package com.angelonmx.reloquence

/**
 * El plan de una emisión: los ocho parámetros de voz **completos**,
 * explícitos y nunca negativos, más los porcentajes que Android pide.
 *
 * ## Por qué existe
 *
 * Antes, una emisión solo llevaba escritos los parámetros que el
 * usuario había tocado; el resto quedaba como lo tuviera el preset o,
 * peor, como lo hubiera dejado la emisión anterior. Eso hacía que la
 * voz cambiara al reiniciarse el servicio, al cortarse una frase o al
 * cambiar de motor, y que `prosodyReset()` pudiera devolver una cadena
 * vacía o con `-1`.
 *
 * Aquí no hay huecos. Cada uno de los ocho sale con un valor:
 *
 *  - lo que el usuario eligió, tal cual, sin escalarlo ni mezclarlo;
 *  - y si no eligió ese parámetro, el valor del preset de la voz, que
 *    es lo que conserva su identidad (género, cabeza, fluctuación,
 *    rugosidad, respiración, tono y volumen). Así una voz femenina
 *    sigue siendo femenina aunque el usuario no haya tocado nada.
 *
 * La velocidad y el volumen tienen además su propia política: la
 * velocidad sale de [useSystemSpeed] y el volumen de [useSystemVolume]
 * (neutro cuando manda el sistema), como se explica en [plan].
 *
 * Todo esto es Kotlin puro para poder probarlo sin Android ni el motor
 * nativo: las pruebas construyen el plan y comprueban los ocho valores.
 */
object VoicePlan {

	/** Fluctuación de tono cuando el usuario no eligió una. */
	const val DEFAULT_FLUCTUATION = 30

	/** Tamaño de cabeza cuando el usuario no eligió uno. */
	const val DEFAULT_HEAD_SIZE = 50

	/** Tono base de último recurso si el preset no lo dice. */
	const val DEFAULT_PITCH = 50

	/** Volumen de último recurso si el preset no lo dice. */
	const val DEFAULT_VOLUME = 100

	/** Un porcentaje neutro: 100 deja la voz como está. */
	const val NEUTRAL_PERCENT = 100

	/** El tope de un porcentaje de Android, muy por encima del
	 *  deslizador del sistema. */
	const val PERCENT_CAP = 1000

	/**
	 * 5.4: el tope de la velocidad que pide el cliente.
	 *
	 * No es un número nuevo: es el mismo 500 que ya permite el
	 * deslizador propio ([SettingsModel.speedPercent] y
	 * [SettingsModel.setSpeedPercent]). Por encima de eso no hay
	 * nada que ganar y sí mucho que perder: el motor se satura y la
	 * frase deja de entenderse. El cliente sigue mandando por debajo
	 * del tope, que es lo que pide TalkBack; solo se recorta lo que
	 * la propia aplicación no deja elegir ni con su deslizador.
	 */
	const val MAX_RATE_PERCENT = 500

