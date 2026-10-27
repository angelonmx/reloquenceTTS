package com.angelonmx.reloquence

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10

/**
 * 4.3: que cada efecto haga lo que dice, y que apagado no haga nada.
 *
 * La pregunta de cada prueba es la misma en las dos direcciones: con
 * el interruptor o el nivel a cero, la salida tiene que ser la
 * entrada; y con el efecto puesto, tiene que apartarse de la entrada
 * de una forma medible.
 *
 * **Por qué tonos y no Spectra.** Un tono de una frecuencia es la
 * pregunta más limpia que se puede hacer a un filtro: "entra 4 kHz,
 * ¿cuánto sale?". Y tiene respuesta exacta. Las medidas por banda
 * dan ratios que dependen de la señal de prueba, y aquí ya se
 * confundieron dos veces: con la banda mal elegida, el anti-siseo
 * parecía funcionar cuando en realidad no tocaba nada, y al revés.
 * Un tono no se presta a eso.
 *
 * Los fallos que motivo esto:
 *
 * - El **grave profundo** no hacía nada. Dos versiones: la primera
 *   multiplicaba por el cuadrado de una envolvente de centésimas, y
 *   la segunda reducía la banda a la mitad, que no puede dar un
 *   subarmónico porque lo que baja a la mitad es la frecuencia de
 *   muestreo, no el tono. Ahora es un oscilador que sigue el
 *   periodo de la fundamental.
 * - Cuatro filtros tenían el **coeficiente del polo al revés**:
 *   las esquinas caían entre 6 y 59 veces donde decía. El paso bajo
 *   de 90 Hz cortaba a 5,3 kHz, el telephone se quedaba con una
 *   franja de 26 Hz pegada a Nyquist, el excitador saturaba desde
 *   638 Hz y el anti-siseo era en realidad un compresor de banda
 *   ancha.
 * - Cuatro filtros usaban **`y = a*(y + x - x_prev)`**, que tiene
 *   ganancia cero en continua: no es un paso bajo sino un
 *   diferenciador con fuga, así que el "paso alto" no quitaba graves.
 * - El **anti-siseo** sacaba la banda como `señal - pasoBajo`, que
 *   sale con la ganancia del coeficiente (-25 dB a 5 kHz), y con el
 *   umbral en 0,08 no llegaba a activarse nunca. Y la banda estaba
 *   restada al revés, o sea que era un paso bajo.
 * - El **excitador** mezclaba la banda deformada en vez de la
 *   diferencia con la limpia; como `tanh` solo comprime, restaba
 *   agudos en vez de añadirlos.
 * - El **lofi** con 2 bits era tan tosco que dejaba la voz muda, y
 *   el mínimo son 4.
 */
class EffectsTest {

	private val frames = 8192

	/**
	 * [Signal.RATE] es global porque los generadores de señal lo
	 * necesitan para fabricar las senales de prueba, y eso hace
	 * falible una cosa: una prueba que lo cambie y falle a mitad lo
	 * deja cambiado, y todas las que vienen detrás miden sobre la
	 * tasa equivocada sin decir nada. Por eso se vuelve a poner
	 * antes y después de cada una.
	 */
	@Before
	fun resetRateBefore() {
		Signal.RATE = 11025
	}

	@After
	fun resetRateAfter() {
		Signal.RATE = 11025
	}

