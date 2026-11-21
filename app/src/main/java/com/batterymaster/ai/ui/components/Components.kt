package com.batterymaster.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.batterymaster.ai.domain.model.Confidence
import com.batterymaster.ai.domain.model.DataClass
import com.batterymaster.ai.domain.model.Measurement
import com.batterymaster.ai.domain.model.Reading
import com.batterymaster.ai.ui.theme.LocalDataColors
import com.batterymaster.ai.ui.util.Format

@Composable
fun dataClassColor(dc: DataClass): Color {
    val c = LocalDataColors.current
    return when (dc) {
        DataClass.DIRECT_MEASUREMENT -> c.measured
        DataClass.SYSTEM_REPORTED -> c.system
        DataClass.CALCULATED -> c.calculated
        DataClass.ESTIMATED -> c.estimated
        DataClass.PREDICTION -> c.prediction
    }
}

/** Small pill telling the user where a number came from and how much to trust it. */
@Composable
fun ReliabilityBadge(dataClass: DataClass, confidence: Confidence? = null, modifier: Modifier = Modifier) {
    val color = dataClassColor(dataClass)
    val text = buildString {
        append(dataClass.shortLabel)
        if (confidence != null && confidence != Confidence.HIGH) append(" · ${confidence.label.lowercase()} conf.")
    }
    Row(
        modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .semantics { contentDescription = "${dataClass.label}${confidence?.let { ", ${it.label} confidence" } ?: ""}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

/** A metric tile that renders either the value with its provenance, or why it's missing. */
@Composable
fun <T> ReadingTile(
    title: String,
    reading: Reading<T>,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    format: (T) -> String = { it.toString() },
) {
    when (reading) {
        is Reading.Available -> MetricTile(title, format(reading.measurement.value), reading.measurement.unit,
            reading.measurement, modifier, icon)
        is Reading.Unavailable -> UnavailableTile(title, reading.reason, modifier, icon)
    }
}

@Composable
fun MetricTile(
    title: String,
    value: String,
    unit: String?,
    measurement: Measurement<*>?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    dataClass: DataClass? = measurement?.dataClass,
    confidence: Confidence? = measurement?.confidence,
) {
    var showInfo by remember { mutableStateOf(false) }
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon?.let {
                    Icon(it, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                }
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (measurement != null) {
                    IconButton(onClick = { showInfo = true }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Outlined.Info, "About this value", Modifier.size(16.dp))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                if (!unit.isNullOrEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(unit, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp))
                }
            }
            if (dataClass != null) {
                Spacer(Modifier.height(6.dp))
                ReliabilityBadge(dataClass, confidence)
            }
        }
    }
    if (showInfo && measurement != null) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("OK") } },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Type: ${measurement.dataClass.label}")
                    Text("Source: ${measurement.source}")
                    Text("Confidence: ${measurement.confidence.label}")
                    Text("Recorded: ${Format.dateTime(measurement.timestamp)}")
                    measurement.note?.let { Text(it) }
                }
            },
        )
    }
}

@Composable
fun UnavailableTile(title: String, reason: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon?.let {
                    Icon(it, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(6.dp))
                }
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Text("Unavailable", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
            Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Two tiles per row. */
@Composable
fun TileRow(content: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        content(Modifier.weight(1f))
        second(Modifier.weight(1f))
    }
}
