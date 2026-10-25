package dev.wawlink.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.wawlink.runtime.ContainerFeatureSession

@Composable
internal fun ContainerWebSurface(session: ContainerFeatureSession, css: String, modifier: Modifier, onError: (String) -> Unit) {
    AndroidFeatureSurface(
        origin = featureDocumentOrigin(session.url), url = session.url,
        html = null, css = css, data = null, font = null, rounded = false, modifier = modifier,
        onAction = null, onError = onError,
    )
}