	private fun chain(
		rateHz: Int = Signal.RATE, eqDb: IntArray? = null, reverbMix: Int = 0,
		echoMix: Int = 0, compress: Boolean = false, boost: Int = 100, gate: Boolean = false,
		tremoloMix: Int = 0, tremoloRate: Double = 5.5, chorus: Boolean = false,
		crusher: Boolean = false, crusherBits: Int = 6, flanger: Boolean = false,
		exciter: Int = 0, warmth: Boolean = false, deesser: Boolean = false,
		deesserDepth: Int = 100, telephone: Boolean = false, normalizer: Boolean = false,
		highpass: Boolean = false, drive: Boolean = false, phaser: Boolean = false,
		vibratoMix: Int = 0, ringmodMix: Int = 0, fuzzMix: Int = 0, wahMix: Int = 0,
		maximizerMix: Int = 0, tapeMix: Int = 0, subBassMix: Int = 0,
		reverbSize: Int = 50, body: Boolean = false, hd: Boolean = false
	) = AudioFx.Chain(
		rateHz = rateHz, eqDb = eqDb, reverbMix = reverbMix, echoMix = echoMix,
		compress = compress, boostPercent = boost, gate = gate, tremoloMix = tremoloMix,
		chorus = chorus, crusher = crusher, flanger = flanger, exciterMix = exciter,
		warmth = warmth, hdEnhance = hd, deesser = deesser, deesserDepth = deesserDepth,
		body = body, echoDelayMs = 220, tremoloRateHz = tremoloRate, phaser = phaser,
		vibratoMix = vibratoMix, telephone = telephone, normalizer = normalizer,
		highpass = highpass, drive = drive, ringmodMix = ringmodMix, fuzzMix = fuzzMix,
		wahMix = wahMix, maximizerMix = maximizerMix, tapeMix = tapeMix,
		subBassMix = subBassMix, reverbSize = reverbSize, crusherBits = crusherBits
	)

	/** Una cadena con todo a cero, que es lo mismo que apagado. */
	private fun off(
		eqDb: IntArray? = null, reverbMix: Int = 0, echoMix: Int = 0,
		compress: Boolean = false, gate: Boolean = false, tremoloMix: Int = 0,
		chorus: Boolean = false, crusher: Boolean = false, crusherBits: Int = 6,
		flanger: Boolean = false, warmth: Boolean = false, deesser: Boolean = false,
		deesserDepth: Int = 100, telephone: Boolean = false, normalizer: Boolean = false,
		highpass: Boolean = false, drive: Boolean = false, phaser: Boolean = false,
		vibratoMix: Int = 0, ringmodMix: Int = 0, fuzzMix: Int = 0, wahMix: Int = 0,
		maximizerMix: Int = 0, tapeMix: Int = 0, subBassMix: Int = 0, exciter: Int = 0
	) = chain(
		eqDb = eqDb, reverbMix = reverbMix, echoMix = echoMix, compress = compress,
		gate = gate, tremoloMix = tremoloMix, chorus = chorus, crusher = crusher,
		crusherBits = crusherBits, flanger = flanger, warmth = warmth,
		deesser = deesser, deesserDepth = deesserDepth, telephone = telephone,
		normalizer = normalizer, highpass = highpass, drive = drive, phaser = phaser,
		vibratoMix = vibratoMix, ringmodMix = ringmodMix, fuzzMix = fuzzMix,
		wahMix = wahMix, maximizerMix = maximizerMix, tapeMix = tapeMix,
		subBassMix = subBassMix, exciter = exciter
	)

	/** Decibelios que cambia un tono al pasar por la cadena. */
	private fun toneDb(c: AudioFx.Chain, hz: Double): Double {
		val out = Signal.through(c, Signal.tone(frames, hz))
		return 20.0 * log10(Signal.toneLevel(out, hz) / 0.30)
	}

	/** Un golpe seco al final del bloque, y lo que se mide es lo que
	 *  viene después. Al principio no sirve: a los 200 pasos ya hay
	 *  silencio y cualquier medida de cola da cero. */
	private fun hit(at: Int = 4000): ShortArray {
		val b = ShortArray(frames)
		for (i in 0 until 200) {
			b[at + i] = (Math.sin(2.0 * Math.PI * 400.0 * i / Signal.RATE) * 0.5 *
				Math.exp(-i / 60.0) * 32767.0).toInt().toShort()
		}
		return b
	}

