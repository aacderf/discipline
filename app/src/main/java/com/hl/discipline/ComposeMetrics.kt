package com.hl.discipline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Presentation only: values always come from the existing timer/report/task APIs. */
@Immutable
internal data class MetricPart(val value: String, val unit: String = "")

@Composable
internal fun MetaPill(text: String, modifier: Modifier = Modifier) {
    MetaPill(modifier) { Text(text, maxLines = 2) }
}

@Composable
internal fun MetaPill(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    ProvideTextStyle(MaterialTheme.typography.labelMedium.copy(
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)) {
        Row(modifier.background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .5f),
            RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
internal fun MetricValueText(parts: List<MetricPart>, modifier: Modifier = Modifier,
    fontSize: TextUnit = 26.sp, unitSize: TextUnit = MaterialTheme.typography.labelMedium.fontSize) {
    // Wrap whole number/unit pairs at large font scales; never shrink a rendered text layer.
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        parts.forEach { part ->
            Row(Modifier.alignByBaseline(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(part.value, Modifier.alignByBaseline(), style = AppTypography.number.copy(lineHeight = fontSize * 1.25f),
                    fontSize = fontSize, fontWeight = FontWeight.Bold, softWrap = false,
                    color = MaterialTheme.colorScheme.onSurface)
                if (part.unit.isNotEmpty()) Text(part.unit, Modifier.alignByBaseline(),
                    style = MaterialTheme.typography.labelMedium, fontSize = unitSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

internal fun minuteMetricParts(minutes: Long): List<MetricPart> = if (minutes >= 60)
    listOf(MetricPart("${minutes / 60}", "小时"), MetricPart("${minutes % 60}", "分"))
    else listOf(MetricPart("$minutes", "分钟"))

internal fun durationMetricParts(millis: Long) = minuteMetricParts(millis / Rules.MINUTE)

private val amountPattern = Regex("^([+-]?\\d+(?:\\.\\d+)?)\\s*/\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*(.*)$")

/** Split the existing formatted amount; do not calculate task progress a second time. */
internal fun amountMetricParts(amount: String): List<MetricPart> {
    val match = amountPattern.matchEntire(amount.trim()) ?: return listOf(MetricPart(amount))
    return listOf(MetricPart(match.groupValues[1], "/"),
        MetricPart(match.groupValues[2], match.groupValues[3]))
}
