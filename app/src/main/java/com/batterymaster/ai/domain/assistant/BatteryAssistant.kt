package com.batterymaster.ai.domain.assistant

import com.batterymaster.ai.domain.doctor.DoctorReport
import com.batterymaster.ai.domain.doctor.Severity
import com.batterymaster.ai.domain.health.HealthSummary
import com.batterymaster.ai.domain.model.BatterySnapshot
import com.batterymaster.ai.domain.model.Confidence
import com.batterymaster.ai.domain.model.SessionSummary
import com.batterymaster.ai.domain.model.measurementOrNull
import com.batterymaster.ai.domain.model.valueOrNull

data class AssistantContext(
    val snapshot: BatterySnapshot?,
    val health: HealthSummary,
    val report: DoctorReport?,
    val lastCharge: SessionSummary?,
    val avgDrainPctPerHour7d: Float?,
    val chargeTargetPct: Int,
    val protectionDescription: String,
)

data class AssistantAnswer(
    val question: String,
    val detected: String,
    val whyItMatters: String,
    val evidence: List<String>,
    val actions: List<String>,
    val confidence: Confidence,
)

/**
 * Local, offline, rule-based battery assistant. Every answer is built only from the
 * measurements in [AssistantContext]; when data is missing it says so instead of guessing.
 */
object BatteryAssistant {
    val SUGGESTED_QUESTIONS = listOf(
        "How healthy is my battery?",
        "Why is my battery draining fast?",
        "Is my phone too hot?",
        "How was my last charge?",
        "What charging limit should I use?",
        "How long will my battery last?",
    )

    private enum class Topic { HEALTH, DRAIN, TEMPERATURE, CHARGING, LIMIT, RUNTIME, UNKNOWN }

    private val keywords = linkedMapOf(
        Topic.LIMIT to listOf("limit", "target", "80", "stop charging", "overcharg"),
        Topic.TEMPERATURE to listOf("hot", "heat", "temperature", "temp", "warm", "overheat"),
        Topic.DRAIN to listOf("drain", "fast", "dying", "losing", "discharge", "standby"),
        Topic.RUNTIME to listOf("how long", "last", "remaining", "runtime", "until empty"),
        Topic.CHARGING to listOf("charge", "charging", "charger", "plug", "speed", "watt"),
        Topic.HEALTH to listOf("health", "capacity", "degrad", "wear", "replace", "old", "mah", "cycle"),
    )

    fun answer(question: String, ctx: AssistantContext): AssistantAnswer {
        val q = question.lowercase()
        // "How was my last charge" must not be routed to RUNTIME by the word "last".
        val topic = when {
            "last charge" in q || "last charging" in q -> Topic.CHARGING
            else -> keywords.entries.firstOrNull { (_, words) -> words.any { it in q } }?.key ?: Topic.UNKNOWN
        }
        return when (topic) {
            Topic.HEALTH -> health(question, ctx)
            Topic.DRAIN -> drain(question, ctx)
            Topic.TEMPERATURE -> temperature(question, ctx)
            Topic.CHARGING -> charging(question, ctx)
            Topic.LIMIT -> limit(question, ctx)
            Topic.RUNTIME -> runtime(question, ctx)
            Topic.UNKNOWN -> AssistantAnswer(
                question,
                "I can only answer questions about this phone's battery, using data recorded on it.",
                "Answers are generated on your device from your own measurements — nothing is sent online.",
                emptyList(),
                listOf("Try one of: " + SUGGESTED_QUESTIONS.joinToString(" · ")),
                Confidence.NONE,
            )
        }
    }

    private fun health(q: String, c: AssistantContext): AssistantAnswer {
        val h = c.health
        val cap = h.estimatedCapacity
        if (cap == null) {
            return AssistantAnswer(q, "There isn't a reliable capacity estimate yet.", h.explanation, emptyList(),
                listOf("Keep monitoring on and let the phone charge across a wide range, e.g. 20% → 90%."), Confidence.NONE)
        }
        val pct = h.healthPct?.value
        val detected = if (pct != null) {
            "Estimated health is about ${"%.0f".format(pct)}% (${cap.value} mAh of ${h.designCapacityMah} mAh design)."
        } else {
            "Estimated full-charge capacity is about ${cap.value} mAh."
        }
        val evidence = buildList {
            add("${h.usedEstimates} qualifying charging session(s), spread ±${"%.1f".format(h.dispersionPct ?: 0f)}%")
            h.method?.let { add("Method: ${it.label}") }
            c.snapshot?.cycleCount?.valueOrNull?.let { add("Charge cycles reported by Android: $it") }
        }
        val actions = buildList {
            if (pct == null) add("Enter the design capacity in Settings to get a health percentage.")
            if (pct != null && pct < 80) add("Shorter runtime is expected. Consider a battery service if it no longer gets you through the day.")
            add("Estimates improve with more long charging sessions.")
        }
        return AssistantAnswer(q, detected,
            "Capacity is how much charge the battery can hold. It drops slowly with age, heat and time spent at 100%.",
            evidence, actions, h.confidence)
    }