	/** Cuanto queda de energia en la cola, a partir de [from]. */
	private fun tailRms(c: AudioFx.Chain, from: Int): Double {
		val out = Signal.through(c, hit())
		var sum = 0.0
		for (i in from until frames) {
			val d = out[i] / 32768.0
			sum += d * d
		}
		return Math.sqrt(sum / (frames - from))
	}

	// ---- la cadena entera, apagada, no toca nada --------------------

	@Test
	fun `a chain with nothing on leaves the signal alone`() {
		val voice = Signal.voice(frames)
		val out = Signal.through(off(), voice)
		// La rampa de arranque son 20 ms, así que se comparan las
		// muestras de después de que haya pasado.
		var worst = 0
		for (i in 3000 until voice.size) {
			worst = maxOf(worst, abs(voice[i].toInt() - out[i].toInt()))
		}
		assertTrue(
			"una cadena sin nada encendido no puede cambiar la señal (mayor diferencia $worst)",
			worst <= 2
		)
	}

	// ---- grave profundo (el que estaba roto) -----------------------

	@Test
	fun `sub bass generates the octave below the voice`() {
		// 4.3: la pregunta es "¿sale algo en la mitad de la
		// fundamental?". Apagado no puede salir nada, porque en un
		// tono puro no hay nada a la mitad. Encendido tiene que
		// salir un subarmónico de verdad, y tiene que ser lo
		// dominante: si lo que sube es el doble o el triple, el
		// efecto esta haciendo un armonico, no un subgrave.
		for (f0 in listOf(100.0, 150.0, 220.0)) {
			val off = Signal.through(off(), Signal.tone(frames, f0))
			val on = Signal.through(off(subBassMix = 100), Signal.tone(frames, f0))
			val before = Signal.toneLevel(off, f0 / 2.0)
			val after = Signal.toneLevel(on, f0 / 2.0)
			assertTrue(
				"con ${f0.toInt()} Hz de entrada tiene que salir un subarmónico a " +
					"${(f0 / 2).toInt()} Hz, y sale de $before a $after",
				after > 0.05
			)
			// Y tiene que valer más que cualquier otra frecuencia
			// nueva: el fundamental no cuenta, estaba antes.
			val atTwice = Signal.toneLevel(on, f0 * 2.0) - Signal.toneLevel(off, f0 * 2.0)
			assertTrue(
				"el sub a ${(f0 / 2).toInt()} Hz ($after) tiene que pesar más que el " +
					"armónico a ${(f0 * 2).toInt()} Hz ($atTwice)",
				after > atTwice
			)
		}
	}

	@Test
	fun `sub bass level zero is exactly off and more mix means more sub`() {
		val voice = Signal.voice(frames, f0 = 150.0)
		assertEquals(
			"un nivel de cero tiene que ser lo mismo que apagado",
			1.0,
			Signal.similarity(
				Signal.through(off(), voice),
				Signal.through(off(subBassMix = 0), voice)
			),
			0.0001
		)
		val half = Signal.through(off(subBassMix = 50), Signal.tone(frames, 150.0))
		val full = Signal.through(off(subBassMix = 100), Signal.tone(frames, 150.0))
		assertTrue(
			"más mezcla, más sub: 50 da ${Signal.toneLevel(half, 75.0)} y " +
				"100 da ${Signal.toneLevel(full, 75.0)}",
			Signal.toneLevel(full, 75.0) > Signal.toneLevel(half, 75.0)
		)
	}

	@Test
	fun `sub bass stays quiet when there is no voice`() {
		// Si el sub sonara solo, entre frases se oiria un tono sin
		// voz, que es lo peor que puede hacer este efecto.
		val silence = ShortArray(frames)
		val out = Signal.through(off(subBassMix = 100), silence)
		assertTrue(
			"en silencio no tiene que salir nada, y sale rms ${Signal.rms(out)}",
			Signal.rms(out) < 0.002
		)
	}

	// ---- los cuatro filtros con el coeficiente al revés -----------

