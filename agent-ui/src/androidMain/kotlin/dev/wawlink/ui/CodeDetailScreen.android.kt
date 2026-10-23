package dev.wawlink.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// Native widgets read dp/sp from Context rather than Compose's LocalDensity.
@Composable
internal fun rememberAgentViewContext(): Context {
    val context = LocalContext.current
    val density = LocalDensity.current
    return remember(context, density.density, density.fontScale) {
        ContextThemeWrapper(context, 0).apply {
            applyOverrideConfiguration(Configuration(context.resources.configuration).apply {
                densityDpi = (density.density * 160f).roundToInt()
                fontScale = density.fontScale
            })
        }
    }
}

@Composable
internal actual fun PlatformCodeDetail(onBack: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(
        dismissOnBackPress = true, dismissOnClickOutside = false,
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
    )) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.apply {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            }
        }
        content()
    }
}

@Composable
internal actual fun PlatformCodeViewer(text: String, language: String?, modifier: Modifier, selectionRequest: Int) {
    val context = rememberAgentViewContext()
    val colors = LocalAgentColors.current
    val dark = colors.surface.luminance() < 0.5f
    val supported = language?.let(::canonicalCodeLanguage) != null
    val editor = remember(context) {
        CodeEditor(context).apply {
            isEditable = false
            isWordwrap = false
            isLineNumberEnabled = true
            setTypefaceText(Typeface.MONOSPACE)
            setTextSize(15f)
            setText(text)
        }
    }
    val applied = remember(editor) { AppliedSoraState(text) }
    var tokenizer by remember(language, dark) { mutableStateOf<OfflineTextMateTokenizer?>(null) }
    LaunchedEffect(language, dark) {
        if (supported) tokenizer = withContext(Dispatchers.Default) {
            BundledTextMate.tokenizer(dark).also { language?.let(it::grammar) }
        }
    }
    DisposableEffect(editor) { onDispose { editor.release() } }
    LaunchedEffect(editor, selectionRequest) {
        if (selectionRequest > 0) editor.selectAll()
    }
    key(editor) { AndroidView(factory = { editor }, modifier = modifier, update = { view ->
        if (applied.text != text) {
            view.setText(text)
            applied.text = text
        }
        val prepared = tokenizer
        if (applied.tokenizer !== prepared || applied.language != language) {
            if (prepared != null || !supported) {
                val nativeLanguage = language?.let { prepared?.soraLanguage(it) }
                view.colorScheme = if (nativeLanguage != null && prepared != null) {
                    TextMateColorScheme.create(prepared.themes, prepared.themes.currentThemeModel)
                } else EditorColorScheme()
                view.setEditorLanguage(nativeLanguage ?: EmptyLanguage())
                applied.tokenizer = prepared
                applied.language = language
            }
        }
        val scheme = view.colorScheme
        scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, colors.surface.toArgb())
        scheme.setColor(EditorColorScheme.TEXT_NORMAL, colors.onSurface.toArgb())
        scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, colors.surfaceContainerLow.toArgb())
        scheme.setColor(EditorColorScheme.LINE_NUMBER, colors.outline.toArgb())
        scheme.setColor(EditorColorScheme.LINE_NUMBER_CURRENT, colors.primary.toArgb())
        scheme.setColor(EditorColorScheme.LINE_DIVIDER, colors.outlineVariant.toArgb())
        scheme.setColor(EditorColorScheme.CURRENT_LINE, colors.surfaceContainerLow.toArgb())
        scheme.setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, colors.primaryContainer.toArgb())
        scheme.setColor(EditorColorScheme.TEXT_SELECTED, colors.onPrimaryContainer.toArgb())
        scheme.setColor(EditorColorScheme.SELECTION_HANDLE, colors.primary.toArgb())
        scheme.setColor(EditorColorScheme.TEXT_ACTION_WINDOW_BACKGROUND, colors.surfaceContainerHigh.toArgb())
        scheme.setColor(EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR, colors.onSurface.toArgb())
        // Sora owns asynchronous, document-local incremental spans. Recomposition changes only
        // chrome; it neither copies tokens onto the UI thread nor resets text/selection.
    }) }
}

private class AppliedSoraState(
    var text: String,
    var tokenizer: OfflineTextMateTokenizer? = null,
    var language: String? = null,
)
