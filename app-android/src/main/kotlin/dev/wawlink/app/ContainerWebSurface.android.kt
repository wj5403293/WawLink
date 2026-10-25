package dev.wawlink.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.wawlink.runtime.ContainerFeatureSession
import java.net.URI

@Composable
internal fun ContainerWebSurface(session: ContainerFeatureSession, css: String, modifier: Modifier, onError: (String) -> Unit) {
    val uri = URI(session.url)
    AndroidFeatureSurface(
        origin = "${uri.scheme}://${uri.host}:${uri.port}", url = session.url,
        html = null, css = css, data = null, font = null, modifier = modifier,
        onAction = null, onError = onError,
    )
}
