package com.batterymaster.ai.ui.apps

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterymaster.ai.R
import com.batterymaster.ai.domain.model.DataClass
import com.batterymaster.ai.domain.usage.AppPowerCalculator
import com.batterymaster.ai.ui.components.AppCard
import com.batterymaster.ai.ui.components.ReliabilityBadge
import com.batterymaster.ai.ui.util.Format

@Composable
fun AppsScreen(padding: PaddingValues, vm: AppsViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { vm.stop() }
    }
    val screenOffLabel = stringResource(R.string.apps_screen_off)
    val unknownLabel = stringResource(R.string.apps_unknown)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AppCard {
                Text(stringResource(R.string.apps_now), style = MaterialTheme.typography.labelLarge)
                Text(
                    st.liveCurrentMa?.let { "%+,d mA".format(it) } ?: "—",
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                )
                Text(stringResource(R.string.apps_explain), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!st.hasAccess) {
            item {
                AppCard {
                    Text(stringResource(R.string.apps_access_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.apps_access_body), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) { Text(stringResource(R.string.apps_grant)) }
                }
            }
        }
        if (!st.monitoringEnabled) {
            item {
                AppCard { Text(stringResource(R.string.apps_monitoring_hint), style = MaterialTheme.typography.bodySmall) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(st.period == AppsPeriod.LAST_15_MIN, onClick = { vm.setPeriod(AppsPeriod.LAST_15_MIN) },
                    label = { Text(stringResource(R.string.apps_last_15)) })
                FilterChip(st.period == AppsPeriod.TODAY, onClick = { vm.setPeriod(AppsPeriod.TODAY) },
                    label = { Text(stringResource(R.string.apps_today)) })
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildString {
                        st.totalMah?.let { append("≈ ${it.toInt()} mAh · ") }
                        append("${Format.num(st.totalPct, 0)}% ")
                        append("used while unplugged")
                    },
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                )
                ReliabilityBadge(DataClass.ESTIMATED)
            }
        }
        if (!st.loading && st.rows.isEmpty()) {
            item { Text(stringResource(R.string.apps_empty), style = MaterialTheme.typography.bodyMedium) }
        }
        items(st.rows, key = { it.key }) { r ->
            val label = when (r.key) {
                AppPowerCalculator.SCREEN_OFF -> screenOffLabel
                AppPowerCalculator.UNKNOWN -> unknownLabel
                else -> r.label
            }
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (r.icon != null) {
                        Image(r.icon.asImageBitmap(), null, Modifier.size(36.dp))
                    } else {
                        Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center) {
                            Icon(if (r.key == AppPowerCalculator.SCREEN_OFF) Icons.Outlined.DarkMode else Icons.Outlined.Apps, null)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            buildString {
                                append(Format.durationMs(r.durationMs))
                                r.avgMa?.let { append(" · avg ${it.toInt()} mA") }
                                r.screenTimeMs?.let { append(" · ${Format.durationMs(it)} on screen today") }
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(r.mah?.let { "${it.toInt()} mAh" } ?: "${Format.num(r.pctDrop, 0)}%",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("${Format.num(r.sharePct, 0)}%", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { (r.sharePct / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
