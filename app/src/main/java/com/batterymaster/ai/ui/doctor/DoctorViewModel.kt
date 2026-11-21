package com.batterymaster.ai.ui.doctor

import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.batterymaster.ai.data.export.ExportManager
import com.batterymaster.ai.data.repo.BatteryRepository
import com.batterymaster.ai.domain.assistant.AssistantAnswer
import com.batterymaster.ai.domain.assistant.BatteryAssistant
import com.batterymaster.ai.domain.doctor.DoctorReport
import com.batterymaster.ai.domain.usecase.InsightsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DoctorUiState(
    val report: DoctorReport? = null,
    val loading: Boolean = true,
    val conversation: List<AssistantAnswer> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class DoctorViewModel @Inject constructor(
    private val battery: BatteryRepository,
    private val insights: InsightsUseCase,
    private val export: ExportManager,
) : ViewModel() {

    private val _state = MutableStateFlow(DoctorUiState())
    val state: StateFlow<DoctorUiState> = _state.asStateFlow()

    private val shareChannel = Channel<Intent>(Channel.BUFFERED)
    val shareEvents: Flow<Intent> = shareChannel.receiveAsFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { insights.doctorReport(battery.readNow()) }
                .onSuccess { r -> _state.update { it.copy(report = r, loading = false) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: "Analysis failed") } }
        }
    }

    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            val snap = battery.readNow()
            val ctx = insights.assistantContext(snap, _state.value.report)
            val answer = BatteryAssistant.answer(q, ctx)
            _state.update { it.copy(conversation = it.conversation + answer) }
        }
    }

    fun clearConversation() = _state.update { it.copy(conversation = emptyList()) }

    fun shareReport() {
        val report = _state.value.report ?: return
        viewModelScope.launch {
            runCatching {
                val health = insights.assistantContext(battery.readNow(), report).health
                val file = export.writeReportPdf(report, health, "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
                export.shareIntent(file, "application/pdf")
            }.onSuccess { shareChannel.send(it) }
                .onFailure { e -> _state.update { it.copy(error = "Could not create the report: ${e.message}") } }
        }
    }
}
