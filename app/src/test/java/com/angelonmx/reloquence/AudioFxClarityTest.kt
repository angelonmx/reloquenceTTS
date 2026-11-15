package com.angelonmx.reloquence

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class AudioFxClarityTest {

	@Test
	fun hdImprovesSpeechBandBalanceWithoutDependingOnOutputRate() {
		for (rate in intArrayOf(16000, 22050, 32000, 44100, 48000)) {
			val untreatedLow = toneAmplitude(rate, 250, hd = false)
			val untreatedSpeech = toneAmplitude(rate, 3000, hd = false)
			val clearLow = toneAmplitude(rate, 250, hd = true)
			val clearSpeech = toneAmplitude(rate, 3000, hd = true)

			val untreatedRatio = untreatedSpeech / untreatedLow
			val clearRatio = clearSpeech / clearLow
			assertTrue(
				"$rate Hz should make the consonant band clearer: $clearRatio <= $untreatedRatio",
				clearRatio > untreatedRatio * 1.2
			)
		}
	}

	private fun toneAmplitude(rate: Int, frequency: Int, hd: Boolean): Double {
		val frames = rate
		val bytes = ByteArray(frames * 2)
		for (frame in 0 until frames) {
			val sample = (8000.0 * sin(2.0 * PI * frequency * frame / rate)).toInt()
			bytes[frame * 2] = (sample and 0xFF).toByte()
			bytes[frame * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
		}

		AudioFx.Chain(
			rateHz = rate,
			eqDb = null,
			reverbMix = 0,
			body = false,
			hdEnhance = hd
		).process(bytes, bytes.size)

		val start = frames / 2
		val count = frames - start
		var inPhase = 0.0
		var quadrature = 0.0
		for (frame in start until frames) {
			val offset = frame * 2
			val sample = ((bytes[offset].toInt() and 0xFF) or
				(bytes[offset + 1].toInt() shl 8)).toShort().toInt()
			val phase = 2.0 * PI * frequency * frame / rate
			inPhase += sample * sin(phase)
			quadrature += sample * cos(phase)
		}
		return 2.0 * sqrt(inPhase * inPhase + quadrature * quadrature) / count
	}
}
