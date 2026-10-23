package dev.wawlink.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.ui.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/** Offline brand artwork; source/license evidence lives in files/model-icons/. */
@Composable
internal fun ModelIcon(
    modelId: String,
    modelName: String = modelId,
    modifier: Modifier = Modifier,
) {
    val artwork = remember(modelId, modelName) {
        modelArtwork(modelId) ?: modelArtwork(modelName)
    }
    if (artwork == null) {
        val initial = remember(modelId) { modelInitial(modelId).toString() }
        val background = remember(initial) { modelInitialColor(initial.single()) }
        Box(modifier.size(24.dp).background(background, CircleShape), contentAlignment = Alignment.Center) {
            Text(initial, color = Color(0xFF1F1F1F), fontSize = 16.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
        }
    } else {
        // Image, rather than Material Icon, deliberately preserves original brand colors.
        Image(painterResource(artwork), contentDescription = null, modifier = modifier.size(24.dp))
    }
}

@Composable
internal fun ProviderBrandIcon(providerName: String, modifier: Modifier = Modifier) {
    val artwork = remember(providerName) { modelArtwork(providerName) }
    if (artwork == null) {
        Symbol("neurology", modifier.size(24.dp), size = 24.dp, tint = LocalAgentColors.current.onSurfaceVariant)
    } else {
        Image(painterResource(artwork), contentDescription = null, modifier = modifier.size(24.dp))
    }
}

internal fun modelInitial(modelId: String): Char = modelId.firstOrNull { !it.isWhitespace() }?.uppercaseChar() ?: '?'

internal fun modelInitialColor(initial: Char): Color = Color.hsl(
    hue = (initial.uppercaseChar().code * 137.508f) % 360f,
    saturation = .5f,
    lightness = .86f,
)

private val openAiModelId = Regex("(^|[/\\s:_-])(gpt(?:[-\\d]|$)|o[134](?:[-\\s:]|$)|chatgpt)")

private fun modelArtwork(value: String): DrawableResource? {
    val name = value.lowercase()
    return when {
        "deepseek" in name || "深度求索" in name -> Res.drawable.model_deepseek_color
        "claude" in name || "anthropic" in name -> Res.drawable.model_claude_color
        "codex" in name -> Res.drawable.model_codex
        "gemma" in name -> Res.drawable.model_gemma_color
        "gemini" in name || "google" in name -> Res.drawable.model_gemini_color
        "qwen" in name || "qwq" in name || "千问" in name || "通义" in name -> Res.drawable.model_qwen_color
        "kimi" in name || "moonshot" in name || "月之暗面" in name -> Res.drawable.model_kimi_color
        "grok" in name || "xai" in name || "x.ai" in name -> Res.drawable.model_grok
        "minimax" in name -> Res.drawable.model_minimax_color
        "mistral" in name || "mixtral" in name || "codestral" in name || "devstral" in name -> Res.drawable.model_mistral_color
        "llama" in name || "meta" in name -> Res.drawable.model_meta_color
        "openai" in name || openAiModelId.containsMatchIn(name) -> Res.drawable.model_openai
        else -> null
    }
}
