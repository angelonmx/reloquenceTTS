package com.angelonmx.reloquence

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsPacerTest {

	@Test
	fun `the clock starts with the first handed bytes`() {
		var now = 0L
		val pacer = TtsPacer(1_000) { now }
		assertEquals(0L, pacer.ahead())
		pacer.handed(500)
		assertEquals(500L, pacer.ahead())
		now = 100_000_000L
		assertEquals(400L, pacer.ahead())
	}

	@Test
	fun `handed time is bytes over rate`() {
		val pacer = TtsPacer(2_000) { 0L }
		pacer.handed(500)
		pacer.handed(500)
		assertEquals(500L, pacer.handedMs())
	}

	@Test
	fun `reset starts the clock again on the next hand`() {
		var now = 0L
		val pacer = TtsPacer(1_000) { now }
		pacer.handed(1_000)
		now = 2_000_000_000L
		assertEquals(-1_000L, pacer.ahead())
		pacer.reset()
		assertEquals(0L, pacer.ahead())
		now = 3_000_000_000L
		pacer.handed(250)
		assertEquals(250L, pacer.ahead())
	}

	@Test
	fun `a zero rate never reports debt`() {
		val pacer = TtsPacer(0)
		pacer.handed(1_000)
		assertEquals(0L, pacer.handedMs())
		assertEquals(0L, pacer.ahead())
	}

	@Test
	fun `empty and negative handoffs are ignored`() {
		val pacer = TtsPacer(1_000) { 0L }
		pacer.handed(0)
		pacer.handed(-4)
		assertEquals(0L, pacer.handedMs())
		assertEquals(0L, pacer.ahead())
	}
}
