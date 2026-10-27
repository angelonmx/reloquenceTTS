package com.angelonmx.reloquence

import kotlin.random.Random

/**
 * 4.3: lo que las pruebas necesitan para mirar el audio.
 *
 * Un efecto que no hace nada y uno que hace muy poco se oyen igual
 * de mal, asi que estas pruebas no preguntan "¿suena bien?", que no
 * se puede responder sin oidos. Preguntan cosas que tienen
 * respuesta exacta: si la senal cambia cuando deberia, cuanta
 * energia hay por debajo de una frecuencia dada, cuantos pasos
 * distintos tiene la salida, cuanto tarda en callarse. Eso es lo
 * que separa un efecto que funciona de uno que esta puesto.
 */
object Signal {

	/**
	 * 4.3: variable, no constante, porque hay filtros cuya esquina cae
	 * a distinta frecuencia segun la tasa y a 11025 el de 5,5 kHz se
	 * queda justo en Nyquist. La prueba que lo mira la cambia y la
	 * vuelve a poner.
	 */
	var RATE = 11025

	/** Una vocal sintetica: la fundamental con sus armonicos, que es
	 *  lo que hace que un filtro suene a voz y no a pitido. */
	fun voice(frames: Int, f0: Double = 140.0, amp: Double = 0.30): ShortArray {
		val out = ShortArray(frames)
		for (i in 0 until frames) {
			val t = i.toDouble() / RATE
			// Un par de formantes movidos, como una vocal /a/.
			val f1 = 700.0 + 120.0 * Math.sin(2.0 * Math.PI * 3.0 * t)
			val f2 = 1220.0 + 200.0 * Math.sin(2.0 * Math.PI * 1.7 * t)
			var v = 0.0
			for (h in 1..12) {
				val fh = f0 * h.toDouble()
				var a = 1.0 / (h * h).toDouble()
				if (fh > 900) a *= 0.35
				val env = if (fh >= 200.0 && fh <= 4000.0) {
					1.0 + 1.6 * Math.exp(-Math.pow((fh - f1) / 220.0, 2.0)) +
						0.9 * Math.exp(-Math.pow((fh - f2) / 320.0, 2.0))
				} else 0.15
				v += a * env * Math.sin(2.0 * Math.PI * fh * t)
			}
			v *= amp
			out[i] = (v * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
		}
		return out
	}

	/**
	 * Ruido blanco, para efectos que no necesitan una voz.
	 *
	 * Esto lleva un generador de verdad ([Random]) y no un LCG
	 * propio. Con un LCG de 64 bits tomando los bits altos, el ruido
	 * salia con el 97 % de su energia por debajo de 300 Hz, y eso
	 * hacia que todas las medidas por banda dieran falso: los
	 * efectos que se suponian que tocaban los agudos no se veian
	 * porque la senal de prueba no tenia agudos.
	 */
	fun noise(frames: Int, seed: Long = 12345L, amp: Double = 0.3): ShortArray {
		val rnd = Random(seed)
		return ShortArray(frames) {
			((rnd.nextDouble() * 2.0 - 1.0) * amp * 32767.0)
				.toInt().coerceIn(-32768, 32767).toShort()
		}
	}

	/** Un tono puro, para medir cuanto pasa o quanto quita un filtro
	 *  en una frecuencia concreta. */
	fun tone(frames: Int, hz: Double, amp: Double = 0.3): ShortArray {
		return ShortArray(frames) {
			(Math.sin(2.0 * Math.PI * hz * it / RATE) * amp * 32767.0)
				.toInt().coerceIn(-32768, 32767).toShort()
		}
	}

	/** La amplitud que queda de un tono pura de [hz], en la misma
	 *  escala que [rms]. Un filtro que quita tiene que dar menos aqui
	 *  que en la entrada. */
	fun toneLevel(buf: ShortArray, hz: Double): Double {
		// Se demodula y se promedia el valor absoluto: con el coseno y
		// el seno a la vez sale la envolvente de esa frecuencia sin
		// depender de donde empiece la fase.
		var re = 0.0
		var im = 0.0
		for (i in buf.indices) {
			val w = 2.0 * Math.PI * hz * i / RATE
			re += buf[i] / 32768.0 * Math.cos(w)
			im += buf[i] / 32768.0 * Math.sin(w)
		}
		return 2.0 * Math.sqrt(re * re + im * im) / buf.size
	}

	/** Energia en la banda por debajo de [hz], sobre el total. Un
	 *  grave profundo tiene que subirla; un paso alto, bajarla. */
	fun lowBandRatio(buf: ShortArray, hz: Double = 200.0): Double = band(buf, 0.0, hz)

	/** Lo mismo pero por encima de [hz]. */
	fun highBandRatio(buf: ShortArray, hz: Double = 4000.0): Double = band(buf, hz, RATE / 2.0)

	/** Energia entre [fromHz] y [toHz], sobre el total. Para
	 *  comprobar que un filtro de banda deja lo que dice y quita lo
	 *  que dice. */
	fun bandRatio(buf: ShortArray, fromHz: Double, toHz: Double): Double = band(buf, fromHz, toHz)

	private fun band(buf: ShortArray, fromHz: Double, toHz: Double): Double {
		val spec = spectrum(buf)
		var pick = 0.0
		var all = 0.0
		for (i in spec.indices) {
			val f = i.toDouble() * RATE / (2.0 * spec.size)
			val p = spec[i] * spec[i]
			all += p
			if (f in fromHz..toHz) pick += p
		}
		return if (all <= 0.0) 0.0 else pick / all
	}

	/** Cuantos pasos de magnitud distintos hay. El cuantizador da
	 *  uno por bit, asi que un lofi mas tosco da menos. */
	fun distinctLevels(buf: ShortArray): Int {
		val seen = HashSet<Int>()
		for (v in buf) seen.add(v.toInt())
		return seen.size
	}

	/** Ruido cuadratico medio, en escala de amplitud. */
	fun rms(buf: ShortArray): Double {
		var sum = 0.0
		for (v in buf) { val d = v.toDouble() / 32768.0; sum += d * d }
		return Math.sqrt(sum / buf.size)
	}

	/** Cuantos pasos por cero. */
	fun crossings(buf: ShortArray): Int {
		var n = 0
		for (i in 1 until buf.size) {
			if ((buf[i - 1] < 0) != (buf[i] < 0)) n++
		}
		return n
	}

	/** Dispersion de los cruces por cero, en pasos, sobre la media.
	 *  La cinta los separa; un efecto que no toca la frecuencia no
	 *  los mueve. */
	fun crossingSpread(buf: ShortArray): Double {
		val at = ArrayList<Int>()
		for (i in 1 until buf.size) {
			if ((buf[i - 1] < 0) != (buf[i] < 0)) at.add(i)
		}
		if (at.size < 4) return 0.0
		var sum = 0.0
		var sum2 = 0.0
		for (i in 1 until at.size) {
			val d = (at[i] - at[i - 1]).toDouble()
			sum += d
			sum2 += d * d
		}
		val n = at.size - 1
		val mean = sum / n
		if (mean <= 0.0) return 0.0
		return Math.sqrt(Math.max(0.0, sum2 / n - mean * mean)) / mean
	}

	/** Milisegundos hasta bajar de la mitad del pico inicial. Una
	 *  reverberacion mas grande tarda mas. */
	fun decayMs(buf: ShortArray, fromFrame: Int): Double {
		var peak = 0.0
		for (i in 0 until minOf(200, buf.size)) {
			peak = maxOf(peak, Math.abs(buf[i].toDouble() / 32768.0))
		}
		if (peak <= 0.0) return 0.0
		val half = peak * 0.5
		for (i in fromFrame until buf.size) {
			if (Math.abs(buf[i].toDouble() / 32768.0) < half) {
				return (i - fromFrame).toDouble() * 1000.0 / RATE
			}
		}
		return (buf.size - fromFrame).toDouble() * 1000.0 / RATE
	}

	/** Cuanto se parece una senal a otra, de 0 a 1. Es la forma de
	 *  preguntar "el interruptor hace algo?". */
	fun similarity(a: ShortArray, b: ShortArray): Double {
		val n = minOf(a.size, b.size)
		if (n == 0) return 1.0
		var num = 0.0
		var da = 0.0
		var db = 0.0
		for (i in 0 until n) {
			val x = a[i].toDouble()
			val y = b[i].toDouble()
			num += x * y
			da += x * x
			db += y * y
		}
		if (da <= 0.0 || db <= 0.0) return 0.0
		return num / Math.sqrt(da * db)
	}

	/** Espectro de magnitud, con ventana de Hann. */
	fun spectrum(buf: ShortArray): DoubleArray {
		val n = 1024
		val usable = buf.size - buf.size % n
		if (usable < n * 2) return DoubleArray(n / 2)
		val half = n / 2
		val out = DoubleArray(half)
		val win = DoubleArray(n) { i -> 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1)) }
		val re = DoubleArray(half)
		val im = DoubleArray(half)
		var blocks = 0
		var start = 0
		while (start + n <= usable) {
			for (k in 0 until half) {
				var r = 0.0
				var m = 0.0
				for (i in 0 until n) {
					val w = win[i] * buf[start + i]
					r += w * Math.cos(2.0 * Math.PI * k * i / n)
					m += w * Math.sin(2.0 * Math.PI * k * i / n)
				}
				re[k] += r
				im[k] += m
			}
			blocks++
			start += n
		}
		if (blocks == 0) return out
		for (k in 0 until half) out[k] = Math.sqrt(re[k] * re[k] + im[k] * im[k]) / blocks
		return out
	}

	/** Pasa un bloque por la cadena, en trozos como hace el
	 *  servicio, que es como se procesa de verdad. */
	fun through(chain: AudioFx.Chain, buf: ShortArray, chunk: Int = 2048): ShortArray {
		val out = ShortArray(buf.size)
		// Un trocito cada vez, como hace el servicio, que es donde
		// la rampa de arranque y el limitador se portan como en el
		// aparato.
		val slice = ByteArray(chunk * 2)
		var at = 0
		while (at < buf.size) {
			// process() lee n como numero de muestras, asi que par.
			val n = minOf(chunk, buf.size - at)
			val bytes = n * 2
			for (i in 0 until n) {
				val v = buf[at + i].toInt()
				slice[i * 2] = (v and 0xFF).toByte()
				slice[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
			}
			chain.process(slice, bytes)
			for (i in 0 until n) {
				out[at + i] = ((slice[i * 2].toInt() and 0xFF) or
					(slice[i * 2 + 1].toInt() shl 8)).toShort()
			}
			at += n
		}
		return out
	}

	/** PCM 16 little-endian, que es lo que entrega el motor. La
	 *  cadena trabaja sobre bytes, igual que en el servicio, asi que
	 *  medir sobre muestras seria medir otra cosa. */
	fun toBytes(buf: ShortArray): ByteArray {
		val out = ByteArray(buf.size * 2)
		for (i in buf.indices) {
			val v = buf[i].toInt()
			out[i * 2] = (v and 0xFF).toByte()
			out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
		}
		return out
	}

	fun toSamples(bytes: ByteArray): ShortArray {
		val out = ShortArray(bytes.size / 2)
		for (i in out.indices) {
			out[i] = ((bytes[i * 2].toInt() and 0xFF) or
				(bytes[i * 2 + 1].toInt() shl 8)).toShort()
		}
		return out
	}
}
