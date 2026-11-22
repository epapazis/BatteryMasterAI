package com.batterymaster.ai.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.batterymaster.ai.data.db.AppDatabase
import com.batterymaster.ai.data.db.SampleEntity
import com.batterymaster.ai.data.recording.SessionManager
import com.batterymaster.ai.data.db.toPoint
import com.batterymaster.ai.data.repo.HistoryRepository
import com.batterymaster.ai.domain.analytics.ChartMetric
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private lateinit var db: AppDatabase
    private lateinit var history: HistoryRepository
    private lateinit var sessions: SessionManager
    private val t0 = 1_700_000_000_000L
    private val min = 60_000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        history = HistoryRepository(db, db.sampleDao(), db.sessionDao(), db.capacityDao(), db.protectionDao(), db.chargerTestDao())
        sessions = SessionManager(db, db.sessionDao(), db.sampleDao(), db.capacityDao())
    }

    @After fun tearDown() = db.close()

    private fun sample(t: Long, level: Int, plug: String, counter: Int? = null) = SampleEntity(
        timestamp = t, levelPct = level, status = if (plug == "NONE") "DISCHARGING" else "CHARGING", plug = plug,
        temperatureC = 30f, voltageMv = 4000, currentMa = null, currentConfidence = null, chargeCounterMah = counter,
        screenOn = true, source = "TEST",
    )

    @Test fun duplicateTimestampsAreIgnored() = runTest {
        val dao = db.sampleDao()
        assertTrue(dao.insert(sample(t0, 50, "AC")) > 0)
        assertEquals(-1L, dao.insert(sample(t0, 51, "AC")))
        assertEquals(50, dao.latest()!!.levelPct)
    }

    @Test fun chargingSessionIsClosedAndProducesCapacityEstimate() = runTest {
        val dao = db.sampleDao()
        // 0.7 %/min from 20% to 90% on a 4500 mAh battery, then unplug.
        var exact = 20.0
        var t = t0
        while (exact <= 90.0) {
            val s = sample(t, Math.round(exact).toInt(), "AC", counter = (exact / 100 * 4500).toInt())
            dao.insert(s)
            sessions.onSample(s.toPoint())
            exact += 0.7
            t += min
        }
        val unplug = sample(t, 90, "NONE")
        dao.insert(unplug)
        sessions.onSample(unplug.toPoint())

        val all = history.allSessions()
        assertEquals(2, all.size)
        val charge = all.first()
        assertNotNull(charge.endTime)
        assertEquals(20, charge.startLevel)
        val est = history.allEstimates().filter { it.accepted }
        assertTrue(est.isNotEmpty())
        assertTrue(kotlin.math.abs(est.first().capacityMah!! - 4500) < 200)
        assertNull(all.last().endTime) // discharge session still open
    }

    @Test fun bucketsAggregateInSql() = runTest {
        val dao = db.sampleDao()
        (0 until 120).forEach { i -> dao.insert(sample(t0 + i * min, 100 - i / 2, "NONE")) }
        val buckets = history.observeBuckets(ChartMetric.LEVEL, t0, t0 + 120 * min, 3_600_000L).first()
        assertTrue(buckets.size in 2..3)
        assertEquals(120, buckets.sumOf { it.count })
        assertTrue(buckets.all { it.min <= it.avg && it.avg <= it.max })
    }

    @Test fun deleteAllClearsEverything() = runTest {
        db.sampleDao().insert(sample(t0, 50, "AC"))
        history.deleteAll()
        assertNull(db.sampleDao().latest())
        assertTrue(history.allSessions().isEmpty())
    }
}
