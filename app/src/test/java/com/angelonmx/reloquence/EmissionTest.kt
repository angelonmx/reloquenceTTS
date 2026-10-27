package com.angelonmx.reloquence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 5.3: el orden de una emisión.
 *
 * El fallo que se estaba viendo era que la voz cambiaba tras parar y
 * volver a arrancar el servicio. La causa no era el motor: era que
 * cada llamante aplicaba la voz cuando le parecía. Estas pruebas fijan
 * el único orden válido y comprueban que, si el plan no se puede
 * aplicar, **no se habla** en vez de hablar con `-1`.
 */
class EmissionTest {

	private class FakeTarget(
		private val applyResults: MutableList<Boolean> = mutableListOf(true),
		var reset: String = "`vs50 `vb50 ",
		private val speakResult: Boolean = true
	) : Emission.Target {

		val calls = ArrayList<String>()
		var applied: VoicePlan.Plan? = null

		override fun applyEmission(plan: VoicePlan.Plan): Boolean {
			calls.add("apply")
			applied = plan
			return if (applyResults.isEmpty()) true else applyResults.removeAt(0)
		}

		override fun setRatePercent(percent: Int) {
			calls.add("rate=$percent")
		}

		override fun setPitchPercent(percent: Int) {
			calls.add("pitch=$percent")
		}

		override fun setVolumePercent(percent: Int) {
			calls.add("volume=$percent")
		}

		override fun prosodyReset(): String {
			calls.add("reset")
			return reset
		}

		override fun speak(text: String): Boolean {
			calls.add("speak=$text")
			return speakResult
		}
	}

	private fun plan(shape: Map<Int, Int> = emptyMap()): VoicePlan.Plan =
		VoicePlan.plan(
			voice = 2,
			shape = shape,
			preset = emptyMap(),
			requestRate = 100,
			requestPitch = 100,
			volumeGain = 100
		)

	@Test
	fun `aplicar, escalar, reiniciar y solo entonces hablar`() {
		val target = FakeTarget()
		var resetSeen = ""
		val result = Emission.speak(
			target,
			plan(),
			"hola",
			pause = {},
			onReset = { resetSeen = it }
		) { reset -> reset + "hola" }

		assertEquals(Emission.Result.Spoken, result)
		assertEquals(
			listOf("apply", "rate=100", "pitch=100", "volume=100", "reset", "speak=`vs50 `vb50 hola"),
			target.calls
		)
		assertEquals("el reinicio se anuncia antes de hablar", "`vs50 `vb50 ", resetSeen)
		assertNotNull("el plan llegó al motor", target.applied)
	}

	@Test
	fun `el reinicio de prosodia va antes de cada speak`() {
		val target = FakeTarget()
		val plan = plan()
		val bodies = listOf("uno", "dos", "tres")
		for (body in bodies) {
			Emission.speak(target, plan, body, pause = {}) { reset -> reset + body }
		}
		val speaks = target.calls.count { it.startsWith("speak=") }
		val resets = target.calls.count { it == "reset" }
		assertEquals("una emisión por trozo", bodies.size, speaks)
		assertEquals("un reinicio por trozo", bodies.size, resets)
		// Y siempre en el orden reset -> speak.
		for (at in target.calls.indices) {
			if (target.calls[at] == "reset") {
				assertTrue(
					"detrás de un reset tiene que venir su speak",
					target.calls.getOrNull(at + 1)?.startsWith("speak=") == true
				)
			}
		}
	}

	@Test
	fun `si el motor esta ocupado se reintenta antes de rendirse`() {
		val target = FakeTarget(applyResults = mutableListOf(false, false, true))
		val result = Emission.speak(target, plan(), "hola", retries = 4, pause = {}) { reset ->
			reset + "hola"
		}
		assertEquals(Emission.Result.Spoken, result)
		assertEquals("tres intentos", 3, target.calls.count { it == "apply" })
	}

	@Test
	fun `agotar los reintentos no habla nada`() {
		val target = FakeTarget(applyResults = mutableListOf(false, false, false, false, false))
		val result = Emission.speak(target, plan(), "hola", retries = 4, pause = {}) { reset ->
			reset + "hola"
		}
		assertEquals(Emission.Result.NotApplied, result)
		assertFalse("no puede haber reset", target.calls.contains("reset"))
		assertFalse("ni speak", target.calls.any { it.startsWith("speak=") })
		assertFalse("ni escalado", target.calls.any { it.startsWith("rate=") })
	}

	@Test
	fun `un reinicio vacio no habla`() {
		val target = FakeTarget(reset = "")
		val result = Emission.speak(target, plan(), "hola", pause = {}) { reset -> reset + "hola" }
		assertEquals(Emission.Result.NotApplied, result)
		assertFalse(target.calls.any { it.startsWith("speak=") })
	}

	@Test
	fun `un plan incompleto no llega al motor`() {
		val target = FakeTarget()
		val broken = VoicePlan.Plan(
			voice = 1,
			params = mapOf(Eci.VOICE_SPEED to 50),
			ratePercent = 100,
			pitchPercent = 100,
			volumeGain = 100,
			pitchFromUser = false,
			speedFromUser = false
		)
		val result = Emission.speak(target, broken, "hola", pause = {}) { reset -> reset + "hola" }
		assertEquals(Emission.Result.NotApplied, result)
		assertTrue("no se intenta aplicar", target.calls.isEmpty())
	}

	@Test
	fun `un texto vacio no habla`() {
		val target = FakeTarget()
		val result = Emission.speak(target, plan(), "   ", pause = {}) { reset -> reset + "x" }
		assertEquals(Emission.Result.Empty, result)
		assertTrue(target.calls.isEmpty())
	}

	@Test
	fun `si el motor rechaza el texto ya aplicado se informa`() {
		val target = FakeTarget(speakResult = false)
		val result = Emission.speak(target, plan(), "hola", pause = {}) { reset -> reset + "hola" }
		assertEquals(Emission.Result.Rejected, result)
	}

	@Test
	fun `si el armado deja el texto vacio no se habla`() {
		val target = FakeTarget()
		val result = Emission.speak(target, plan(), "hola", pause = {}) { "" }
		assertEquals(Emission.Result.Empty, result)
		assertFalse(target.calls.any { it.startsWith("speak=") })
	}

	@Test
	fun `el plan que llega al motor es el mismo que se escala`() {
		val shape = mapOf(
			Eci.VOICE_PITCH_BASELINE to 71,
			Eci.VOICE_PITCH_FLUCTUATION to 9,
			Eci.VOICE_HEAD_SIZE to 33
		)
		val target = FakeTarget()
		Emission.speak(target, plan(shape), "hola", pause = {}) { reset -> reset + "hola" }
		val applied = target.applied!!
		assertEquals(71, applied.value(Eci.VOICE_PITCH_BASELINE))
		assertEquals(9, applied.value(Eci.VOICE_PITCH_FLUCTUATION))
		assertEquals(33, applied.value(Eci.VOICE_HEAD_SIZE))
		assertTrue(applied.isComplete)
	}

	@Test
	fun `la tasa que llega al motor es la del plan ya recortada`() {
		val target = FakeTarget()
		val plan = VoicePlan.plan(
			voice = 0,
			shape = emptyMap(),
			preset = emptyMap(),
			requestRate = 5000
		)
		Emission.speak(target, plan, "hola", pause = {}) { reset -> reset + "hola" }
		assertEquals(
			"rate=${VoicePlan.MAX_RATE_PERCENT}",
			target.calls.first { it.startsWith("rate=") }
		)
	}
}