	/**
	 * Un plan completo. [params] siempre tiene los ocho parámetros de
	 * [Eci.SHAPE] con valores `>= 0`; [isComplete] lo comprueba.
	 *
	 * Los campos de después de [speedFromUser] son para el
	 * diagnóstico: qué pidió el cliente, qué se aplicó de verdad y
	 * qué mandaba cada interruptor. No cambian lo que suena.
	 */
	data class Plan(
		val voice: Int,
		val params: Map<Int, Int>,
		val ratePercent: Int,
		val pitchPercent: Int,
		val volumeGain: Int,
		val pitchFromUser: Boolean,
		val speedFromUser: Boolean,
		val rawRatePercent: Int = ratePercent,
		val rawPitchPercent: Int = pitchPercent,
		val useSystemSpeed: Boolean = true,
		val useSystemVolume: Boolean = true,
		val configuredSpeed: Int = Eci.DEFAULT_SPEED
	) {

		/** El valor explícito de [param], o -1 si el plan está roto. */
		fun value(param: Int): Int = params[param] ?: -1

		/** Si los ocho están y ninguno es negativo. Una emisión con un
		 *  plan incompleto no se habla: es la garantía de que nunca
		 *  sale un `-1` hacia el motor. */
		val isComplete: Boolean
			get() = Eci.SHAPE.all { value(it) >= 0 }

		/** Los pares que van al registro de diagnóstico. No llevan
		 *  texto del usuario: solo números de voz y prosodia. */
		fun diagnosticPairs(): List<Pair<String, String>> {
			val out = ArrayList<Pair<String, String>>(Eci.NUM_VOICE_PARAMS + 14)
			out.add("voice" to voice.toString())
			for (param in Eci.SHAPE) {
				out.add(nameOf(param) to value(param).toString())
			}
			// 5.4: lo pedido y lo aplicado, cada uno en su clave. Antes
			// solo salía `rate_pct`, y un `409` del cliente parecía un
			// valor elegido por la app; ahora se ve que es el cliente.
			out.add("rate_raw" to rawRatePercent.toString())
			out.add("rate_pct" to ratePercent.toString())
			out.add("pitch_raw" to rawPitchPercent.toString())
			out.add("pitch_pct" to pitchPercent.toString())
			out.add("volume_gain" to volumeGain.toString())
			out.add("speed_cfg" to configuredSpeed.toString())
			out.add("speed_eff" to effectiveSpeed().toString())
			out.add("pitch_eff" to effectivePitch().toString())
			out.add("use_system_speed" to useSystemSpeed.toString())
			out.add("use_system_volume" to useSystemVolume.toString())
			out.add("pitch_user" to pitchFromUser.toString())
			out.add("speed_user" to speedFromUser.toString())
			return out
		}

		/** La velocidad que de verdad va al motor después de aplicar
		 *  el porcentaje del cliente sobre la base del plan. */
		fun effectiveSpeed(): Int =
			SpeechRate.speedForPercent(value(Eci.VOICE_SPEED), ratePercent)

		/** El tono que de verdad va al motor, con el mismo tope que
		 *  aplica [EvvEngine.setPitchPercent]. */
		fun effectivePitch(): Int =
			(value(Eci.VOICE_PITCH_BASELINE).toLong() * pitchPercent / 100)
				.toInt()
				.coerceIn(0, Eci.PERCENT_MAX)
	}

	/**
	 * Arma el plan de [voice].
	 *
	 * [shape] es lo que el usuario eligió (ver [Settings.shape]); puede
	 * traer solo algunos parámetros. [preset] es lo que el preset de esa
	 * voz trae de fábrica, y se usa para cualquier parámetro que el
	 * usuario no haya elegido, salvo la velocidad y el volumen, que
	 * tienen la política de [useSystemSpeed] y [useSystemVolume].
	 *
	 * [requestRate] y [requestPitch] son los porcentajes de Android y se
	 * aplican sobre la base de voz elegida o heredada del preset. La
	 * velocidad también se escala por el porcentaje del sistema, que es
	 * lo que respeta el ritmo que pide el lector de pantalla;
	 * [useSystemSpeed] decide si la base es la del sistema (50) o la que
	 * eligió el usuario.
	 */
	fun plan(
		voice: Int,
		shape: Map<Int, Int>,
		preset: Map<Int, Int>,
		requestRate: Int = NEUTRAL_PERCENT,
		requestPitch: Int = NEUTRAL_PERCENT,
		useSystemSpeed: Boolean = true,
		volumeGain: Int = NEUTRAL_PERCENT,
		useSystemVolume: Boolean = true
	): Plan {
		val pitchFromUser = shape.containsKey(Eci.VOICE_PITCH_BASELINE)
		val speedFromUser = !useSystemSpeed && shape.containsKey(Eci.VOICE_SPEED)
		// La base configurada, antes de escalarla por lo que pida el
		// cliente. [Settings.shape] siempre trae la velocidad, así que
		// esto es lo que el usuario dejó en el deslizador aunque el
		// interruptor del sistema esté encendido.
		val configuredSpeed = Eci.clampVoice(
			Eci.VOICE_SPEED,
			shape[Eci.VOICE_SPEED] ?: Eci.DEFAULT_SPEED
		)
		val params = LinkedHashMap<Int, Int>(Eci.NUM_VOICE_PARAMS)
		for (param in Eci.SHAPE) {
			val value = when (param) {
				Eci.VOICE_GENDER ->
					shape[param] ?: preset[param] ?: 0
				Eci.VOICE_HEAD_SIZE ->
					shape[param] ?: preset[param] ?: DEFAULT_HEAD_SIZE
				Eci.VOICE_PITCH_BASELINE ->
					shape[param] ?: preset[param] ?: DEFAULT_PITCH
				Eci.VOICE_PITCH_FLUCTUATION ->
					shape[param] ?: preset[param] ?: DEFAULT_FLUCTUATION
				Eci.VOICE_ROUGHNESS ->
					shape[param] ?: preset[param] ?: 0
				Eci.VOICE_BREATHINESS ->
					shape[param] ?: preset[param] ?: 0
				Eci.VOICE_SPEED ->
					if (useSystemSpeed) Eci.DEFAULT_SPEED
					else shape[param] ?: Eci.DEFAULT_SPEED
				Eci.VOICE_VOLUME ->
					if (useSystemVolume) NEUTRAL_PERCENT
					else shape[param] ?: preset[param] ?: DEFAULT_VOLUME
				else -> shape[param] ?: 0
			}
			params[param] = Eci.clampVoice(param, value)
		}
		return Plan(
			voice = voice,
			params = params,
			// El cliente manda hasta el tope que ya tiene la propia
			// aplicación; por encima se recorta a ese tope.
			ratePercent = sanePercent(requestRate).coerceAtMost(MAX_RATE_PERCENT),
			// Android siempre aporta su porcentaje de tono, incluso si
			// hay una base elegida en la app.
			pitchPercent = sanePercent(requestPitch),
			volumeGain = volumeGain.coerceIn(0, 500),
			pitchFromUser = pitchFromUser,
			speedFromUser = speedFromUser,
			rawRatePercent = requestRate,
			rawPitchPercent = requestPitch,
			useSystemSpeed = useSystemSpeed,
			useSystemVolume = useSystemVolume,
			configuredSpeed = configuredSpeed
		)
	}

