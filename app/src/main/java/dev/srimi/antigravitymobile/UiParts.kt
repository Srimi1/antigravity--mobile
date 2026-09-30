package dev.srimi.antigravitymobile

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun AntigravityTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFFAFCEFF), secondary = Color(0xFFC9B8FF), background = Color(0xFF11151D),
        surface = Color(0xFF11151D), surfaceVariant = Color(0xFF1E2430),
    ) else lightColorScheme(primary = Color(0xFF2F5DB8), secondary = Color(0xFF6A4FC8))
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable fun StatusChip(label: String, status: String) {
    val color = when (status) {
        "VERIFIED", "PASSED", "COMPLETED", "ACCEPTED", "COMMITTED" -> Color(0xFF3FA66B)
        "CONNECTED", "REVIEW", "RUNNING", "AWAITING_APPROVAL", "UNVERIFIED", "OPEN" -> Color(0xFFCB8A1E)
        "EXPIRED", "DISCONNECTED", "DECLINED", "REVERTED", "CANCELLED", "INTERRUPTED" -> Color(0xFF7A8699)
        else -> MaterialTheme.colorScheme.error
    }
    Surface(color = color.copy(alpha = 0.18f), contentColor = color, shape = RoundedCornerShape(50)) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** Colored unified diff. Long lines scroll horizontally rather than wrapping. */
@Composable fun DiffView(diff: String, maxLines: Int = 400) {
    val lines = diff.lines()
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
        .horizontalScroll(rememberScrollState()).padding(8.dp)) {
        lines.take(maxLines).forEach { line ->
            val color = when {
                line.startsWith("+++") || line.startsWith("---") -> MaterialTheme.colorScheme.onSurfaceVariant
                line.startsWith("+") -> Color(0xFF3FA66B)
                line.startsWith("-") -> Color(0xFFE0605A)
                line.startsWith("@@") -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.onSurface
            }
            Text(line.ifEmpty { " " }, color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false)
        }
        if (lines.size > maxLines) Text("… ${lines.size - maxLines} more lines", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable fun TextPromptDialog(
    title: String, label: String, confirm: String, initial: String = "", secondLabel: String? = null,
    onDismiss: () -> Unit, onConfirm: (String, String) -> Unit,
) {
    var first by remember { mutableStateOf(initial) }
    var second by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(first, { first = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (secondLabel != null) OutlinedTextField(second, { second = it }, label = { Text(secondLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(first, second) }, enabled = first.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable fun ConfirmDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(body) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable fun EmptyState(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}

@Composable fun BusyRow(label: String, detail: String = "", onCancel: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label + if (detail.isNotEmpty()) " · $detail" else "", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge)
            if (onCancel != null) TextButton(onClick = onCancel) { Text("Stop") }
        }
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

fun formatTime(millis: Long): String = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(millis))
