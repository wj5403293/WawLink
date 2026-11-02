package dev.wawlink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A bounded, selectable runtime error summary with an explicit full-details affordance.
 * The detail dialog never mutates the state that produced the error.
 */
@Composable
fun ErrorMessage(
    message: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.error,
    fontSize: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    details: String = message,
) {
    val safeMessage = remember(message) { redactErrorDetails(message) }
    val safeDetails = remember(details) { redactErrorDetails(details) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SelectionContainer {
            Text(
                safeMessage,
                color = color,
                fontSize = fontSize,
                lineHeight = lineHeight,
                style = style,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ErrorDetailsButton(safeMessage, safeDetails)
    }
}

/** Compact entry point for existing horizontal error rows. */
@Composable
fun ErrorDetailsButton(message: String, details: String = message) {
    var showDetails by remember(message, details) { mutableStateOf(false) }
    TextButton(onClick = { showDetails = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Text("查看详情", fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
    if (showDetails) ErrorDetailsDialog(message, details) { showDetails = false }
}

/** Shared Material 3 dialog used by runtime errors and host notifications. */
@Composable
fun ErrorDetailsDialog(summary: String, details: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val safeSummary = remember(summary) { redactErrorDetails(summary) }
    val safeDetails = remember(details) { redactErrorDetails(details) }
    var copyFailure by remember(safeDetails) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("错误详情") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (safeSummary.isNotBlank()) Text(safeSummary, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (safeDetails != safeSummary || safeSummary.isBlank()) SelectionContainer { Text(safeDetails.ifBlank { safeSummary }) }
                copyFailure?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    clipboard.setText(AnnotatedString(safeDetails.ifBlank { safeSummary }))
                    copyFailure = null
                } catch (failure: Exception) {
                    copyFailure = failure.message ?: "复制失败，剪贴板不可用"
                }
            }) { Text("复制详情") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private val authorizationCredentialPattern = Regex("(?i)(\\bAuthorization\\s*[:=]\\s*(?:(?:Bearer|Basic)\\s+)?|\\b(?:Bearer|Basic)\\s+)[^\\s,;]+")
private val credentialKeyPattern = Regex("(?i)([\\\"']?(?:token|api[_-]?key|password|passwd|secret)[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)")
private val urlUserInfoPattern = Regex("(?i)([a-z][a-z0-9+.-]*://)([^/\\s:@]+(?::[^/@\\s]*)?@)")

/** Keep common credential-bearing fragments out of both the summary and copied details. */
internal fun redactErrorDetails(value: String): String {
    var result = authorizationCredentialPattern.replace(value) { "${it.groupValues[1]}***" }
    result = urlUserInfoPattern.replace(result) { "${it.groupValues[1]}***@" }
    result = credentialKeyPattern.replace(result) { "${it.groupValues[1]}***" }
    return result
}
