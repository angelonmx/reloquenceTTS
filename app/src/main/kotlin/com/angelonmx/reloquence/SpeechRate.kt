package com.angelonmx.reloquence

/**
 * La velocidad de habla de Android, convertida en uno de los
 * números de velocidad del motor.
 *
 * Android significa un multiplicador: 100 es la voz tal como está,
 * 200 es el doble de rápido, y el deslizador del sistema va mucho más
 * allá que eso. La velocidad del motor es de 0 a 250 y no es nada
 * lineal en lo rápido que habla de verdad. Medido en una frase, con
 * todo lo demás de la voz quieto:
 *
 *     velocidad  50   100   150    200    250
 *     veces     1.0   2.7   7.0   16.6   21.2
 *
 * Así que el porcentaje no puede simplemente multiplicar el número
 * de velocidad. Eso está equivocado por mucho arriba: convierte una
 * velocidad de 400, cuatro veces, en velocidad 200, que es dieciséis
 * veces y mucho demasiado rápido para seguir. Un reloj sale con la
 * velocidad subida, así que es ahí donde pica primero.
 *
 * [TIMES] es esa medición. Leerla hacia delante dice cuántas veces
 * la velocidad-50 habla una velocidad dada; leerla al revés convierte
 * un multiplicador querido de vuelta en un número de velocidad. Ambas
 * interpolan entre los puntos.
 *
 * La curva se aplana cerca del tope, así que las tasas muy grandes
 * se saturan en lugar de correr fuera de control. Eso es el techo
 * propio del motor y no un límite impuesto aquí.
 */
object SpeechRate {

	/** Números de velocidad, y cuántas veces la tasa de velocidad-50
	 *  habla cada uno. Medido con una frase a 11,025 Hz; los números
	 *  salieron idénticos para dos voces con valores distintos, así que
	 *  la velocidad es lo único que decide la duración y una tabla sirve
	 *  para cada voz. */
	private val SPEED = intArrayOf(0, 5, 10, 20, 30, 40, 50, 60, 70, 85, 100, 125, 150, 175, 200, 225, 250)

	private val TIMES = doubleArrayOf(
		0.400, 0.416, 0.450, 0.552, 0.675, 0.823, 1.000, 1.226, 1.504,
		1.976, 2.740, 4.383, 6.986, 11.801, 16.633, 20.427, 21.170
	)

	/** Cuántas veces la tasa de velocidad-50 habla [speed]. */
	fun timesFor(speed: Int): Double {
		val want = speed.coerceIn(SPEED.first(), SPEED.last())
		for (i in 1 until SPEED.size) {
			if (want > SPEED[i]) continue
			val span = (SPEED[i] - SPEED[i - 1]).toDouble()
			val along = (want - SPEED[i - 1]) / span
			return TIMES[i - 1] + along * (TIMES[i] - TIMES[i - 1])
		}
		return TIMES.last()
	}

	/** El número de velocidad que habla [times] la tasa de velocidad-50. */
	fun speedFor(times: Double): Int {
		if (times <= TIMES.first()) return SPEED.first()
		for (i in 1 until TIMES.size) {
			if (times > TIMES[i]) continue
			val span = TIMES[i] - TIMES[i - 1]
			val along = if (span <= 0.0) 0.0 else (times - TIMES[i - 1]) / span
			return Math.round(SPEED[i - 1] + along * (SPEED[i] - SPEED[i - 1])).toInt()
		}
		return SPEED.last()
	}

	/**
	 * El número de velocidad para [percent] de la tasa que [base]
	 * habla, donde 100 es la voz dejada en paz.
	 */
	fun speedForPercent(base: Int, percent: Int): Int {
		if (percent == 100) return base
		val want = timesFor(base) * percent.coerceAtLeast(0) / 100.0
		return speedFor(want).coerceIn(0, Eci.SPEED_MAX)
	}
}
