package com.batterymaster.ai.ui.chargertest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.batterymaster.ai.data.db.ChargerTestEntity
import com.batterymaster.ai.data.repo.BatteryRepository
import com.batterymaster.ai.data.repo.HistoryRepository
import com.batterymaster.ai.domain.insights.ChargerTestCalculator
import com.batterymaster.ai.domain.model.BatterySnapshot
import com.batterymaster.ai.domain.model.ChargeStatus
import com.batterymaster.ai.domain.model.Confidence
import com.batterymaster.ai.domain.model.measurementOrNull
import com.batterymaster.ai.domain.model.valueOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface TestPhase {
    data object Idle : TestPhase
    data class Running(val secondsLeft: Int, val warmingUp: Boolean, val lastMa: Int?) : TestPhase
    data class Done(val result: ChargerTestEntity) : TestPhase
    data class Failed(val reason: String) : TestPhase
}

data class ChargerTestUi(
    val phase: TestPhase = TestPhase.Idle,
    val label: String = "",
)

@HiltViewModel
class ChargerTestViewModel @Inject constructor(
    private val battery: BatteryRepository,
    private val history: HistoryRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(ChargerTestUi())
    val ui: StateFlow<ChargerTestUi> = _ui.asStateFlow()

    val snapshot: StateFlow<BatterySnapshot?> = battery.live
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val tests: StateFlow<List<ChargerTestEntity>> = history.observeChargerTests()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var job: Job? = null

    fun setLabel(v: String) = _ui.update { it.copy(label = v.take(40)) }

    fun start() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            val first = battery.readNow()
            if (first == null || !first.isPluggedIn) {
                _ui.update { it.copy(phase = TestPhase.Failed("Plug in the charger and cable you want to test first.")) }
                return@launch
            }
            val currents = ArrayList<Int>()
            val watts = ArrayList<Float>()
            val total = WARMUP_S + MEASURE_S
            for (i in 0 until total) {
                val s = battery.readNow()
                if (s == null || !s.isPluggedIn) {
                    _ui.update { it.copy(phase = TestPhase.Failed("The charger was unplugged during the test.")) }
                    return@launch
                }
                val m = s.currentMa.measurementOrNull
                val warming = i < WARMUP_S
                if (!warming && m != null && m.confidence != Confidence.LOW) {
                    currents += m.value
                    s.powerW.valueOrNull?.let { watts += it }
                }
                _ui.update { it.copy(phase = TestPhase.Running(total - i, warming, m?.value)) }
                delay(1_000)
            }
            val last = battery.readNow() ?: first
            val r = ChargerTestCalculator.compute(currents, watts)
            if (r == null) {
                val why = if (first.status == ChargeStatus.FULL || first.levelPct >= 98) {
                    "The battery is (almost) full, so it isn't accepting current. Test below 80% for a fair result."
                } else {
                    "This phone isn't reporting charging current yet. Leave it charging a few minutes so the app can calibrate, then retry."
                }
                _ui.update { it.copy(phase = TestPhase.Failed(why)) }
                return@launch
            }
            val entity = ChargerTestEntity(
                timestamp = System.currentTimeMillis(),
                label = _ui.value.label.ifBlank { "Charger test" },
                avgCurrentMa = r.avgMa,
                peakCurrentMa = r.peakMa,
                avgPowerW = r.avgW,
                startLevel = first.levelPct,
                endLevel = last.levelPct,
                temperatureC = last.temperatureC.valueOrNull,
                durationSec = MEASURE_S,
            )
            history.addChargerTest(entity)
            _ui.update { it.copy(phase = TestPhase.Done(entity)) }
        }
    }

    fun cancel() {
        job?.cancel()
        _ui.update { it.copy(phase = TestPhase.Idle) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { history.deleteChargerTest(id) }
    }

    companion object {
        /** Fast chargers negotiate for a few seconds; ignore that ramp-up. */
        const val WARMUP_S = 10
        const val MEASURE_S = 45
    }
}