	/**
	 * El valor que la interfaz debe enseñar cuando el usuario no ha
	 * elegido uno. Es el mismo que aplica [plan], para que el
	 * deslizador no diga una cosa y el motor haga otra. Cuando
	 * [useSystemVolume] está activo, el volumen del motor es
	 * [NEUTRAL_PERCENT] y no el del preset.
	 */
	fun defaultValue(
		param: Int,
		preset: Map<Int, Int> = emptyMap(),
		useSystemVolume: Boolean = true
	): Int = when (param) {
		Eci.VOICE_GENDER -> preset[param] ?: 0
		Eci.VOICE_HEAD_SIZE -> preset[param] ?: DEFAULT_HEAD_SIZE
		Eci.VOICE_PITCH_BASELINE -> preset[param] ?: DEFAULT_PITCH
		Eci.VOICE_PITCH_FLUCTUATION -> preset[param] ?: DEFAULT_FLUCTUATION
		Eci.VOICE_ROUGHNESS -> preset[param] ?: 0
		Eci.VOICE_BREATHINESS -> preset[param] ?: 0
		Eci.VOICE_SPEED -> Eci.DEFAULT_SPEED
		Eci.VOICE_VOLUME ->
			if (useSystemVolume) NEUTRAL_PERCENT else preset[param] ?: DEFAULT_VOLUME
		else -> 0
	}

	/** El nombre corto del parámetro, para el registro. */
	fun nameOf(param: Int): String = when (param) {
		Eci.VOICE_GENDER -> "gender"
		Eci.VOICE_HEAD_SIZE -> "head"
		Eci.VOICE_PITCH_BASELINE -> "pitch"
		Eci.VOICE_PITCH_FLUCTUATION -> "fluct"
		Eci.VOICE_ROUGHNESS -> "rough"
		Eci.VOICE_BREATHINESS -> "breath"
		Eci.VOICE_SPEED -> "speed"
		Eci.VOICE_VOLUME -> "volume"
		else -> "p$param"
	}

	/** Lo que de un porcentaje de Android se acepta: lo que no se
	 *  dice, cien, y un tope muy por encima de donde llega el
	 *  deslizador del sistema. */
	fun sanePercent(percent: Int): Int = when {
		percent <= 0 -> NEUTRAL_PERCENT
		percent > PERCENT_CAP -> PERCENT_CAP
		else -> percent
	}
}
