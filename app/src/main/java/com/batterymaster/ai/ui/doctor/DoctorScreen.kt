package com.batterymaster.ai.ui.doctor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterymaster.ai.R
import com.batterymaster.ai.domain.assistant.AssistantAnswer
import com.batterymaster.ai.domain.assistant.BatteryAssistant
import com.batterymaster.ai.domain.doctor.DoctorReport
import com.batterymaster.ai.domain.doctor.Finding
import com.batterymaster.ai.domain.doctor.Severity
import com.batterymaster.ai.ui.components.AppCard
import com.batterymaster.ai.ui.components.SectionHeader
import com.batterymaster.ai.ui.theme.LocalDataColors
import com.batterymaster.ai.ui.util.Format

@Composable
fun DoctorScreen(padding: PaddingValues, vm: DoctorViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var question by remember { mutableStateOf("") }
    var showCriteria by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.shareEvents.collect { runCatching { context.startActivity(it) } } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            val r = st.report
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.doctor_title), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = vm::refresh) { Icon(Icons.Outlined.Refresh, stringResource(R.string.reanalyze)) }
                    IconButton(onClick = vm::shareReport, enabled = r != null) {
                        Icon(Icons.Outlined.PictureAsPdf, stringResource(R.string.share_pdf))
                    }
                }
                if (st.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                st.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (r != null) ScoreHeader(r)
                TextButton(onClick = { showCriteria = !showCriteria }) {
                    Text(if (showCriteria) stringResource(R.string.hide_scoring) else stringResource(R.string.show_scoring))
                }
                if (showCriteria) {
                    DoctorReport.SCORING_CRITERIA.forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        val findings = st.report?.findings.orEmpty()
        itemsIndexed(findings, key = { i, f -> "f$i-${f.title}" }) { _, f -> FindingCard(f) }

        item { SectionHeader(stringResource(R.string.ask_assistant)) }
        item {
            AppCard {
                Text(stringResource(R.string.assistant_privacy), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BatteryAssistant.SUGGESTED_QUESTIONS.forEach { q ->
                        AssistChip(onClick = { vm.ask(q) }, label = { Text(q) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = question,
                        onValueChange = { question = it.take(200) },
                        placeholder = { Text(stringResource(R.string.ask_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { vm.ask(question); question = "" }),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { vm.ask(question); question = "" }, enabled = question.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.send))
                    }
                }
            }
        }
        itemsIndexed(st.conversation.asReversed(), key = { i, a -> "a${st.conversation.size - i}-${a.question}" }) { _, a ->
            AnswerCard(a)
        }
        if (st.conversation.isNotEmpty()) {
            item { TextButton(onClick = vm::clearConversation) { Text(stringResource(R.string.clear_conversation)) } }
        }
    }
}

@Composable
private fun ScoreHeader(r: DoctorReport) {
    val c = LocalDataColors.current
    val score = r.score
    val color = when {
        score == null -> MaterialTheme.colorScheme.outline
        score >= 85 -> c.good
        score >= 70 -> c.notice
        else -> c.warning
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { (score ?: 0) / 100f }, modifier = Modifier.size(84.dp), strokeWidth = 8.dp, color = color,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(score?.toString() ?: "–", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(r.condition, style = MaterialTheme.typography.titleLarge, color = color, fontWeight = FontWeight.SemiBold)
            Text("Confidence: ${r.confidence.label}", style = MaterialTheme.typography.bodySmall)
            Text("Analysed ${Format.dateTime(r.generatedAt)}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Text(r.dataSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FindingCard(f: Finding) {
    val c = LocalDataColors.current
    val color = when (f.severity) {
        Severity.WARNING -> c.warning
        Severity.NOTICE -> c.notice
        Severity.GOOD -> c.good
        Severity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    AppCard {
        Text(f.category.uppercase(), style = MaterialTheme.typography.labelSmall, color = color)
        Text(f.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(f.detail, style = MaterialTheme.typography.bodyMedium)
        if (f.evidence.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("Evidence", style = MaterialTheme.typography.labelMedium)
            f.evidence.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
        f.recommendation?.let {
            Spacer(Modifier.height(6.dp))
            Text("What you can do", style = MaterialTheme.typography.labelMedium)
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Confidence: ${f.confidence.label}" + if (f.penalty > 0) " · −${f.penalty} points" else "",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AnswerCard(a: AssistantAnswer) {
    AppCard {
        Text(a.question, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Text(a.detected, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(a.whyItMatters, style = MaterialTheme.typography.bodyMedium)
        if (a.evidence.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("Based on", style = MaterialTheme.typography.labelMedium)
            a.evidence.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
        if (a.actions.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("What you can do", style = MaterialTheme.typography.labelMedium)
            a.actions.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
        Spacer(Modifier.height(4.dp))
        Text("Confidence: ${a.confidence.label}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
