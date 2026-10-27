package com.angelonmx.reloquence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsHealthTest {

	@Test
	fun `counters advance one step at a time`() {
		val before = TtsHealth.snapshot()
		TtsHealth.utterance()
		TtsHealth.completed()
		TtsHealth.cancelled()
		TtsHealth.recovered()
		TtsHealth.watchdog()
		val after = TtsHealth.snapshot()
		assertEquals(before.utterances + 1, after.utterances)
		assertEquals(before.completed + 1, after.completed)
		assertEquals(before.cancelled + 1, after.cancelled)
		assertEquals(before.recoveries + 1, after.recoveries)
		assertEquals(before.watchdogTrips + 1, after.watchdogTrips)
	}

	@Test
	fun `a failure keeps only a short diagnostic`() {
		val before = TtsHealth.snapshot()
		TtsHealth.failed("x".repeat(500))
		val after = TtsHealth.snapshot()
		assertEquals(before.failures + 1, after.failures)
		assertEquals(160, after.lastError.length)
	}

	@Test
	fun `latency accepts only the useful range`() {
		TtsHealth.latency(1_234)
		assertEquals(1_234L, TtsHealth.snapshot().lastLatencyMs)
		TtsHealth.latency(-1)
		assertEquals(1_234L, TtsHealth.snapshot().lastLatencyMs)
		TtsHealth.latency(600_001)
		assertEquals(1_234L, TtsHealth.snapshot().lastLatencyMs)
	}

	@Test
	fun `speaking remembers the language of the active utterance`() {
		TtsHealth.speaking(true, 0x040A)
		val active = TtsHealth.snapshot()
		assertTrue(active.speaking)
		assertEquals(0x040A, active.activeLanguage)
		TtsHealth.speaking(false)
		val idle = TtsHealth.snapshot()
		assertFalse(idle.speaking)
		assertEquals(0x040A, idle.activeLanguage)
	}

	@Test
	fun `service readiness and engine version are visible`() {
		TtsHealth.service(true)
		TtsHealth.engineVersion("test-1")
		val state = TtsHealth.snapshot()
		assertTrue(state.ready)
		assertEquals("test-1", state.engineVersion)
	}
}