	@Test
	fun `the high pass takes the rumble and leaves the voice`() {
		// Pasa a 90 Hz. Antes de 4.3 el coeficiente estaba invertido y
		// cortaba a 5,3 kHz, o sea que se llevaba por delante los
		// agudos de la voz y el retumbe se quedaba intacto.
		assertTrue(
			"un paso alto de 90 Hz tiene que quitar los 45 Hz",
			toneDb(off(highpass = true), 45.0) < -4.0
		)
		assertTrue(
			"...y no puede tocar los 2 kHz (los quita ${toneDb(off(highpass = true), 2000.0)} dB)",
			toneDb(off(highpass = true), 2000.0) > -1.5
		)
	}

	@Test
	fun `the telephone keeps the voice band and drops both ends`() {
		// Banda de 300 a 3400. Antes se quedaba con una franja de
		// 26 Hz pegada a Nyquist, y ademas el paso alto no quitaba
		// graves, así que era un simple paso bajo.
		assertTrue(
			"el telephone tiene que quitar los 150 Hz (los quita ${toneDb(off(telephone = true), 150.0)} dB)",
			toneDb(off(telephone = true), 150.0) < toneDb(off(telephone = true), 1000.0)
		)
		assertTrue(
			"...y también los 5 kHz (los quita ${toneDb(off(telephone = true), 5000.0)} dB)",
			toneDb(off(telephone = true), 5000.0) < toneDb(off(telephone = true), 1000.0)
		)
		// Y la diferencia tiene que ser de verdad, no de medio dB. Con
		// la compensacion de 2,6 que habia antes, el telephone
		// subia los 5 kHz 3 dB en vez de bajarlos.
		assertTrue(
			"los 5 kHz tienen que caerse mas de 5,5 dB, y caen " +
				"${toneDb(off(telephone = true), 5000.0)}",
			toneDb(off(telephone = true), 5000.0) < -5.5
		)
		// Y la banda de voz no puede cambiar de nivel: un telephone
		// que sube o baja el volumen no es un telephone.
		assertTrue(
			"la banda de voz tiene que quedar igual, y cambia " +
				"${toneDb(off(telephone = true), 1000.0)} dB",
			abs(toneDb(off(telephone = true), 1000.0)) < 1.0
		)
	}

	@Test
	fun `the deesser takes sibilance off and leaves the body alone`() {
		// Banda de 3,2 a 7 kHz. Con el coeficiente al revés el paso
		// bajo caía en 486 Hz, y al arreglarlo la banda salía con
		// -25 dB y el umbral nunca se alcanzaba. Además estaba
		// restada al revés, o sea que era un paso bajo.
		//
		// Un tono puro es el peor caso para este efecto, y por eso
		// los números son modestos: el detector compara la banda con
		// el total, y en un tono la banda es una parte pequeña del
		// todo. Con siseo de verdad, que es lo que pasa en una "/s/",
		// la proportion es mucho mayor y el corte llega al tope. Lo
		// que se comprueba aqui es la dirección y que el cuerpo no
		// se toque; la profundidad va en la prueba siguiente.
		try {
			for (rate in listOf(11025, 22050)) {
				Signal.RATE = rate
				val cut = chain(rateHz = rate, deesser = true, deesserDepth = 100)
				val hiss = toneDb(cut, 4000.0)
				val body = toneDb(cut, 500.0)
				assertTrue(
					"a $rate Hz el anti-siseo tiene que quitar el siseo de 4 kHz " +
						"($hiss dB) mas de lo que toca el cuerpo ($body dB)",
					hiss < body - 1.0
				)
				assertTrue(
					"a $rate Hz tiene que notar, y solo quita $hiss dB",
					hiss < -1.0
				)
			}
		} finally {
			Signal.RATE = 11025
		}
	}