    private fun drain(q: String, c: AssistantContext): AssistantAnswer {
        val rate = c.avgDrainPctPerHour7d
        val standby = c.report?.findings?.firstOrNull { it.category == "Standby" }
        val current = c.snapshot?.currentMa?.measurementOrNull
        val evidence = buildList {
            rate?.let { add("Average discharge over 7 days: ${"%.1f".format(it)}%/h") }
            standby?.let { add(it.detail) }
            if (current != null && current.value < 0) add("Current draw right now: ${-current.value} mA")
        }
        if (evidence.isEmpty()) {
            return AssistantAnswer(q, "Not enough discharge history yet.", "Drain analysis needs at least a few hours unplugged with monitoring on.",
                emptyList(), listOf("Enable continuous monitoring in Settings and check back later."), Confidence.NONE)
        }
        val high = (rate ?: 0f) > 8f || standby?.severity == Severity.NOTICE
        return AssistantAnswer(
            q,
            if (high) "Your drain looks higher than typical." else "Your drain looks within a normal range.",
            "Drain depends mostly on screen time, signal strength and background apps. This app sees battery level and current, not per-app energy.",
            evidence,
            listOf(
                "Open Android's Battery usage screen to see which apps used the most.",
                "Weak mobile signal and high screen brightness are common hidden causes.",
            ),
            if (rate != null) Confidence.MEDIUM else Confidence.LOW,
        )
    }

    private fun temperature(q: String, c: AssistantContext): AssistantAnswer {
        val now = c.snapshot?.temperatureC?.valueOrNull
        val tf = c.report?.findings?.firstOrNull { it.category == "Temperature" }
        if (now == null && tf == null) {
            return AssistantAnswer(q, "This device isn't reporting battery temperature.", "Without a temperature sensor reading, heat can't be assessed.",
                emptyList(), emptyList(), Confidence.NONE)
        }
        val detected = when {
            now == null -> tf!!.title
            now >= 45f -> "The battery is hot right now (${"%.1f".format(now)} °C)."
            now >= 40f -> "The battery is warm right now (${"%.1f".format(now)} °C)."
            else -> "The battery temperature is fine right now (${"%.1f".format(now)} °C)."
        }
        return AssistantAnswer(
            q, detected,
            "Lithium-ion batteries age fastest when hot. Roughly 20–35 °C is comfortable; sustained time above 40 °C adds wear.",
            listOfNotNull(now?.let { "Current battery temperature: ${"%.1f".format(it)} °C" }, tf?.detail),
            listOf("Let it cool before fast charging.", "Avoid sun, car dashboards and charging under pillows."),
            Confidence.HIGH,
        )
    }

    private fun charging(q: String, c: AssistantContext): AssistantAnswer {
        val s = c.lastCharge ?: return AssistantAnswer(q, "No completed charging session recorded yet.",
            "Sessions are recorded while monitoring is on.", emptyList(), listOf("Plug in with monitoring enabled."), Confidence.NONE)
        val evidence = buildList {
            add("${s.startLevel}% → ${s.endLevel}% in ${s.durationMs / 60_000} min")
            s.ratePctPerHour?.let { add("Average speed: ${"%.0f".format(it)}%/h") }
            s.avgCurrentMa?.let { add("Average current into battery: $it mA") }
            s.avgPowerW?.let { add("Average battery-side power: ${"%.1f".format(it)} W") }
            s.maxTempC?.let { add("Peak temperature: ${"%.1f".format(it)} °C") }
            if (s.dataGaps > 0) add("${s.dataGaps} gap(s) in measurements")
        }
        val hot = (s.maxTempC ?: 0f) >= 42f
        return AssistantAnswer(
            q,
            if (hot) "Your last charge ran warm." else "Your last charge looked normal.",
            "Charging speed naturally slows above ~80% as the charger switches to constant-voltage mode.",
            evidence,
            if (hot) listOf("Charge somewhere cooler or remove the case while fast charging.") else emptyList(),
            if (s.sampleCount >= 10) Confidence.HIGH else Confidence.MEDIUM,
        )
    }

    private fun limit(q: String, c: AssistantContext): AssistantAnswer = AssistantAnswer(
        q,
        "Your charging target is ${c.chargeTargetPct}%. Protection method on this device: ${c.protectionDescription}.",
        "Keeping a lithium-ion battery below 100% for long periods, especially when warm, reduces long-term wear. The benefit is real but gradual.",
        listOfNotNull(c.report?.findings?.firstOrNull { it.title == "Most charges go to 100%" }?.detail),
        listOf(
            "80–90% is a common balance between runtime and longevity.",
            "Android doesn't let ordinary apps cut power to the battery, so this app alerts you to unplug. Some phones have a built-in limit in system battery settings that does stop charging.",
        ),
        Confidence.HIGH,
    )

    private fun runtime(q: String, c: AssistantContext): AssistantAnswer {
        val s = c.snapshot
        val rate = c.avgDrainPctPerHour7d
        if (s == null || rate == null || rate <= 0.1f) {
            return AssistantAnswer(q, "Not enough discharge history to estimate runtime.", "Runtime predictions use your own drain history.",
                emptyList(), listOf("Keep monitoring on for a day."), Confidence.NONE)
        }
        val hours = s.levelPct / rate
        return AssistantAnswer(
            q,
            "At your 7-day average drain, ${s.levelPct}% would last roughly ${"%.1f".format(hours)} hours.",
            "This is a forecast based on average use; heavy use (games, navigation, camera) drains much faster.",
            listOf("Average drain: ${"%.1f".format(rate)}%/h", "Current level: ${s.levelPct}%"),
            listOf("The Home screen shows a live estimate based on the last hour."),
            Confidence.LOW,
        )
    }
}
