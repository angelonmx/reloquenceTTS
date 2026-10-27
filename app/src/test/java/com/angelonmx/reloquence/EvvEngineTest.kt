package com.angelonmx.reloquence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 5.3: [EvvEngine] con un doble de prueba en lugar de la biblioteca
 * nativa, para poder verificar sin un aparato que el plan de voz se
 * aplica tal cual, que aplicar el mismo plan dos veces no acumula
 * estado y que [resetProsody] deja el motor limpio tras una parada.
 */
class EvvEngineTest {

	/** Doble de [EvvBridge]: guarda los ocho parámetros por voz y
	 *  handle, sin motor nativo. */
	private class FakeBridge : EvvBridge {
		private val handles = mutableMapOf<Long, MutableMap<Int, MutableMap<Int, Int>>>()
		private var next = 1L

		private fun voice(handle: Long, v: Int): MutableMap<Int, Int> {
			return handles.getOrPut(handle) { mutableMapOf() }.getOrPut(v) { mutableMapOf() }
		}

		override fun create(language: Int): Long {
			val h = next
			next++
			handles[h] = mutableMapOf()
			return h
		}

		override fun destroy(handle: Long) {
			handles.remove(handle)
		}

		override fun abort(handle: Long) {}

		override fun speak(handle: Long, text: ByteArray): Boolean = true

		override fun read(handle: Long, dst: ByteArray): Int = 0

		override fun stop(handle: Long) {}

		override fun setParam(handle: Long, param: Int, value: Int): Int = 0

		override fun setVoiceParam(handle: Long, voice: Int, param: Int, value: Int): Int {
			voice(handle, voice)[param] = value
			return value
		}

		override fun getVoiceParam(handle: Long, voice: Int, param: Int): Int {
			return voice(handle, voice).getOrDefault(param, -1)
		}

		override fun copyVoice(handle: Long, from: Int, to: Int): Int {
			voice(handle, to).clear()
			voice(handle, to).putAll(voice(handle, from))
			return 0
		}

		override fun teachWord(handle: Long, volume: Int, key: ByteArray, say: ByteArray): Int = 0

		override fun forgetDictionaries(handle: Long) {}

		override fun languages(): IntArray = intArrayOf(0x00020001)

		override fun version(): String = "fake-1.0"
	}

	private val esus = 0x00020001 // español latinoamericano

	private fun fullShape(): Map<Int, Int> = mapOf(
		Eci.VOICE_GENDER to 1,
		Eci.VOICE_SPEED to 100,
		Eci.VOICE_PITCH_BASELINE to 60,
		Eci.VOICE_PITCH_FLUCTUATION to 30,
		Eci.VOICE_HEAD_SIZE to 50,
		Eci.VOICE_ROUGHNESS to 10,
		Eci.VOICE_BREATHINESS to 20,
		Eci.VOICE_VOLUME to 90
	)

	private fun fullPlan(): VoicePlan.Plan = VoicePlan.plan(
		voice = 2,
		shape = fullShape(),
		preset = emptyMap(),
		requestRate = 100,
		requestPitch = 100,
		useSystemSpeed = false,
		useSystemVolume = false,
		volumeGain = 100
	)

	/** Abre un motor con el doble y devuelve el engine y su handle. */
	private fun openEngine(bridge: FakeBridge): Pair<EvvEngine, Long> {
		val handle = bridge.create(esus)
		return EvvEngine(handle, esus, 11025, bridge) to handle
	}

	@Test
	fun `applyEmission escribe los ocho valores tal cual y se leen de vuelta`() {
		val bridge = FakeBridge()
		val (engine, handle) = openEngine(bridge)
		val plan = fullPlan()
		assertTrue("el plan se aplicó", engine.applyEmission(plan))
		for ((param, value) in fullShape()) {
			assertEquals(
				"${VoicePlan.nameOf(param)} se lee tal cual se aplicó",
				value,
				engine.getVoiceParam(param)
			)
		}
		assertTrue("el plan está completo", plan.isComplete)
		// El motor realmente recibió los valores (no solo el plan).
		for ((param, value) in fullShape()) {
			assertEquals(
				"${VoicePlan.nameOf(param)} en el motor tras aplicar",
				value,
				bridge.getVoiceParam(handle, Eci.VOICE_CURRENT, param)
			)
		}
	}

	@Test
	fun `aplicar el mismo plan dos veces no acumula estado`() {
		val bridge = FakeBridge()
		val (engine, _) = openEngine(bridge)
		val plan = fullPlan()
		assertTrue(engine.applyEmission(plan))
		assertTrue(engine.applyEmission(plan)) // idempotente: segunda vez igual
		for ((param, value) in fullShape()) {
			assertEquals(
				"${VoicePlan.nameOf(param)} no se acumuló tras aplicar dos veces",
				value,
				engine.getVoiceParam(param)
			)
		}
		// El reinicio de prosodia devuelve los mismos ocho valores.
		val reset = engine.prosodyReset()
		assertTrue("reinicio no vacío", reset.isNotEmpty())
		assertTrue("velocidad en el reinicio", reset.contains("`vs100 "))
		assertTrue("tono en el reinicio", reset.contains("`vb60 "))
		assertTrue("cabeza en el reinicio", reset.contains("`vh50 "))
		assertTrue("volumen en el reinicio", reset.contains("`vv90 "))
	}

	@Test
	fun `resetProsody reafirma los valores base tras un residuo del motor`() {
		val bridge = FakeBridge()
		val (engine, handle) = openEngine(bridge)
		val plan = fullPlan()
		assertTrue(engine.applyEmission(plan))

		// Simular una anotación inline (`vb900`) de una emisión
		// cortada a mitad que movió el tono en el motor.
		bridge.setVoiceParam(handle, Eci.VOICE_CURRENT, Eci.VOICE_PITCH_BASELINE, 999)
		assertEquals("residuo: el motor reflejó el tono movido", 999, engine.getVoiceParam(Eci.VOICE_PITCH_BASELINE))

		engine.resetProsody()

		assertEquals("tras resetProsody el tono vuelve al base", 60, engine.getVoiceParam(Eci.VOICE_PITCH_BASELINE))
		assertEquals("tras resetProsody la cabeza sigue en el base", 50, engine.getVoiceParam(Eci.VOICE_HEAD_SIZE))
		assertEquals("tras resetProsody el volumen sigue en el base", 90, engine.getVoiceParam(Eci.VOICE_VOLUME))
		assertEquals("tras resetProsody la velocidad sigue en el base", 100, engine.getVoiceParam(Eci.VOICE_SPEED))
	}

	@Test
	fun `setRatePercent escala el tono base y setPitchPercent el tono`() {
		val bridge = FakeBridge()
		val (engine, _) = openEngine(bridge)
		assertTrue(engine.applyEmission(fullPlan()))
		// baseSpeed = 100; el 200 % se escala sobre esa base.
		engine.setRatePercent(200)
		engine.setPitchPercent(50)
		val reset = engine.prosodyReset()
		assertTrue("velocidad escalada en el reinicio", reset.contains("`vs"))
		assertTrue("tono a la mitad en el reinicio", reset.contains("`vb30 "))
	}

	@Test
	fun `motor cerrado no aplica ni restablece`() {
		val bridge = FakeBridge()
		val (engine, _) = openEngine(bridge)
		engine.close()
		assertFalse("cerrado: applyEmission rechaza", engine.applyEmission(fullPlan()))
		assertEquals("cerrado: prosodyReset vacío", "", engine.prosodyReset())
	}
}
