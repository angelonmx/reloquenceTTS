package com.angelonmx.reloquence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 5.3: el plan de voz, que es lo que impide que una emisión salga con
 * `-1` o con lo que dejó la anterior.
 *
 * Estas pruebas no tocan Android ni el motor: comprueban los ocho
 * valores que [VoicePlan.plan] entrega y que lo elegido por el usuario
 * se aplica tal cual, sin escalarlo ni mezclarlo con el preset.
 */
class VoicePlanTest {

	private val emptyShape = emptyMap<Int, Int>()
	private val emptyPreset = emptyMap<Int, Int>()

	@Test
	fun `un plan sin nada elegido sale completo y sin negativos`() {
		val plan = VoicePlan.plan(voice = 3, shape = emptyShape, preset = emptyPreset)
		assertTrue("los ocho tienen que estar", plan.isComplete)
		for (param in Eci.SHAPE) {
			assertTrue(
				"${VoicePlan.nameOf(param)} no puede salir negativo",
				plan.value(param) >= 0
			)
		}
		assertEquals("la velocidad de sistema es la de fábrica", Eci.DEFAULT_SPEED, plan.value(Eci.VOICE_SPEED))
		assertEquals("el tono de último recurso", VoicePlan.DEFAULT_PITCH, plan.value(Eci.VOICE_PITCH_BASELINE))
		assertEquals("la fluctuación de sistema", VoicePlan.DEFAULT_FLUCTUATION, plan.value(Eci.VOICE_PITCH_FLUCTUATION))
		assertEquals("la cabeza de sistema", VoicePlan.DEFAULT_HEAD_SIZE, plan.value(Eci.VOICE_HEAD_SIZE))
		assertEquals("el volumen de último recurso", VoicePlan.DEFAULT_VOLUME, plan.value(Eci.VOICE_VOLUME))
		assertEquals("el género neutro", 0, plan.value(Eci.VOICE_GENDER))
		assertEquals("la rugosidad neutra", 0, plan.value(Eci.VOICE_ROUGHNESS))
		assertEquals("la respiración neutra", 0, plan.value(Eci.VOICE_BREATHINESS))
		assertEquals("la tasa neutra", 100, plan.ratePercent)
		assertEquals("el tono neutro", 100, plan.pitchPercent)
		assertFalse("nada vino del usuario", plan.pitchFromUser || plan.speedFromUser)
	}

	@Test
	fun `lo elegido por el usuario se aplica literal aunque el preset diga otra cosa`() {
		val shape = mapOf(
			Eci.VOICE_GENDER to 1,
			Eci.VOICE_HEAD_SIZE to 12,
			Eci.VOICE_PITCH_BASELINE to 77,
			Eci.VOICE_PITCH_FLUCTUATION to 8,
			Eci.VOICE_ROUGHNESS to 21,
			Eci.VOICE_BREATHINESS to 34,
			Eci.VOICE_SPEED to 140,
			Eci.VOICE_VOLUME to 63
		)
		val preset = mapOf(
			Eci.VOICE_PITCH_BASELINE to 20,
			Eci.VOICE_VOLUME to 30,
			Eci.VOICE_PITCH_FLUCTUATION to 90
		)
		val plan = VoicePlan.plan(
			voice = 1,
			shape = shape,
			preset = preset,
			requestRate = 150,
			requestPitch = 150,
			useSystemSpeed = false,
			useSystemVolume = false,
			volumeGain = 80
		)
		assertTrue(plan.isComplete)
		for ((param, value) in shape) {
			assertEquals(
				"${VoicePlan.nameOf(param)} tiene que salir tal cual",
				value,
				plan.value(param)
			)
		}
		assertEquals("el tono del usuario no se escala", 100, plan.pitchPercent)
		assertEquals("la tasa sí se escala por el sistema", 150, plan.ratePercent)
		assertEquals("la ganancia se respeta", 80, plan.volumeGain)
		assertTrue(plan.pitchFromUser)
		assertTrue(plan.speedFromUser)
	}

	@Test
	fun `el tono del preset si se escala por el sistema`() {
		val preset = mapOf(Eci.VOICE_PITCH_BASELINE to 60, Eci.VOICE_VOLUME to 40)
		val plan = VoicePlan.plan(
			voice = 2,
			shape = emptyShape,
			preset = preset,
			requestPitch = 150
		)
		assertEquals("el preset manda si el usuario no tocó el tono", 60, plan.value(Eci.VOICE_PITCH_BASELINE))
		assertEquals("y se escala como pide Android", 150, plan.pitchPercent)
		assertEquals("con volumen del sistema, el motor usa 100 no el preset", VoicePlan.NEUTRAL_PERCENT, plan.value(Eci.VOICE_VOLUME))
		assertFalse(plan.pitchFromUser)
	}

	@Test
	fun `la velocidad del sistema manda salvo que el usuario la haya fijado`() {
		val shape = mapOf(Eci.VOICE_SPEED to 140)
		val system = VoicePlan.plan(
			voice = 0,
			shape = shape,
			preset = emptyPreset,
			requestRate = 120,
			useSystemSpeed = true
		)
		assertEquals("con velocidad del sistema no manda el deslizador", Eci.DEFAULT_SPEED, system.value(Eci.VOICE_SPEED))
		assertEquals("pero el porcentaje del sistema sí", 120, system.ratePercent)
		assertFalse(system.speedFromUser)

		val own = VoicePlan.plan(
			voice = 0,
			shape = shape,
			preset = emptyPreset,
			requestRate = 120,
			useSystemSpeed = false
		)
		assertEquals("sin velocidad del sistema manda el usuario", 140, own.value(Eci.VOICE_SPEED))
		assertTrue(own.speedFromUser)
	}

