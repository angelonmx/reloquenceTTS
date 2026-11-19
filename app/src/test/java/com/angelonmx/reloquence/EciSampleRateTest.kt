package com.angelonmx.reloquence

import org.junit.Assert.assertEquals
import org.junit.Test

class EciSampleRateTest {

	@Test
	fun everySelectableRateRoundTripsThroughNativeIndex() {
		for (index in Eci.SAMPLE_RATES.indices) {
			val rate = Eci.sampleRateHz(index)
			assertEquals(rate, Eci.SAMPLE_RATES[index])
			assertEquals(index, Eci.sampleRateIndex(rate))
		}
	}

	@Test
	fun unsupportedRateFallsBackToWidebandDefault() {
		assertEquals(Eci.DEFAULT_SAMPLE_RATE, Eci.sampleRateHz(Eci.sampleRateIndex(12345)))
	}
}