	@Test
	fun `the deesser cuts more the deeper it is set`() {
		try {
			// A 22050, que es donde la banda de 3,2-7 kHz cabe entera;
			// a 11025 el tope de la banda la deja en 3,2-4,96.
			Signal.RATE = 22050
			val soft = toneDb(chain(deesser = true, deesserDepth = 25), 4000.0)
			val hard = toneDb(chain(deesser = true, deesserDepth = 100), 4000.0)
			assertTrue(
				"más profundidad quita más: 25 da $soft dB y 100 da $hard dB",
				hard < soft - 0.4
			)
		} finally {
			Signal.RATE = 11025
		}
	}

	@Test
	fun `the deesser is off when the switch is off`() {
		assertEquals(
			"sin encender no hay anti-siseo",
			1.0,
			Signal.similarity(
				Signal.through(off(), Signal.noise(frames)),
				Signal.through(off(deesser = true, deesserDepth = 0), Signal.noise(frames))
			),
			0.0001
		)
	}

	@Test
	fun `the exciter adds air without touching the body`() {
		// Se mezcla la diferencia entre la banda deformada y la
		// limpia, no la deformada entera. Con la entera, como tanh
		// solo comprime, restaba agudos en vez de añadirlos.
		val air = toneDb(off(exciter = 100), 4000.0)
		val body = toneDb(off(exciter = 100), 500.0)
		assertTrue(
			"el excitador tiene que subir los 4 kHz, y los sube $air dB",
			air > 1.0
		)
		assertTrue(
			"...y no puede subir los 500 Hz, y los sube $body dB",
			body < 1.0
		)
	}

	// ---- bitcrusher -------------------------------------------------

	@Test
	fun `the lofi is off when its switch is off, even at six bits`() {
		// 4.3: la condición miraba solo el rango de bits, y el valor
		// por omisión (6) siempre estaba dentro, así que el lofi
		// venía encendido y no se podía apagar.
		val voice = Signal.voice(frames)
		assertEquals(
			"con el interruptor apagado el lofi no hace nada, aunque los bits sean 6",
			1.0,
			Signal.similarity(
				Signal.through(off(), voice),
				Signal.through(off(crusher = false, crusherBits = 6), voice)
			),
			0.0001
		)
	}

	@Test
	fun `the lofi does quantise when its switch is on and coarser is coarser`() {
		val voice = Signal.voice(frames)
		assertTrue(
			"con el interruptor encendido el lofi tiene que cambiar la señal",
			Signal.similarity(
				Signal.through(off(), voice),
				Signal.through(off(crusher = true, crusherBits = 4), voice)
			) < 0.995
		)
		val coarse = Signal.through(off(crusher = true, crusherBits = 4), voice)
		val fine = Signal.through(off(crusher = true, crusherBits = 12), voice)
		assertTrue(
			"4 bits da menos niveles que 12: ${Signal.distinctLevels(coarse)} contra " +
				"${Signal.distinctLevels(fine)}",
			Signal.distinctLevels(coarse) < Signal.distinctLevels(fine)
		)
	}

	// ---- reverberación y tamaño -------------------------------------

	@Test
	fun `reverb is off with no level and a bigger room has a longer tail`() {
		val voice = Signal.voice(frames)
		assertEquals(
			"sin mezcla no hay reverberación",
			1.0,
			Signal.similarity(
				Signal.through(off(), voice),
				Signal.through(off(reverbMix = 0), voice)
			),
			0.0001
		)
		// El tamaño estira los peineos. Antes el valor se guardaba y
		// no hacia nada, que es media parte del bug.
		val small = tailRms(chain(reverbMix = 90, reverbSize = 0), 4300)
		val mid = tailRms(chain(reverbMix = 90, reverbSize = 50), 4300)
		val big = tailRms(chain(reverbMix = 90, reverbSize = 100), 4300)
		assertTrue("una sala grande tiene más cola que una pequeña: $small -> $mid -> $big", big > small)
		assertTrue("...y la diferencia tiene que verse: $small contra $big", big > small * 1.25)
	}