	@Test
	fun `los porcentajes fuera de rango se sanean`() {
		assertEquals(100, VoicePlan.sanePercent(0))
		assertEquals(100, VoicePlan.sanePercent(-5))
		assertEquals(VoicePlan.PERCENT_CAP, VoicePlan.sanePercent(5000))
		assertEquals(175, VoicePlan.sanePercent(175))
	}

	@Test
	fun `los valores del plan no se salen de su rango`() {
		val shape = mapOf(
			Eci.VOICE_GENDER to 9,
			Eci.VOICE_HEAD_SIZE to 500,
			Eci.VOICE_PITCH_BASELINE to -40,
			Eci.VOICE_PITCH_FLUCTUATION to 900,
			Eci.VOICE_ROUGHNESS to 900,
			Eci.VOICE_BREATHINESS to 900,
			Eci.VOICE_SPEED to 900,
			Eci.VOICE_VOLUME to 900
		)
		val plan = VoicePlan.plan(voice = 0, shape = shape, preset = emptyPreset, useSystemSpeed = false)
		assertTrue(plan.isComplete)
		assertEquals(1, plan.value(Eci.VOICE_GENDER))
		assertEquals(100, plan.value(Eci.VOICE_HEAD_SIZE))
		assertEquals(0, plan.value(Eci.VOICE_PITCH_BASELINE))
		assertEquals(Eci.SPEED_MAX, plan.value(Eci.VOICE_SPEED))
		assertEquals(Eci.VOLUME_MAX, plan.value(Eci.VOICE_VOLUME))
	}

	@Test
	fun `el valor por defecto de la interfaz es el mismo que se aplica`() {
		val preset = mapOf(Eci.VOICE_PITCH_BASELINE to 44, Eci.VOICE_VOLUME to 22)
		val plan = VoicePlan.plan(voice = 0, shape = emptyShape, preset = preset)
		for (param in Eci.SHAPE) {
			assertEquals(
				"${VoicePlan.nameOf(param)}: la pantalla y el motor tienen que coincidir",
				plan.value(param),
				VoicePlan.defaultValue(param, preset)
			)
		}
	}

	@Test
	fun `el diagnostico lleva los ocho nombres y ningun negativo`() {
		val plan = VoicePlan.plan(
			voice = 4,
			shape = mapOf(Eci.VOICE_PITCH_BASELINE to 66),
			preset = emptyPreset,
			requestRate = 409,
			requestPitch = 120,
			useSystemSpeed = true,
			useSystemVolume = false
		)
		val pairs = plan.diagnosticPairs().toMap()
		assertEquals("4", pairs["voice"])
		for (param in Eci.SHAPE) {
			val key = VoicePlan.nameOf(param)
			assertTrue("falta $key", pairs.containsKey(key))
			assertTrue("$key no puede ser negativo", (pairs[key]?.toInt() ?: -1) >= 0)
		}
		assertEquals("lo pedido por el cliente", "409", pairs["rate_raw"])
		assertEquals("lo que se aplica", "409", pairs["rate_pct"])
		assertEquals("lo pedido por el cliente", "120", pairs["pitch_raw"])
		assertEquals("el tono del usuario manda literal", "100", pairs["pitch_pct"])
		assertTrue(pairs.containsKey("volume_gain"))
		assertEquals("la base configurada", "50", pairs["speed_cfg"])
		assertEquals(
			"la velocidad que de verdad va al motor",
			SpeechRate.speedForPercent(50, 409).toString(),
			pairs["speed_eff"]
		)
		assertEquals("true", pairs["use_system_speed"])
		assertEquals("false", pairs["use_system_volume"])
		assertTrue(pairs.containsKey("pitch_user"))
		assertTrue(pairs.containsKey("speed_user"))
		assertFalse("el plan no puede llevar texto del usuario", pairs.keys.any { it.contains("text") })
	}

	@Test
	fun `la velocidad del cliente se respeta hasta el tope de la app`() {
		val at409 = VoicePlan.plan(
			voice = 0, shape = emptyShape, preset = emptyPreset, requestRate = 409
		)
		assertEquals("lo que pide el cliente se conserva", 409, at409.ratePercent)
		assertEquals("y se apunta como pedido", 409, at409.rawRatePercent)
		assertEquals(
			"la velocidad efectiva es la del cliente",
			SpeechRate.speedForPercent(Eci.DEFAULT_SPEED, 409),
			at409.effectiveSpeed()
		)
		val huge = VoicePlan.plan(
			voice = 0, shape = emptyShape, preset = emptyPreset, requestRate = 5000
		)
		assertEquals(
			"por encima del tope de la app se recorta",
			VoicePlan.MAX_RATE_PERCENT,
			huge.ratePercent
		)
		assertEquals("lo pedido se sigue viendo", 5000, huge.rawRatePercent)
		val slow = VoicePlan.plan(
			voice = 0, shape = emptyShape, preset = emptyPreset, requestRate = 0
		)
		assertEquals("cero no es silencio: es neutro", 100, slow.ratePercent)
	}
}
