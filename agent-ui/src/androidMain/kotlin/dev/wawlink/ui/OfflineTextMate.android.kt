package dev.wawlink.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import dev.wawlink.ui.resources.Res
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.tm4e.core.grammar.IGrammar
import org.eclipse.tm4e.core.grammar.IStateStack
import org.eclipse.tm4e.core.internal.grammar.tokenattrs.EncodedTokenAttributes
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IRegistryOptions
import org.eclipse.tm4e.core.registry.IThemeSource
import org.eclipse.tm4e.core.registry.Registry

/** Bundled bytes are shared; inline registries are serialized, and native editors own analyzers. */
internal object BundledTextMate {
    private const val root = "files/code/textmate/"
    private val mutex = Mutex()
    private var resources: Map<String, ByteArray>? = null
    private val highlightMutex = Mutex()
    private var lightHighlighter: OfflineTextMateTokenizer? = null
    private var darkHighlighter: OfflineTextMateTokenizer? = null

    /** Inline jobs share two confined registries; native editors still own separate analyzers. */
    suspend fun highlight(dark: Boolean, language: String, text: String): AnnotatedString = highlightMutex.withLock {
        val cached = if (dark) darkHighlighter else lightHighlighter
        val lexer = cached ?: tokenizer(dark).also {
            if (dark) darkHighlighter = it else lightHighlighter = it
        }
        lexer.highlight(language, text)
    }

    suspend fun tokenizer(dark: Boolean): OfflineTextMateTokenizer {
        val bytes = mutex.withLock {
            resources ?: withContext(Dispatchers.IO) {
                val index = Res.readBytes(root + "index.json")
                val data = Json.parseToJsonElement(index.decodeToString()).jsonObject
                val paths = (data.getValue("grammars").jsonObject.values + data.getValue("themes").jsonObject.values)
                    .map { it.jsonPrimitive.content }.distinct()
                buildMap {
                    put("index.json", index)
                    for (path in paths) put(path, Res.readBytes(root + path))
                }.also { resources = it }
            }
        }
        return OfflineTextMateTokenizer(bytes.getValue("index.json").decodeToString(), { bytes.getValue(it) }, dark)
    }
}

/**
 * UI/context-free entry point for JVM tokenizer smoke tests. The supplied reader resolves only
 * bundled relative paths. No source, shebang, include, URL, or language configuration is executed.
 * Instances are deliberately not shared between editor analyzers or concurrent highlighting jobs.
 */
internal class OfflineTextMateTokenizer(index: String, private val readResource: (String) -> ByteArray, dark: Boolean) {
    private val manifest = Json.parseToJsonElement(index).jsonObject
    private val languages = manifest.getValue("languages").jsonObject.mapValues { it.value.jsonPrimitive.content }
    private val grammars = manifest.getValue("grammars").jsonObject.mapValues { it.value.jsonPrimitive.content }
    private val injections = manifest.getValue("injections").jsonObject.mapValues { entry ->
        entry.value.jsonArray.map { it.jsonPrimitive.content }
    }
    val themes = ThemeRegistry().apply {
        val path = manifest.getValue("themes").jsonObject.getValue(if (dark) "dark" else "light").jsonPrimitive.content
        loadTheme(IThemeSource.fromInputStream(readResource(path).inputStream(), path, null))
        currentThemeModel.isDark = dark
    }
    private val registry = Registry(object : IRegistryOptions {
        override fun getGrammarSource(scopeName: String): IGrammarSource? = grammars[scopeName]?.let { path ->
            IGrammarSource.fromInputStream(readResource(path).inputStream(), path, null)
        }
        override fun getInjections(scopeName: String): Collection<String>? = injections[scopeName]
    }).apply { setTheme(themes.currentThemeModel.theme) }

    fun grammar(language: String): IGrammar? = languages[language]?.let(registry::loadGrammar)

    fun soraLanguage(language: String): TextMateLanguage? {
        val grammar = grammar(language) ?: return null
        // Use Sora's real incremental TextMate analyzer. A local registry adapter preserves TM4E's
        // dependency-aware loader (including HTML JS/CSS and regex subgrammars) without globals.
        val adapter = object : GrammarRegistry(null) {
            override fun findGrammar(scopeName: String): IGrammar? = if (scopeName == grammar.scopeName) grammar else null
        }
        return TextMateLanguage.create(grammar.scopeName, adapter, themes, false).apply {
            isAutoCompleteEnabled = false
        }
    }

    data class Token(val start: Int, val end: Int, val scopes: List<String>)

    /** Real lexical scopes, useful for edge regressions without mounting an Android view. */
    fun tokenize(language: String, text: String): List<Token> {
        val grammar = grammar(language) ?: return emptyList()
        val tokens = ArrayList<Token>()
        var state: IStateStack? = null
        var offset = 0
        for (line in text.splitToSequence('\n')) {
            val result = grammar.tokenizeLine(line, state, null)
            state = result.ruleStack
            for (token in result.tokens) {
                val start = token.startIndex.coerceIn(0, line.length)
                val end = token.endIndex.coerceIn(start, line.length)
                if (start < end) tokens += Token(offset + start, offset + end, token.scopes)
            }
            offset += line.length + 1
        }
        return tokens
    }

    suspend fun highlight(language: String, text: String): AnnotatedString {
        val grammar = grammar(language) ?: return AnnotatedString(text)
        val builder = AnnotatedString.Builder(text)
        val styles = HashMap<Int, SpanStyle>()
        var state: IStateStack? = null
        var offset = 0
        for (line in text.splitToSequence('\n')) {
            currentCoroutineContext().ensureActive()
            val result = grammar.tokenizeLine2(line, state, null)
            state = result.ruleStack
            val tokens = result.tokens
            val palette = registry.colorMap
            var i = 0
            while (i < tokens.size) {
                val start = tokens[i].coerceIn(0, line.length)
                val end = (if (i + 2 < tokens.size) tokens[i + 2] else line.length).coerceIn(start, line.length)
                val metadata = tokens[i + 1]
                if (start < end) {
                    val style = styles.getOrPut(metadata) {
                        val foreground = palette.getOrNull(EncodedTokenAttributes.getForeground(metadata))
                        val font = EncodedTokenAttributes.getFontStyle(metadata)
                        SpanStyle(
                            color = foreground?.takeIf { it.startsWith('#') }?.let {
                                val rgb = it.removePrefix("#").toLong(16)
                                Color((if (it.length == 7) rgb or 0xff000000L else (rgb ushr 8) or ((rgb and 255) shl 24)).toInt())
                            } ?: Color.Unspecified,
                            fontWeight = if (font and 2 != 0) FontWeight.Bold else null,
                            fontStyle = if (font and 1 != 0) FontStyle.Italic else null,
                            textDecoration = when (font and 12) {
                                4 -> TextDecoration.Underline
                                8 -> TextDecoration.LineThrough
                                12 -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                                else -> null
                            },
                        )
                    }
                    builder.addStyle(style, offset + start, offset + end)
                }
                i += 2
            }
            offset += line.length + 1
        }
        return builder.toAnnotatedString()
    }
}

internal actual suspend fun highlightPlatformCode(text: String, language: String, colors: AgentColorScheme): AnnotatedString =
    withContext(Dispatchers.Default) {
        BundledTextMate.highlight(colors.surface.luminance() < 0.5f, language, text)
    }
