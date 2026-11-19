package com.angelonmx.reloquence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PausesTest {

	@Test
	fun punctuationUsesIncreasingNaturalPauseLengths() {
		val result = Pauses.apply("One, two; three. Four?", Pauses.ALL, false, punctMs = 140)

		assertTrue(result.contains("`p140,"))
		assertTrue(result.contains("`p210;"))
		assertTrue(result.contains("`p350."))
		assertTrue(result.contains("`p350?"))
	}

	@Test
	fun punctuationPausesLeaveNumbersAndRepeatedMarksIntact() {
		val result = Pauses.apply("3.14 at 2:30; wait...", Pauses.ALL, false, punctMs = 140)

		assertEquals("3.14 at 2:30; wait...", result.replace(Regex(" `p\\d+"), ""))
		assertTrue(result.contains("`p350..."))
	}
}
