package ai.edgelm.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the scheduler's deterministic behaviour (arch doc Part 8): the
 * governor hard clamp and basic admission. Concurrent weighted-fair ORDERING is timing
 * dependent and is exercised on-device (see PHASE1-SCHEDULER.md), so it's intentionally
 * not asserted here to keep the suite non-flaky.
 *
 * Run: ./gradlew :runtime-service:testDebugUnitTest
 */
class AISchedulerTest {

    private fun state(thermal: DeviceGovernor.Thermal) =
        DeviceGovernor.State(thermal = thermal, onBattery = true, batteryLow = false, powerSaveMode = false)

    // ---- governor hard clamp --------------------------------------------------

    @Test fun `severe thermal blocks background admission`() {
        val sched = AIScheduler(governor = { state(DeviceGovernor.Thermal.SEVERE) })
        assertThrows(AIScheduler.DeferredException::class.java) {
            sched.withEngine(AIScheduler.Priority.BACKGROUND, appId = "bg") { _ -> Unit }
        }
        assertThrows(AIScheduler.DeferredException::class.java) {
            sched.withEngine(AIScheduler.Priority.BATCH, appId = "bg") { _ -> Unit }
        }
    }

    @Test fun `severe thermal never blocks foreground`() {
        val sched = AIScheduler(governor = { state(DeviceGovernor.Thermal.SEVERE) })
        val ran = sched.withEngine(AIScheduler.Priority.FOREGROUND, appId = "fg") { _ -> "ok" }
        assertEquals("ok", ran)
    }

    @Test fun `normal thermal admits background`() {
        val sched = AIScheduler(governor = { state(DeviceGovernor.Thermal.NORMAL) })
        val ran = sched.withEngine(AIScheduler.Priority.BACKGROUND, appId = "bg") { _ -> 42 }
        assertEquals(42, ran)
    }

    // ---- basic admission (no governor) ----------------------------------------

    @Test fun `withEngine runs the block and returns its value when free`() {
        val sched = AIScheduler()
        assertEquals("hello", sched.withEngine(AIScheduler.Priority.INTERACTIVE, appId = "a") { _ -> "hello" })
    }

    @Test fun `back-compat overload still works for internal callers`() {
        val sched = AIScheduler()
        var ran = false
        sched.withEngine(AIScheduler.Priority.FOREGROUND) { ran = true }
        assertTrue(ran)
    }

    @Test fun `preemption signal is not set for an uncontended job`() {
        val sched = AIScheduler()
        val yielded = sched.withEngine(AIScheduler.Priority.BATCH, appId = "a") { p -> p.shouldYield() }
        assertEquals(false, yielded)
    }
}