	// ---- el resto, uno por uno --------------------------------------

	@Test
	fun `the equaliser is off without it and moves energy with it`() {
		val voice = Signal.voice(frames)
		assertEquals(
			"sin encender no hay ecualizador",
			1.0,
			Signal.similarity(
				Signal.through(off(), voice),
				Signal.through(off(eqDb = IntArray(10)), voice)
			),
			0.0001
		)
		// 1 kHz es la banda 4.
		val cut = IntArray(10).also { it[4] = -12 }
		assertTrue(
			"el ecualizador tiene que cambiar la señal",
			Signal.similarity(
				Signal.through(off(), voice),
				Signal.through(off(eqDb = cut), voice)
			) < 0.99
		)
	}

	@Test
	fun `echo is off at zero and repeats when on`() {
		val voice = Signal.voice(frames)
		assertEquals(
			"sin mezcla no hay eco",
			1.0,
			Signal.similarity(
				Signal.through(off(), voice),
				Signal.through(off(echoMix = 0), voice)
			),
			0.0001
		)
		assertTrue(
			"el eco cambia la señal",
			Signal.similarity(
				Signal.through(off(), voice),
				Signal.through(chain(echoMix = 80), voice)
			) < 0.99
		)
	}

	@Test
	fun `the compressor narrows the distance between loud and quiet`() {
		val voice = Signal.voice(frames)
		assertTrue(
			"comprimir reduce la distancia entre lo fuerte y lo flojo: " +
				"${crest(Signal.through(off(), voice))} contra " +
				"${crest(Signal.through(chain(compress = true, boost = 100), voice))}",
			crest(Signal.through(chain(compress = true, boost = 100), voice)) <
				crest(Signal.through(off(), voice))
		)
	}

	private fun crest(b: ShortArray): Double {
		var peak = 0.0
		for (v in b) peak = maxOf(peak, abs(v.toDouble() / 32768.0))
		val r = Signal.rms(b)
		return if (r <= 0.0) 0.0 else peak / r
	}

	@Test
	fun `tape makes the pitch wander and is off at zero`() {
		// La cinta es una línea de retardo con la cabeza movida. Lo
		// que la delata es que la separación entre cruces por cero
		// deja de ser plana.
		val voice = Signal.voice(frames, f0 = 160.0)
		val plain = Signal.through(off(), voice)
		assertTrue(
			"la cinta tiene que dispersar los cruces por cero: " +
				"${Signal.crossingSpread(plain)} contra " +
				"${Signal.crossingSpread(Signal.through(chain(tapeMix = 100), voice))}",
			Signal.crossingSpread(Signal.through(chain(tapeMix = 100), voice)) >
				Signal.crossingSpread(plain) + 0.01
		)
		assertEquals(
			"cinta a cero es nada",
			1.0,
			Signal.similarity(plain, Signal.through(off(tapeMix = 0), voice)),
			0.0001
		)
	}

	@Test
	fun `tape and vibrato are not the same thing`() {
		val voice = Signal.voice(frames, f0 = 160.0)
		assertTrue(
			"cinta y vibrato tienen que sonar distinto",
			Signal.similarity(
				Signal.through(chain(vibratoMix = 100), voice),
				Signal.through(chain(tapeMix = 100), voice)
			) < 0.999
		)
	}

	@Test
	fun `every switchable effect changes the signal when switched on`() {
		// Una entrada por efecto con interruptor. Si alguno no se
		// mueve, el interruptor no hace nada, y eso es un bug y no
		// una costumbre.
		val voice = Signal.voice(frames)
		val cases: List<Pair<String, AudioFx.Chain>> = listOf(
			"chorus" to chain(chorus = true),
			"flanger" to chain(flanger = true),
			"phaser" to chain(phaser = true),
			"telephone" to chain(telephone = true),
			"highpass" to chain(highpass = true),
			"drive" to chain(drive = true),
			"normalizer" to chain(normalizer = true),
			"gate" to chain(gate = true),
			"compressor" to chain(compress = true),
			"warmth" to chain(warmth = true),
			"deesser" to chain(deesser = true, deesserDepth = 100),
			"crusher" to chain(crusher = true, crusherBits = 4)
		)
		val plain = Signal.through(off(), voice)
		for ((name, c) in cases) {
			assertTrue(
				"el efecto con interruptor $name no cambia nada de la señal",
				Signal.similarity(plain, Signal.through(c, voice)) < 0.999
			)
		}
	}

