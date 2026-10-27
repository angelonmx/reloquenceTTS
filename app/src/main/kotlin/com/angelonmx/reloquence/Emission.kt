package com.angelonmx.reloquence

/**
 * El único camino por el que una emisión llega al motor.
 *
 * ## El orden, que es lo que importa
 *
 * Antes cada llamante componía su propia secuencia: uno aplicaba la
 * voz una vez por emisión, otro solo la primera vez, otro ponía el
 * reinicio de prosodia únicamente en el primer trozo. Así, después de
 * una parada o de un reinicio del servicio, un trozo podía salir con
 * la voz que hubiera dejado el anterior, o con los parámetros a `-1`.
 *
 * Aquí el orden es uno solo y no depende del llamante:
 *
 *  1. [Target.applyEmission] escribe los ocho parámetros explícitos del
 *     plan (nunca `-1`), con reintentos si el motor está ocupado.
 *  2. Se ponen la velocidad, el tono y la ganancia de la emisión.
 *  3. [Target.prosodyReset] devuelve los códigos de los ocho parámetros
 *     ya aplicados. Si vuelve vacío, la emisión **no se habla**.
 *  4. [build] recibe ese reinicio y arma el texto (diccionario, texto
 *     limpio, pausas...).
 *  5. [Target.speak] encola el texto.
 *
 * La interfaz [Target] permite probar ese orden con un doble de prueba
 * sin motor nativo ni Android.
 */
object Emission {

	/** Lo que una emisión necesita del motor. [EvvEngine] lo cumple. */
	interface Target {
		/** Escribe los ocho parámetros del plan en la voz actual.
		 *  Contesta falso si el motor no los aceptó. */
		fun applyEmission(plan: VoicePlan.Plan): Boolean

		fun setRatePercent(percent: Int)

		fun setPitchPercent(percent: Int)

		fun setVolumePercent(percent: Int)

		/** Los códigos que devuelven la voz a lo aplicado. Vacío
		 *  significa que no hay estado válido y no se debe hablar. */
		fun prosodyReset(): String

		fun speak(text: String): Boolean
	}

	/** Cómo terminó una emisión. */
	sealed class Result {
		/** El motor aceptó el texto y las muestras saldrán por [read]. */
		object Spoken : Result()

		/** No se pudo aplicar el plan tras los reintentos. No se habló
		 *  nada: la emisión se pospone o falla, pero nunca sale con la
		 *  voz equivocada. */
		object NotApplied : Result()

		/** El motor rechazó el texto ya aplicado. */
		object Rejected : Result()

		/** No había nada que decir (texto vacío o solo anotaciones). */
		object Empty : Result()
	}

	/**
	 * Habla [body] con [plan].
	 *
	 * [retries] es cuántas veces se intenta aplicar el plan; si el
	 * motor está ocupado, [pause] espera entre intentos. Al agotarse,
	 * contesta [Result.NotApplied] y no se llama a [Target.speak].
	 *
	 * [build] recibe el reinicio de prosodia ya calculado. Debe
	 * devolver el texto completo, con ese reinicio por delante, o una
	 * cadena vacía para no hablar nada.
	 *
	 * [onReset] recibe el reinicio que de verdad se va a mandar. Es
	 * el enganche que usa el servicio para dejar en el diagnóstico la
	 * prueba de que [Target.prosodyReset] corrió antes de cada
	 * `speak`, sin meter Android ni preferencias aquí dentro.
	 */
	fun speak(
		target: Target,
		plan: VoicePlan.Plan,
		body: String,
		retries: Int = 4,
		pause: (Long) -> Unit = { Thread.sleep(it) },
		onReset: (String) -> Unit = {},
		build: (String) -> String
	): Result {
		if (body.isBlank()) return Result.Empty
		if (!plan.isComplete) return Result.NotApplied
		var attempts = 0
		while (true) {
			if (target.applyEmission(plan)) break
			attempts++
			if (attempts >= retries) return Result.NotApplied
			pause(50L * attempts)
		}
		target.setRatePercent(plan.ratePercent)
		target.setPitchPercent(plan.pitchPercent)
		target.setVolumePercent(plan.volumeGain)
		val reset = target.prosodyReset()
		if (reset.isBlank()) return Result.NotApplied
		onReset(reset)
		val text = build(reset)
		if (text.isBlank()) return Result.Empty
		return if (target.speak(text)) Result.Spoken else Result.Rejected
	}
}