	@Test
	fun `every level effect changes the signal above zero`() {
		val voice = Signal.voice(frames)
		val cases: List<Pair<String, AudioFx.Chain>> = listOf(
			"tremolo" to chain(tremoloMix = 80, tremoloRate = 8.0),
			"vibrato" to chain(vibratoMix = 80),
			"ringmod" to chain(ringmodMix = 80),
			"fuzz" to chain(fuzzMix = 80),
			"wah" to chain(wahMix = 80),
			"maximizador" to chain(maximizerMix = 80),
			"cinta" to chain(tapeMix = 80),
			"grave profundo" to chain(subBassMix = 80),
			"reverberación" to chain(reverbMix = 80),
			"eco" to chain(echoMix = 80),
			"aire" to chain(exciter = 80),
			"cuerpo" to chain(body = true)
		)
		val plain = Signal.through(off(), voice)
		for ((name, c) in cases) {
			assertTrue(
				"el efecto de nivel $name no cambia nada de la señal",
				Signal.similarity(plain, Signal.through(c, voice)) < 0.999
			)
		}
	}

	@Test
	fun `nothing is silenced and nothing runs away`() {
		// Un efecto que se come la señal o que la desata tampoco
		// sirve. El maximizador sube hasta 2,9x a tope, que es lo
		// que se le pide, asi que el techo va por debajo de 3. El
		// suelo es holgado porque con todo encendido a la vez (lofi
		// de 5 bits, puerta, cinta, anti-siseo y subgrave) el nivel
		// baja de forma bastante: aqui se mira que no se hunda, no
		// que suene bien.
		val voice = Signal.voice(frames)
		val cases = listOf(
			"lofi 4 bits" to chain(crusher = true, crusherBits = 4),
			"grave profundo 100" to chain(subBassMix = 100),
			"cinta 100" to chain(tapeMix = 100),
			"fuzz 100" to chain(fuzzMix = 100),
			"maximizador 100" to chain(maximizerMix = 100),
			"catedral" to chain(reverbMix = 100, reverbSize = 100),
			"eco 100" to chain(echoMix = 100),
			"anti-siseo 100" to chain(deesser = true, deesserDepth = 100),
			"teléfono" to chain(telephone = true),
			"todo junto" to chain(
				eqDb = IntArray(10) { if (it % 2 == 0) 8 else -8 }, reverbMix = 70,
				compress = true, boost = 140, gate = true, tremoloMix = 60, chorus = true,
				crusher = true, crusherBits = 5, flanger = true, exciter = 60, warmth = true,
				deesser = true, phaser = true, vibratoMix = 50, ringmodMix = 40, fuzzMix = 40,
				wahMix = 40, maximizerMix = 70, tapeMix = 60, subBassMix = 70, drive = true
			)
		)
		val plainRms = Signal.rms(Signal.through(off(), voice))
		for ((name, c) in cases) {
			val out = Signal.through(c, voice)
			val r = Signal.rms(out)
			assertTrue("$name dejó la señal en silencio ($r)", r > plainRms * 0.3)
			assertTrue("$name dejó la señal desatada ($r, era $plainRms)", r < plainRms * 3.2)
			var peak = 0
			for (v in out) peak = maxOf(peak, abs(v.toInt()))
			assertTrue("$name ha recortado duro (pico $peak)", peak <= 32767)
		}
	}
}
