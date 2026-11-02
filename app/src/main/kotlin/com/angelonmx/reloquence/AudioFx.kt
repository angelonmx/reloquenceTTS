package com.angelonmx.reloquence

import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.effects.DelayEffect
import be.tarsos.dsp.effects.FlangerEffect
import be.tarsos.dsp.io.TarsosDSPAudioFormat
import kotlin.math.abs

/** Operaciones PCM pequeñas sobre el audio ya sintetizado.
 *
 *  Todo corre en el hilo de síntesis. Los adaptadores por bloques
 *  reutilizan buffers mientras se mantenga el tamaño del bloque.
 */
object AudioFx {

	/**
	 * 4.3: coeficiente de un polo para un filtro de un polo con la
	 * esquina en [hz], a [rateHz] de muestreo.
	 *
	 * Existe porque cuatro filtros tenian el coeficiente del revés
	 * (`rc / (rc + dt)` en vez de `dt / (rc + dt)`) y eso no sonaba
	 * a "un poco distinto", sonaba a otra cosa: con la tasa de por
	 * omision de 11025, el paso bajo de 90 Hz cortaba por 5,3 kHz
	 * (x59), el telephone de 300-3400 Hz se quedaba en una franja de
	 * 26 Hz pegada a Nyquist, y el excitador de "aire por encima de
	 * 4 kHz" saturaba toda la voz desde 638 Hz. El coeficiente
	 * invertido no es un error de precision: mueve la esquina a un
	 * orden de magnitud y el efecto hace otra cosa.
	 *
	 * Aqui va con la exponencial, que es exacta y no depende de
	 * recordar en que sentido va el cociente. El tope al 45 % de la
	 * tasa es lo que hace que un filtro pedido a 5,5 kHz siga
	 * teniendo sentido a 11025, donde Nyquist esta en 5512 y el
	 * filtro se quedaria sin banda por encima.
	 */
	private fun onePole(hz: Double, rateHz: Int): Double {
		val r = rateHz.coerceAtLeast(8000).toDouble()
		return 1.0 - Math.exp(-2.0 * Math.PI * hz.coerceIn(1.0, r * 0.45) / r)
	}

	/**
	 * 4.3: una banda de la señal, con ganancia ~1 dentro de ella.
	 *
	 * Se saca como `pasoBajo(hiHz) - pasoBajo(loHz)`, y el orden de
	 * la resta es lo unico que separa una banda de un paso bajo. El
	 * paso con la esquina mas alta deja pasar mas de todo, asi que
	 * restando el de la esquina baja queda justo el trozo de en
	 * medio; al reves sale un paso bajo y el "anti-siseo" acababa
	 * metiendose con los graves en vez de con el silbido.
	 *
	 * Tampoco se puede sacar como `senal - pasoBajo`, que es lo que
	 * habia: eso da la banda con una ganancia de solo el
	 * coeficiente del filtro, unos -25 dB a 5 kHz, y con el umbral
	 * en 0,08 el efecto no llegaba ni a activarse.
	 *
	 * Por encima de [hiHz] los dos se apagan y la banda se cierra
	 * sola, así que no toca el ultratope aunque se pida a 7 kHz.
	 */
	private class Band(rateHz: Int, loHz: Double, hiHz: Double) {
		private val aLo = onePole(loHz, rateHz)
		private val aHi = onePole(hiHz, rateHz)
		private var lo = 0.0
		private var hi = 0.0
		private var started = false

		fun run(s: Double): Double {
			if (!started) {
				started = true
				lo = s
				hi = s
				return 0.0
			}
			lo += aLo * (s - lo)
			hi += aHi * (s - hi)
			return hi - lo
		}
	}

	/** tanh rápida (Padé): indistinguible para audio y ~3x más
	 *  barata que kotlin.math.tanh, que tira de exp. Se usa en
	 *  ganancia, excitador, calidez, drive y limitador, que antes
	 *  llamaban a tanh por muestra. */
	private fun fastTanh(x: Double): Double {
		if (x > 3.0) return 1.0
		if (x < -3.0) return -1.0
		val x2 = x * x
		return x * (27.0 + x2) / (27.0 + 9.0 * x2)
	}

	/** Aplica una ganancia de [percent] % (100 = intacto, 0 = silencio).
	 *  Por encima de 100 el recorte es suave (tanh rápida)
	 *  para que subir el volumen no suene a golpes. */
	fun gain(buffer: ByteArray, n: Int, percent: Int) {
		if (percent == 100) return
		if (percent <= 0) {
			buffer.fill(0, 0, n.coerceIn(0, buffer.size))
			return
		}
		if (percent < 100) {
			// Atenuar no necesita shaping: multiplicación directa,
			// mucho más barata que tanh por muestra.
			val gNum = percent
			var i = 0
			while (i + 1 < n) {
				val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
				val shaped = sample * gNum / 100
				val c = shaped.coerceIn(-32768, 32767)
				buffer[i] = (c and 0xFF).toByte()
				buffer[i + 1] = ((c shr 8) and 0xFF).toByte()
				i += 2
			}
			return
		}
		val g = percent / 100.0
		var i = 0
		while (i + 1 < n) {
			val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
			val shaped = (fastTanh(sample * g / 32768.0) * 32767.0).toInt()
				.coerceIn(-32768, 32767)
			buffer[i] = (shaped and 0xFF).toByte()
			buffer[i + 1] = ((shaped shr 8) and 0xFF).toByte()
			i += 2
		}
	}

	/** Sube el volumen de las primeras muestras poco a poco para
	 *  que el empalme entre trozos no chasquee. Rampa de 15 ms con
	 *  curva de igual potencia (seno al cuadrado): un fundido
	 *  lineal deja un codo audible a 44k/48k con el HD fuerte,
	 *  que es el clic del arranque. */
	fun fadeIn(buffer: ByteArray, n: Int, rateHz: Int, ms: Int = 15) {
		val frames = minOf(n / 2, rateHz * ms / 1000).coerceAtLeast(1)
		for (i in 0 until frames) {
			val sample = (buffer[i * 2].toInt() and 0xFF) or (buffer[i * 2 + 1].toInt() shl 8)
			val t = (i + 1).toDouble() / (frames + 1)
			// Igual potencia: sin² sube suave sin codo.
			val g = Math.sin(t * Math.PI / 2.0)
			val scaled = (sample * g).toInt()
			buffer[i * 2] = (scaled and 0xFF).toByte()
			buffer[i * 2 + 1] = ((scaled shr 8) and 0xFF).toByte()
		}
	}

	/** Baja el volumen de las últimas muestras poco a poco, espejo
	 *  de [fadeIn]: evita el chasquido al cortar un trozo en medio
	 *  de una frase (cada trozo es una emisión propia del motor y
	 *  el anterior puede terminar fuera de cero). 12 ms en
	 *  coseno al cuadrado para que el corte no se oiga. */
	fun fadeOut(buffer: ByteArray, n: Int, rateHz: Int, ms: Int = 12) {
		val frames = minOf(n / 2, rateHz * ms / 1000).coerceAtLeast(1)
		val total = n / 2
		for (i in 0 until frames) {
			val at = total - frames + i
			val sample = (buffer[at * 2].toInt() and 0xFF) or (buffer[at * 2 + 1].toInt() shl 8)
			val t = (frames - i).toDouble() / (frames + 1)
			val g = Math.sin(t * Math.PI / 2.0)
			val scaled = (sample * g).toInt()
			buffer[at * 2] = (scaled and 0xFF).toByte()
			buffer[at * 2 + 1] = ((scaled shr 8) and 0xFF).toByte()
		}
	}

	/** Las diez bandas del ecualizador, en hercios. Las que queden
	 *  por encima de lo que la tasa actual da (Nyquist) se saltan. */
	val EQ_FREQS = intArrayOf(60, 170, 310, 600, 1000, 3000, 6000, 12000, 14000, 16000)

	/** Realce HD por tasa: a más hercios, más aire. El motor
	 *  sintetiza hasta ~5 kHz; subir la tasa con sinc limpio suena
	 *  igual porque no hay nada nuevo arriba. Este realce añade
	 *  armónicos y brillo proporcional a la tasa para que cada
	 *  salto (11k -> 16k -> 22k -> 32k -> 44k -> 48k) se distinga
	 *  del anterior. 0 en 11k o menos: ahí no hay banda aguda
	 *  que realzar y solo se escucharía más siseo.
	 *
	 *  En 4.2 los escalones son grandes a propósito (antes apenas
	 *  se notaba el cambio de 16k en adelante): cada tasa suena
	 *  claramente más abierta que la anterior. El limitador final
	 *  cuida que no sature. */
	fun hdAmount(rateHz: Int): Int = when {
		rateHz >= 48000 -> 100
		rateHz >= 44100 -> 95
		rateHz >= 32000 -> 85
		rateHz >= 22050 -> 65
		rateHz >= 16000 -> 45
		else -> 0
	}

	/** Presencia HD en dB según la tasa: el brillo lineal que
	 *  acompaña al excitador. Escalonado para que cada tasa
	 *  suene un punto más abierta que la anterior (16k +3,
	 *  22k +5, 32k +6, 44k +8, 48k +9). 0 cuando no hay HD.
	 *  4.2: escalones más grandes para que el salto se note. */
	fun hdPresenceDb(rateHz: Int): Int = when {
		rateHz >= 48000 -> 9
		rateHz >= 44100 -> 8
		rateHz >= 32000 -> 6
		rateHz >= 22050 -> 5
		rateHz >= 16000 -> 3
		else -> 0
	}

	/** Una cadena de efectos para una emisión: filtro de
	 *  teléfono, ecualizador de diez bandas, realce de graves y
	 *  presencia, eco y reverberación, vibrato, coro, flanger,
	 *  phaser, excitador de claridad, calidez, anti-siseo y
	 *  limitador final que impide saturar, sobre PCM mono de 16
	 *  bits. Se crea una por emisión y se le pasa cada trozo en
	 *  orden; guarda su propio estado entre trozos. */
	class Chain(
		rateHz: Int,
		eqDb: IntArray?,
		reverbMix: Int,
		bassDb: Int = 0,
		presenceDb: Int = 0,
		echoMix: Int = 0,
		compress: Boolean = false,
		boostPercent: Int = 100,
		gate: Boolean = false,
		tremoloMix: Int = 0,
		chorus: Boolean = false,
		crusher: Boolean = false,
		flanger: Boolean = false,
		exciterMix: Int = 0,
		warmth: Boolean = false,
		hdEnhance: Boolean = true,
		deesser: Boolean = false,
		body: Boolean = true,
		echoDelayMs: Int = 220,
		tremoloRateHz: Double = 5.5,
		phaser: Boolean = false,
		vibratoMix: Int = 0,
		telephone: Boolean = false,
		normalizer: Boolean = false,
		highpass: Boolean = false,
		drive: Boolean = false,
		ringmodMix: Int = 0,
		fuzzMix: Int = 0,
		wahMix: Int = 0,
		maximizerMix: Int = 0,
		tapeMix: Int = 0,
		subBassMix: Int = 0,
		reverbSize: Int = 50,
		crusherBits: Int = 6,
		deesserDepth: Int = 100
	) {
		private val boost = boostPercent.coerceIn(0, 200) / 100.0
		private val phone: Telephone? = if (telephone) Telephone(rateHz) else null
		private val lowcut: LowCut? = if (highpass) LowCut(rateHz) else null
		// 4.3: el interruptor y el nivel, los dos. Mirando solo el
		// rango de bits el lofi quedaba siempre puesto, porque el
		// valor por omision (6) siempre esta dentro: el interruptor
		// no hacia nada y no se podia apagar.
		private val crush: Crusher? = if (crusher && crusherBits in 4..15) Crusher(crusherBits) else null
		// 4.0: sin reserva si el EQ viene vacío o todo a cero.
		// Antes se armaban 10 biquads por emisión aunque idle.
		private val eq: Peaking? = if (eqDb != null && eqDb.any { it != 0 }) Peaking(rateHz, eqDb) else null
		private val bass: Shelf? = Shelf.low(rateHz, bassDb.coerceIn(-12, 12))?.takeIf { bassDb != 0 }
		private val presence: Shelf? = Shelf.high(rateHz, presenceDb.coerceIn(-12, 12))?.takeIf { presenceDb != 0 }
		private val noiseGate: NoiseGate? = if (gate) NoiseGate(rateHz) else null
		private val comp: Compressor? = if (compress) Compressor(rateHz) else null
		private val tremolo: Tremolo? = if (tremoloMix.coerceIn(0, 100) > 0) {
			Tremolo(rateHz, tremoloMix.coerceIn(0, 100) / 100.0, tremoloRateHz)
		} else null
		private val vibrato: Vibrato? = if (vibratoMix.coerceIn(0, 100) > 0) {
			Vibrato(rateHz, vibratoMix.coerceIn(0, 100) / 100.0)
		} else null
		// 4.1: modulador en anillo (portadora 30 Hz estilo Dalek).
		// Voz metálica/robot sin memoria entre muestras: no mete
		// transitorio de arranque y suena distinto del trémolo
		// (2-12 Hz, solo volumen), coro, flanger y phaser.
		private val ringmod: RingMod? = if (ringmodMix.coerceIn(0, 100) > 0) {
			RingMod(rateHz, ringmodMix.coerceIn(0, 100) / 100.0)
		} else null
		// 4.2: fuzz (recorte duro con pre-ganancia) y wah
		// (pasa-bajos que sigue la envolvente). Ambos in-place y
		// sin memoria problemática: el fuzz no tiene estado y el
		// wah solo un seguidor suave, así que no hay transitorios.
		private val fuzz: Fuzz? = if (fuzzMix.coerceIn(0, 100) > 0) {
			Fuzz(fuzzMix.coerceIn(0, 100) / 100.0)
		} else null
		private val wah: Wah? = if (wahMix.coerceIn(0, 100) > 0) {
			Wah(rateHz, wahMix.coerceIn(0, 100) / 100.0)
		} else null
		// 4.3: cinta y grave profundo, los dos nuevos.
		private val tape: Tape? = if (tapeMix.coerceIn(0, 100) > 0) {
			Tape(rateHz, tapeMix.coerceIn(0, 100) / 100.0)
		} else null
		private val sub: SubBass? = if (subBassMix.coerceIn(0, 100) > 0) {
			SubBass(rateHz, subBassMix.coerceIn(0, 100) / 100.0)
		} else null
		// 4.1: maximizador (brickwall con antelación): empuja el
		// nivel con pre-ganancia y techo duro sin recorte áspero.
		// Es la palabra final cuando está activo (el limitador
		// suave descansa para no cocinar dos veces).
		private val maximizer: Maximizer? = if (maximizerMix.coerceIn(0, 100) > 0) {
			Maximizer(rateHz, maximizerMix.coerceIn(0, 100) / 100.0)
		} else null
		private val chorusFx: Chorus? = if (chorus) Chorus(rateHz) else null
		private val tarsosEffects: TarsosEffects? =
			if (flanger || echoMix.coerceIn(0, 100) > 0) {
				TarsosEffects(rateHz, flanger, echoMix.coerceIn(0, 100), echoDelayMs)
			} else null
		private val phaserFx: Phaser? = if (phaser) Phaser(rateHz) else null
		private val reverb: Reverb? = if (reverbMix.coerceIn(0, 100) > 0) {
			Reverb(rateHz, reverbMix.coerceIn(0, 100) / 100.0, reverbSize)
		} else null
		// Excitador pedido + realce HD automático por tasa. En 4.2
		// el HD aporta hasta 80 puntos para que 48k suene
		// claramente más abierto que 22k; el limitador final
		// cuida que no sature.
		private val hd = if (hdEnhance) hdAmount(rateHz) else 0
		private val exciter: Exciter? = run {
			val total = exciterMix.coerceIn(0, 100) + hd * 80 / 100
			if (total > 0) Exciter(rateHz, total.coerceIn(0, 100) / 100.0) else null
		}
		private val warm: Warmth? = if (warmth) Warmth(rateHz) else null
		private val deess: Deesser? = if (deesser && deesserDepth > 0) Deesser(rateHz, deesserDepth) else null
		private val driveFx: Drive? = if (drive) Drive() else null
		private val normFx: Normalizer? = if (normalizer) Normalizer(rateHz) else null
		// Cuerpo base 3.3: graves +5 dB, agudos +2 dB y aire
		// propio. Antes era +2/+1 dB con una pizca de excitador
		// y apenas se notaba; ahora engorda de verdad sin
		// embarrar (el limitador jamás lo nota).
		private val bodyBass: Shelf? = if (body) Shelf.low(rateHz, 5) else null
		private val bodyTop: Shelf? = if (body) Shelf.high(rateHz, 2) else null
		private val bodyAir: Exciter? = if (body && exciterMix == 0) {
			// Con HD activo se baja a la mitad para no duplicar
			// brillo (antes se apagaba del todo y el cuerpo se
			// quedaba sin aire justo cuando más falta hacía).
			Exciter(rateHz, if (hd == 0) 0.22 else 0.12)
		} else null
		// Presencia automática HD: +3 a +9 dB según tasa, si el
		// usuario no puso presencia propia. Cada salto de tasa abre
		// más; a 48k el aire es evidente sin forzar la salida.
		private val hdPresence: Shelf? = if (hd > 0 && presenceDb == 0) {
			Shelf.high(rateHz, hdPresenceDb(rateHz))
		} else null
		// Rampa de arranque de la cadena (~25 ms): los filtros
		// nacen en cero y su primer transitorio sonaba como un
		// golpecito, sobre todo a 44k/48k con el HD fuerte.
		// Esta rampa lo tapa aunque quien llama olvide el fade.
		private var rampDone = 0
		private val rampTotal = (rateHz * 25 / 1000).coerceIn(64, 2048)
		// Salida segura: el limitador solo trabaja cuando algo se
		// pasa del techo, así que con ajustes suaves no cambia nada
		// y con perfiles fuertes impide el recorte duro.
		private val limiter = Limiter(rateHz)
		val idle: Boolean = eq == null && bass == null && presence == null && noiseGate == null &&
			comp == null && tremolo == null && vibrato == null && ringmod == null && fuzz == null &&
			wah == null && maximizer == null && chorusFx == null && crush == null &&
			tape == null && sub == null &&
			tarsosEffects == null && phaserFx == null && phone == null && lowcut == null &&
			reverb == null && exciter == null && warm == null && hdPresence == null &&
			deess == null && driveFx == null && normFx == null &&
			bodyBass == null && bodyTop == null && bodyAir == null && boost == 1.0

		fun process(buffer: ByteArray, n: Int) {
			if (idle) return
			// Locales: los campos se leen una vez por trozo en
			// lugar de una vez por muestra (el nulo ya no se
			// comprueba dentro del bucle caliente).
			val bBoost = boost
			val fPhone = phone
			val fLowcut = lowcut
			val fBodyBass = bodyBass
			val fBodyTop = bodyTop
			val fBodyAir = bodyAir
			val fCrush = crush
			val fWarm = warm
			val fBass = bass
			val fEq = eq
			val eqOn = fEq != null
			val fPresence = presence
			val fHdPresence = hdPresence
			val fExciter = exciter
			val fDrive = driveFx
			val fDeess = deess
			val fGate = noiseGate
			val fComp = comp
			val fNorm = normFx
			val fTremolo = tremolo
			val fVibrato = vibrato
			val fRingmod = ringmod
			val fFuzz = fuzz
			val fWah = wah
			val fMax = maximizer
			val fTape = tape
		val fSub = sub
		val fChorus = chorusFx
			val fPhaser = phaserFx
			val fReverb = reverb
			val fLimiter = limiter
			val frames = n / 2
			// Rampa de arranque: 0 -> 1 en los primeros rampTotal
			// frames de la emisión. Tapa el transitorio de los
			// filtros sin acortar nada audible (20 ms).
			val rampFrom = rampDone
			val rampTo = (rampDone + frames).coerceAtMost(rampTotal)
			val ramping = rampFrom < rampTotal
			var i = 0
			while (i < frames) {
				val lo = i * 2
				var s = ((buffer[lo].toInt() and 0xFF) or (buffer[lo + 1].toInt() shl 8)).toDouble()
				if (bBoost != 1.0) s *= bBoost
				if (fPhone != null) s = fPhone.run(s)
				if (fLowcut != null) s = fLowcut.run(s)
				if (fBodyBass != null) s = fBodyBass.run(s)
				if (fBodyTop != null) s = fBodyTop.run(s)
				if (fBodyAir != null) s = fBodyAir.run(s)
				if (fCrush != null) s = fCrush.run(s)
				if (fWarm != null) s = fWarm.run(s)
				if (fBass != null) s = fBass.run(s)
				if (eqOn) s = fEq!!.run(s)
				if (fPresence != null) s = fPresence.run(s)
				if (fHdPresence != null) s = fHdPresence.run(s)
				if (fExciter != null) s = fExciter.run(s)
				if (fDrive != null) s = fDrive.run(s)
				if (fDeess != null) s = fDeess.run(s)
				if (fGate != null) s = fGate.run(s)
				if (fComp != null) s = fComp.run(s)
				if (fNorm != null) s = fNorm.run(s)
				if (fTremolo != null) s = fTremolo.run(s)
				if (fVibrato != null) s = fVibrato.run(s)
				if (fRingmod != null) s = fRingmod.run(s)
				if (fFuzz != null) s = fFuzz.run(s)
				if (fWah != null) s = fWah.run(s)
				if (fChorus != null) s = fChorus.run(s)
				if (fPhaser != null) s = fPhaser.run(s)
				if (fTape != null) s = fTape.run(s)
				if (fReverb != null) s = fReverb.run(s)
				if (fSub != null) s = fSub.run(s)
				// Maximizador al final cuando está activo (limita él);
				// si no, el limitador suave de siempre.
				s = if (fMax != null) fMax.run(s) else fLimiter.run(s)
				if (ramping) {
					val step = rampFrom + i
					if (step < rampTotal) s *= (step + 1).toDouble() / (rampTotal + 1)
				}
				val out = s.toInt().coerceIn(-32768, 32767)
				buffer[lo] = (out and 0xFF).toByte()
				buffer[lo + 1] = ((out shr 8) and 0xFF).toByte()
				i++
			}
			rampDone = rampTo
			tarsosEffects?.process(buffer, n)
		}

		/** TarsosDSP processors operate on reusable float blocks. */
		private class TarsosEffects(
			rateHz: Int,
			flanger: Boolean,
			echoMix: Int,
			echoDelayMs: Int
		) {
			private val rate = rateHz.coerceAtLeast(8000)
			private val format = TarsosDSPAudioFormat(rate.toFloat(), 16, 1, true, false)
			private val event = AudioEvent(format)
			private val flangerProcessor: FlangerEffect? =
				if (flanger) FlangerEffect(0.005, 0.5, rate.toDouble(), 0.5) else null
			private val delayProcessor: DelayEffect? =
				if (echoMix > 0) DelayEffect(
					echoDelayMs.coerceIn(80, 600) / 1000.0,
					0.5,
					rate.toDouble()
				) else null
			private val delayAmount = echoMix.coerceIn(0, 100) / 100.0
			private var floats = FloatArray(0)
			private var dry = FloatArray(0)
			private var bytesProcessed = 0L

			fun process(buffer: ByteArray, byteCount: Int) {
				val frames = (byteCount / 2).coerceAtMost(buffer.size / 2)
				if (frames == 0) return
				if (floats.size != frames) floats = FloatArray(frames)
				if (delayProcessor != null && dry.size != frames) dry = FloatArray(frames)

				for (i in 0 until frames) {
					val offset = i * 2
					val sample = (buffer[offset].toInt() and 0xFF) or
						(buffer[offset + 1].toInt() shl 8)
					floats[i] = sample.toShort() / 32768.0f
					if (delayProcessor != null) dry[i] = floats[i]
				}

				event.setFloatBuffer(floats)
				event.setOverlap(0)
				event.setBytesProcessed(bytesProcessed)
				event.setBytesProcessing(frames * 2)
				delayProcessor?.process(event)
				if (delayProcessor != null) {
					for (i in 0 until frames) {
						val original = dry[i]
						val delayed = floats[i] - original
						floats[i] = (original * (1.0 - delayAmount * 0.5) +
							delayed * delayAmount).toFloat()
					}
				}
				flangerProcessor?.process(event)

				for (i in 0 until frames) {
					val signal = floats[i].toDouble()
					val safe = if (abs(signal) <= 1.0) signal else fastTanh(signal)
					val sample = (safe * 32767.0).toInt().coerceIn(-32768, 32767)
					val offset = i * 2
					buffer[offset] = (sample and 0xFF).toByte()
					buffer[offset + 1] = ((sample shr 8) and 0xFF).toByte()
				}
				bytesProcessed += frames * 2L
			}
		}

		/** Diez picos en serie (receta RBJ, Q = 1). Solo las bandas
		 *  con ganancia distinta de cero y por debajo de Nyquist. */
		private class Peaking(rateHz: Int, gains: IntArray?) {
			private val b0 = DoubleArray(10)
			private val b1 = DoubleArray(10)
			private val b2 = DoubleArray(10)
			private val a1 = DoubleArray(10)
			private val a2 = DoubleArray(10)
			private val x1 = DoubleArray(10)
			private val x2 = DoubleArray(10)
			private val y1 = DoubleArray(10)
			private val y2 = DoubleArray(10)
			private var count = 0
			val active: Boolean

			init {
				val nyquist = rateHz / 2.0
				if (gains != null) {
					for (b in 0 until 10) {
						val g = gains.getOrElse(b) { 0 }.coerceIn(-12, 12)
						val f = EQ_FREQS[b].toDouble()
						if (g == 0 || f >= nyquist * 0.95) continue
						val a = Math.pow(10.0, g / 40.0)
						val w = 2.0 * Math.PI * f / rateHz
						val alpha = Math.sin(w) / 2.0
						val b0v = 1.0 + alpha * a
						val b1v = -2.0 * Math.cos(w)
						val b2v = 1.0 - alpha * a
						val a0v = 1.0 + alpha / a
						val at = count
						b0[at] = b0v / a0v
						b1[at] = b1v / a0v
						b2[at] = b2v / a0v
						a1[at] = -2.0 * Math.cos(w) / a0v
						a2[at] = (1.0 - alpha / a) / a0v
						count++
					}
				}
				active = count > 0
			}

			fun run(s: Double): Double {
				var out = s
				for (b in 0 until count) {
					val y = b0[b] * out + b1[b] * x1[b] + b2[b] * x2[b] - a1[b] * y1[b] - a2[b] * y2[b]
					x2[b] = x1[b]
					x1[b] = out
					y2[b] = y1[b]
					y1[b] = y
					out = y
				}
				return out
			}
		}

		/** Estante de graves (120 Hz) o presencia (4500 Hz),
		 *  receta RBJ. Nada si queda fuera de Nyquist. */
		private class Shelf private constructor(
			private val b0: Double, private val b1: Double, private val b2: Double,
			private val a1: Double, private val a2: Double
		) {
			private var x1 = 0.0
			private var x2 = 0.0
			private var y1 = 0.0
			private var y2 = 0.0

			fun run(s: Double): Double {
				val y = b0 * s + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
				x2 = x1
				x1 = s
				y2 = y1
				y1 = y
				return y
			}

			companion object {
				private const val SLOPE = 0.9

				fun low(rateHz: Int, db: Int): Shelf? {
					if (db == 0) return null
					return shelf(rateHz, 120.0, db, true)
				}

				fun high(rateHz: Int, db: Int): Shelf? {
					if (db == 0) return null
					return shelf(rateHz, 4500.0, db, false)
				}

				private fun shelf(rateHz: Int, freq: Double, db: Int, low: Boolean): Shelf? {
					if (freq >= rateHz / 2.0 * 0.95) return null
					val a = Math.pow(10.0, db / 40.0)
					val w = 2.0 * Math.PI * freq / rateHz
					val alpha = Math.sin(w) / 2.0 * Math.sqrt((a + 1.0 / a) * (1.0 / SLOPE - 1.0) + 2.0)
					val cos = Math.cos(w)
					val b0v: Double
					val b1v: Double
					val b2v: Double
					val a0v: Double
					val a1v: Double
					val a2v: Double
					if (low) {
						b0v = a * ((a + 1.0) - (a - 1.0) * cos + 2.0 * Math.sqrt(a) * alpha)
						b1v = 2.0 * a * ((a - 1.0) - (a + 1.0) * cos)
						b2v = a * ((a + 1.0) - (a - 1.0) * cos - 2.0 * Math.sqrt(a) * alpha)
						a0v = (a + 1.0) + (a - 1.0) * cos + 2.0 * Math.sqrt(a) * alpha
						a1v = -2.0 * ((a - 1.0) + (a + 1.0) * cos)
						a2v = (a + 1.0) + (a - 1.0) * cos - 2.0 * Math.sqrt(a) * alpha
					} else {
						b0v = a * ((a + 1.0) + (a - 1.0) * cos + 2.0 * Math.sqrt(a) * alpha)
						b1v = -2.0 * a * ((a - 1.0) + (a + 1.0) * cos)
						b2v = a * ((a + 1.0) + (a - 1.0) * cos - 2.0 * Math.sqrt(a) * alpha)
						a0v = (a + 1.0) - (a - 1.0) * cos + 2.0 * Math.sqrt(a) * alpha
						a1v = 2.0 * ((a - 1.0) - (a + 1.0) * cos)
						a2v = (a + 1.0) - (a - 1.0) * cos - 2.0 * Math.sqrt(a) * alpha
					}
					return Shelf(b0v / a0v, b1v / a0v, b2v / a0v, a1v / a0v, a2v / a0v)
				}
			}
		}

		/** Compresor nocturno: nivela lo bajito con lo fuerte
		 *  (umbral -18 dB, razón 3:1, ataque 5 ms, salida 120 ms,
		 *  sin maquillaje: ganancia unidad para no saturar nunca).
		 *  Para escuchar parejo a volumen bajo. */
		private class Compressor(rateHz: Int) {
			private val attack = Math.exp(-1.0 / (0.005 * rateHz))
			private val release = Math.exp(-1.0 / (0.12 * rateHz))
			private var env = 0.0

			fun run(s: Double): Double {
				val level = Math.abs(s) / 32768.0
				env = if (level > env) attack * env + (1.0 - attack) * level
				else release * env + (1.0 - release) * level
				var gain = 1.0
				if (env > THRESHOLD) gain = Math.pow(env / THRESHOLD, 1.0 / RATIO - 1.0)
				return s * gain * MAKEUP
			}

			private companion object {
				const val THRESHOLD = 0.1259
				const val RATIO = 3.0
				const val MAKEUP = 1.0
			}
		}

		/** Anti-siseo: doma los silbidos (s, sh) sin apagar la voz.
		 *  Mide la banda alta y, cuando se dispara, recorta solo
		 *  esa banda de forma proporcional. */
		private class Deesser(rateHz: Int, depth: Int = 100) {
			// 4.3: cuanto se quita. El tope del corte es lo que se
			// escala, asi que el interruptor era todo o nada y ahora
			// se puede quita solo el silbido que molesta.
			private val maxCut = 0.85 * (depth.coerceIn(0, 100) / 100.0)
			// 4.3: aqui habia dos fallos y se cancelaban. El
			// coeficiente del paso bajo estaba invertido, asi que el
			// "paso bajo de 5,5 kHz" era en realidad uno de 486 Hz y
			// todo lo que habia por encima de media kilociclo se
			// tomaba por silbido: era un compresor de banda ancha y
			// por eso bajaba el volumen entero. Y al arreglar eso, la
			// banda salia con la ganancia del coeficiente, -25 dB, con
			// lo cual el umbral de 0,08 no lo alcanzaba nunca y el
			// efecto se quedaba sin hacer absolutely nada. Las dos
			// cosas a la vez se veian como "no hace nada".
			//
			// Ahora la banda sale a escala de la senal (ver [Band]) y
			// el umbral es relativo al nivel de la propia voz, que es
			// lo que evita que una voz bajita se active el efecto solo
			// por ser bajita.
			private val band = Band(rateHz, 3200.0, 7000.0)
			private val r = rateHz.coerceAtLeast(8000).toDouble()
			private val attack = 1.0 - Math.exp(-1.0 / (0.003 * r))
			private val release = 1.0 - Math.exp(-1.0 / (0.12 * r))
			private var envBand = 0.0
			private var envAll = 0.0
			private var cut = 0.0

			fun run(s: Double): Double {
				val norm = s / 32768.0
				val b = band.run(norm)
				val lb = Math.abs(b)
				val la = Math.abs(norm)
				envBand = if (lb > envBand) attack * envBand + (1.0 - attack) * lb
				else release * envBand + (1.0 - release) * lb
				envAll = if (la > envAll) attack * envAll + (1.0 - attack) * la
				else release * envAll + (1.0 - release) * la
				// Cuanto siseo hay por encima de lo normal en esta
				// voz: cero en una voz limpia, uno en un silbido.
				val expected = envAll * RATIO
				val over = if (expected > 1.0e-5) (envBand / expected - 1.0) else 0.0
				val target = (over * SLOPE).coerceIn(0.0, maxCut)
				cut += (target - cut) * (if (target > cut) 0.5 else 0.02)
				return (norm - b * cut) * 32768.0
			}

			private companion object {
				/** Banda de siseo normal para una voz, en parte de la
				 *  señal completa. Con 0,12 un tono metido entero en
				 *  la banda se quita casi del todo y una voz normal
				 *  no se entera, que es lo que se busca. */
				const val RATIO = 0.12
				const val SLOPE = 1.2
			}
		}

		/** Limitador de salida: techo suave a -1 dB. Solo trabaja
		 *  cuando algo se pasa; con ajustes suaves no cambia nada y
		 *  con perfiles fuertes impide el recorte duro y áspero. */
		private class Limiter(rateHz: Int) {
			private val release = Math.exp(-1.0 / (0.05 * rateHz.coerceAtLeast(8000)))
			private var env = 0.0
			private var g = 1.0

			fun run(s: Double): Double {
				val a = Math.abs(s)
				env = if (a > env) a else env * release + a * (1.0 - release)
				val target = if (env > CEILING) CEILING / env else 1.0
				// Ataque suavizado (0.3 en vez de 0.6): con el HD
				// fuerte el ataque brusco bombeaba y sonaba como
				// golpecitos al arrancar las frases.
				g += (target - g) * (if (target < g) 0.3 else 0.02)
				val limited = s * g
				// Red de seguridad: saturación suave en vez de corte.
				return if (limited > 32767.0 || limited < -32768.0) {
					fastTanh(limited / 32768.0) * 32767.0
				} else limited
			}

			private companion object {
				const val CEILING = 30000.0
			}
		}

		/** Maximizador: brickwall con antelación para sonar denso
		 *  y parejo como radio procesada, sin recorte áspero.
		 *  [mix] de 0 a 1 es cuánta pre-ganancia entra (hasta
		 *  +9 dB); el techo duro queda en -0.3 dBFS.
		 *
		 *  La señal se retrasa unos 5 ms en una línea corta y la
		 *  ganancia se calcula de la envolvente de entrada con
		 *  ataque rapidísimo y salida lenta, así que el techo se
		 *  anticipa en vez de reaccionar tarde (eso era el bombeo).
		 *  Todo O(1) por muestra y estable a cualquier tasa. */
		private class Maximizer(rateHz: Int, mix: Double) {
			private val size = (rateHz.coerceAtLeast(8000) * 5 / 1000).coerceIn(32, 256)
			private val buf = DoubleArray(size)
			private var at = 0
			private val pre = 1.0 + mix.coerceIn(0.0, 1.0) * 1.9
			private val r = rateHz.coerceAtLeast(8000).toDouble()
			private val attack = 1.0 - Math.exp(-1.0 / (0.001 * r))
			private val release = 1.0 - Math.exp(-1.0 / (0.12 * r))
			private var env = 0.0
			private var g = 1.0

			fun run(s: Double): Double {
				val hot = s * pre
				val level = Math.abs(hot)
				env += (level - env) * (if (level > env) attack else release)
				val want = if (env > CEILING) CEILING / env else 1.0
				g += (want - g) * (if (want < g) 0.5 else 0.05)
				buf[at] = hot
				at = (at + 1) % size
				val delayed = buf[at]
				val out = delayed * g
				return out.coerceIn(-32768.0, 32768.0)
			}

			private companion object {
				const val CEILING = 32100.0
			}
		}

		/** Puerta de ruido: cierra el siseo entre palabras
		 *  (umbral -48 dB, sostén 150 ms). Sin chasquidos: la
		 *  ganancia sube rápido y baja en ~80 ms. */
		private class NoiseGate(rateHz: Int) {
			private val holdMax = (rateHz * 0.15).toInt().coerceAtLeast(64)
			private var gain = 0.0
			private var hold = 0

			fun run(s: Double): Double {
				val level = Math.abs(s) / 32768.0
				if (level > THRESHOLD) hold = holdMax
				else if (hold > 0) hold--
				val target = if (level > THRESHOLD || hold > 0) 1.0 else 0.0
				gain += (target - gain) * (if (target > gain) ATTACK else RELEASE)
				return s * gain
			}

			private companion object {
				const val THRESHOLD = 0.004
				const val ATTACK = 0.6
				const val RELEASE = 0.002
			}
		}

		/** Trémolo: la amplitud respira a [lfoHz]. [mix] de 0 a 1. */
		private class Tremolo(rateHz: Int, private val mix: Double, lfoHz: Double = 5.5) {
			private var phase = 0.0
			private val step = 2.0 * Math.PI * lfoHz.coerceIn(2.0, 12.0) / rateHz

			fun run(s: Double): Double {
				phase += step
				if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI
				val lfo = 0.5 + 0.5 * Math.sin(phase)
				return s * (1.0 - mix * DEPTH * lfo)
			}

			private companion object {
				const val DEPTH = 0.8
			}
		}

		/** Coro: una copia con retardo modulado que engorda la voz.
		 *  Retardo base 20 ms, vaivén de 6 ms a 0.8 Hz, mezcla fija. */
		private class Chorus(rateHz: Int) {
			private val buf = DoubleArray((rateHz * 0.03).toInt().coerceAtLeast(64))
			private var at = 0
			private var phase = 0.0
			private val step = 2.0 * Math.PI * 0.8 / rateHz
			private val base = rateHz * 0.02
			private val swing = rateHz * 0.006

			fun run(s: Double): Double {
				buf[at] = s
				phase += step
				if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI
				val delay = base + swing * (0.5 + 0.5 * Math.sin(phase))
				val back = at - delay
				val i0 = Math.floor(back).toInt()
				val frac = (back - i0).toFloat()
				val size = buf.size
				val a = buf[(i0 % size + size) % size]
				val b = buf[((i0 + 1) % size + size) % size]
				val wet = a + (b - a) * frac
				at = (at + 1) % size
				return s * (1.0 - MIX) + wet * MIX
			}

			private companion object {
				const val MIX = 0.3
			}
		}

		/** Bitcrusher lofi. 4.3: con numero de bits en vez de paso
		 *  fijo, para pedir desde un griton sucio hasta un telefono
		 *  de los viejos. */
		private class Crusher(bits: Int = 6) {
			private val step: Double = run {
				val used = bits.coerceIn(4, 16)
				32768.0 * 2.0 / (1 shl used)
			}

			fun run(s: Double): Double {
				val q = Math.floor(s / step + 0.5) * step
				return q.coerceIn(-32768.0, 32767.0)
			}
		}

		/** Excitador de claridad: realza armónicos agudos con
		 *  saturación suave sobre la banda alta. [mix] de 0 a 1.
		 *  Es lo que hace que 32k/48k suenen con más aire que 11k. */
		private class Exciter(rateHz: Int, private val mix: Double) {
			// La banda a excitar, de 3 a 7 kHz. Antes era un paso
			// alto de 4 kHz con el coeficiente invertido, asi que
			// excitaba desde 638 Hz (mas armónicos en toda la voz) y
			// ademas con una ganancia de -25 dB: dos fallos, o sea
			// que no se oia.
			private val band = Band(rateHz, 3000.0, 7000.0)

			fun run(s: Double): Double {
				val norm = s / 32768.0
				// Lo que se mezcla es la diferencia entre la banda
				// deformada y la banda limpia, no la deformada. La
				// diferencia son solo los armonicos nuevos: al
				// mezclarla no sube el nivel ni se satura, que es
				// justo lo que se le pide a un excitador. Antes se
				// mezclaba la deformada entera, y como tanh solo
				// comprime, el efecto restaba agudos en vez de
				// anadirlos.
				val b = band.run(norm)
				val added = fastTanh(b * 3.0) - b
				return (norm + added * mix) * 32768.0
			}
		}

		/** Calidez: saturación suave de cinta + redondeo de agudos.
		 *  Engorda la voz sin subir el nivel. */
		private class Warmth(rateHz: Int) {
			private var lp = 0.0
			private var started = false
			// 6,5 kHz estan por encima de Nyquist a la tasa de por
			// omision, asi que el tope de [onePole] lo deja en lo mas
			// alto que hay de verdad en vez de doblarlo hacia abajo.
			private val a = onePole(6500.0, rateHz)

			fun run(s: Double): Double {
				val norm = s / 32768.0
				if (!started) {
					started = true
					lp = norm
					return s
				}
				lp += a * (norm - lp)
				val body = norm * 0.6 + lp * 0.4
				val sat = fastTanh(body * 1.6) * 0.85
				return (body * 0.5 + sat * 0.5) * 32768.0
			}
		}

		/** Filtro de teléfono: pasa-banda 300-3400 Hz, dos polos por
		 *  lado. Voz de radio/teléfono: recorta graves y aire pero
		 *  deja intacta la banda donde vive la inteligibilidad, así
		 *  que se entiende mejor con ruido de fondo. Sin estado
		 *  entre trozos más allá de sus dos filtros. */
		private class Telephone(rateHz: Int) {
			// 4.3: el paso alto tenía el coeficiente invertido y
			// filtraba a 3376 Hz en vez de 300, así que el telephone
			// se quedaba con una franja de 26 Hz pegada a Nyquist en
			// vez de una banda de voz. Y la actualización era
			// `a * (estado + s - previo)`, que tiene ganancia cero en
			// continua, o sea que el paso alto no quitaba graves: el
			// efecto entero era un paso bajo de 3400 Hz.
			//
			// Ahora son dos polos por lado. Con uno solo, la caida por
			// encima de 3400 era de medio dB y no se oia como
			// teléfono; el comentario de "doble filtro" era lo que
			// se queria y no lo que habia.
			private val hpA = onePole(300.0, rateHz)
			private val lpA = onePole(3400.0, rateHz)
			private var hp1 = 0.0
			private var hp2 = 0.0
			private var lp1 = 0.0
			private var lp2 = 0.0
			private var lp3 = 0.0
			private var started = false

			fun run(s: Double): Double {
				if (!started) {
					started = true
					hp1 = s
					hp2 = s
					lp1 = s
					lp2 = s
					lp3 = s
					return s
				}
				hp1 += hpA * (s - hp1)
				hp2 += hpA * (hp1 - hp2)
				// Lo que se queda es la seccion menos el paso bajo:
				// hp1 y hp2 son el estado del paso bajo, y lo que sale
				// de ahi es el paso alto. Sin la resta esto era un
				// paso bajo de 300 Hz, o sea un telephone que solo
				// quita agudos.
				val hp = s - hp2
				lp1 += lpA * (hp - lp1)
				lp2 += lpA * (lp1 - lp2)
				lp3 += lpA * (lp2 - lp3)
				// Compensa lo que los polos se comen en la banda de
				// voz, que a 1 kHz es menos de un decibel. Con 2,6 (lo
				// que habia, pensado para un polo por lado) el
				// telephone no quita agudos, los sube 3 dB: +8,3 dB de
				// ganancia plana se comian la caida del paso bajo.
				//
				// Tres polos arriba y no dos porque dos no bastan: un
				// polo de los de aqui solo cae 2,5 dB a 5 kHz (la
				// curva digital es mas plana que 1/sqrt(1+(f/fc)^2),
				// porque la correspondencia comprime las frecuencias
				// altas), y con dos el telephone apenas se notaba.
				return lp3 * 1.15
			}
		}

		/** Vibrato: trémolo de tono en vez de volumen. Un retardo
		 *  corto modulado a 5 Hz con profundidad según [mix]
		 *  (0 a 1): da calidez de voz cantada sin cambiar el
		 *  nivel. Estable por construcción (solo lee pasado). */
		private class Vibrato(rateHz: Int, private val mix: Double) {
			private val buf = DoubleArray((rateHz * 0.012).toInt().coerceAtLeast(64))
			private var at = 0
			private var phase = 0.0
			private val step = 2.0 * Math.PI * 5.0 / rateHz
			private val base = rateHz * 0.006
			private val swing = rateHz * 0.004 * mix.coerceIn(0.0, 1.0)

			fun run(s: Double): Double {
				buf[at] = s
				phase += step
				if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI
				val delay = base + swing * (0.5 + 0.5 * Math.sin(phase))
				val back = at - delay
				val i0 = Math.floor(back).toInt()
				val frac = back - i0
				val size = buf.size
				val a = buf[(i0 % size + size) % size]
				val b = buf[((i0 + 1) % size + size) % size]
				val wet = a + (b - a) * frac
				at = (at + 1) % size
				return s * (1.0 - WET) + wet * WET
			}

			private companion object {
				const val WET = 0.7
			}
		}

		/** Modulador en anillo: multiplica la voz por una portadora
		 *  senoidal de 30 Hz (estilo Dalek). Crea bandas laterales
		 *  (suma y diferencia) que suenan metálicas/robot sin cambiar
		 *  el nivel medio: mezcla [mix] de 0 a 1 entre voz seca y voz
		 *  modulada. Sin memoria entre muestras salvo la fase, así
		 *  que no mete transitorios ni depende de la tasa. */
		private class RingMod(rateHz: Int, private val mix: Double) {
			private var phase = 0.0
			private val step = 2.0 * Math.PI * CARRIER_HZ / rateHz.coerceAtLeast(8000)

			fun run(s: Double): Double {
				phase += step
				if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI
				val wet = s * Math.sin(phase)
				return s * (1.0 - mix) + wet * mix
			}

			private companion object {
				const val CARRIER_HZ = 30.0
			}
		}

		/** Fuzz: distorsión por recorte duro con pre-ganancia según
		 *  [mix] (0 a 1): la voz se aplasta contra el techo y suena
		 *  agresiva/rasposa. Distinto del drive (saturación suave) y
		 *  del crusher (cuantización): aquí el corte es recto. Sin
		 *  memoria, así que no hay transitorios; la salida se
		 *  normaliza por la pre-ganancia para no pegar saltos de
		 *  nivel al mover el ajuste. */
		private class Fuzz(mix: Double) {
			private val pre = 1.0 + mix.coerceIn(0.0, 1.0) * 7.0
			private val wet = 0.35 + mix.coerceIn(0.0, 1.0) * 0.65

			fun run(s: Double): Double {
				val norm = s / 32768.0
				val hot = norm * pre
				val clipped = hot.coerceIn(-1.0, 1.0)
				// Muerde sin ensordecer: mezcla seca/húmeda y vuelta
				// al nivel de entrada.
				return (norm * (1.0 - wet) + clipped * wet) * 32768.0 / (1.0 + (pre - 1.0) * 0.35)
			}
		}

		/** Wah funky: un pasa-bajos de un polo cuyo corte (500 Hz
		 *  a 2.8 kHz) sigue la envolvente de la voz con la
		 *  profundidad [mix] (0 a 1). Las sílabas fuertes abren el
		 *  filtro y suena el "uah" característico. Solo un seguidor
		 *  suave (ataque 5 ms, salida 200 ms) y un polo: estable
		 *  por construcción a cualquier tasa. */
		private class Wah(rateHz: Int, private val depth: Double) {
			private val r = rateHz.coerceAtLeast(8000).toDouble()
			private val attack = 1.0 - Math.exp(-1.0 / (0.005 * r))
			private val release = 1.0 - Math.exp(-1.0 / (0.2 * r))
			private var env = 0.0
			private var lp = 0.0
			private var started = false

			fun run(s: Double): Double {
				val norm = s / 32768.0
				if (!started) {
					started = true
					lp = norm
					return s
				}
				val level = Math.abs(norm)
				env += (level - env) * (if (level > env) attack else release)
				val cutoff = 500.0 + 2300.0 * env.coerceIn(0.0, 1.0) * depth.coerceIn(0.0, 1.0)
				val rc = 1.0 / (2.0 * Math.PI * cutoff)
				val dt = 1.0 / r
				val a = dt / (rc + dt)
				lp += a * (norm - lp)
				val wet = lp * 1.4
				return (norm * (1.0 - depth * 0.5) + wet * depth * 0.5) * 32768.0
			}
		}

		/** Phaser: barrido aireado tipo espacial. Retardo muy
		 *  corto (1 ms) modulado lento (0.35 Hz) con
		 *  realimentación media: el peine cae en agudos y suena
		 *  menos metálico que el flanger. Mezcla fija suave para
		 *  no ahogar la voz. */
		private class Phaser(rateHz: Int) {
			private val buf = DoubleArray((rateHz * 0.006).toInt().coerceAtLeast(32))
			private var at = 0
			private var phase = 0.0
			private val step = 2.0 * Math.PI * 0.35 / rateHz
			private val base = rateHz * 0.001
			private val swing = rateHz * 0.001

			fun run(s: Double): Double {
				phase += step
				if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI
				val delay = base + swing * (0.5 + 0.5 * Math.sin(phase))
				val back = at - delay
				val i0 = Math.floor(back).toInt()
				val frac = back - i0
				val size = buf.size
				val a = buf[(i0 % size + size) % size]
				val b = buf[((i0 + 1) % size + size) % size]
				val wet = a + (b - a) * frac
				buf[at] = s + wet * FEEDBACK
				at = (at + 1) % size
				return s * (1.0 - MIX) + wet * MIX
			}

			private companion object {
				const val FEEDBACK = 0.6
				const val MIX = 0.4
			}
		}

		/** Corte de graves: pasa-altos de un polo a ~90 Hz. Quita
		 *  retumbe y continua sin tocar la voz (la voz vive muy por
		 *  encima): ideal para altavoces pequeños y para limpiar la
		 *  mezcla cuando hay cuerpo o graves fuertes. */
		private class LowCut(rateHz: Int) {
			// 4.3: dos fallos aqui, y por eso el "quita retumbe" no
			// quitaba retumbe. El coeficiente estaba invertido (cortaba
			// a 5,3 kHz en vez de a 90) y la actualizacion era
			// `a * (lp + s - prev)`, que tiene ganancia cero en
			// continua: eso no es un paso bajo, es un diferenciador con
			// fuga, y `s - lp` sale casi igual que `s`. Con
			// `lp += a * (s - lp)` si es de verdad un paso bajo de un
			// polo, y entonces `s - lp` es el paso alto de 90 Hz.
			private val a = onePole(90.0, rateHz)
			private var lp = 0.0
			private var started = false

			fun run(s: Double): Double {
				if (!started) {
					started = true
					lp = s
					return s
				}
				lp += a * (s - lp)
				return s - lp
			}
		}

		/** Normalizador: nivela el volumen entre voces y frases con
		 *  un seguidor de envolvente lento (ataque ~10 ms, salida
		 *  ~1.2 s). La ganancia se mueve entre 0.5x y 3x buscando
		 *  picos de ~-9 dBFS y se congela en el silencio para no
		 *  inflar el ruido de fondo. */
		private class Normalizer(rateHz: Int) {
			private val r = rateHz.coerceAtLeast(8000).toDouble()
			private val attack = Math.exp(-1.0 / (0.01 * r))
			private val release = Math.exp(-1.0 / (1.2 * r))
			// Bajar es rápido (20 ms: un ataque fuerte tras un
			// silencio no llega a dispararse) y subir es lento
			// (300 ms: sin bombeo audible).
			private val fast = 1.0 - Math.exp(-1.0 / (0.02 * r))
			private val slow = 1.0 - Math.exp(-1.0 / (0.3 * r))
			private var env = 0.0
			private var gain = 1.0

			fun run(s: Double): Double {
				val level = Math.abs(s)
				env = if (level > env) attack * env + (1.0 - attack) * level
				else release * env + (1.0 - release) * level
				// Solo adapta con voz presente: en el silencio la
				// ganancia queda quieta y no sube el soplido.
				if (env > SILENCE) {
					val want = (TARGET / env).coerceIn(0.5, 3.0)
					gain += (want - gain) * (if (want < gain) fast else slow)
				}
				return s * gain
			}

			private companion object {
				const val TARGET = 14000.0
				const val SILENCE = 500.0
			}
		}

		/** Saturación suave: realza armónicos pares con tangente
		 *  hiperbólica para dar presencia en altavoces pequeños sin
		 *  subir el nivel (los pasajes flojos suben ~4 dB, los
		 *  fuertes quedan igual). Sin memoria: no mete ningún
		 *  transitorio de arranque. */
		private class Drive {
			fun run(s: Double): Double {
				val norm = s / 32768.0
				val sat = fastTanh(norm * 2.0)
				return (norm * 0.55 + sat * 0.55) * 32768.0
			}
		}

		/** Schroeder sencillo: cuatro peines en paralelo y dos
		 *  pasatodo en serie. [mix] de 0 a 1. */
		private class Reverb(rateHz: Int, private val mix: Double, sizePercent: Int = 50) {
			// 4.3: el tamano. Los cuatro peineos se estiran con el y
			// la realimentacion sube, que es lo que convierte una
			// habitacion en una catedral. Sin esto la reverberacion
			// era siempre la misma sala corta, y con un perfil de
			// efectos no habia manera de pedir una cola larga.
			private val room = 0.6 + (sizePercent.coerceIn(0, 100) / 100.0) * 1.4
			private val combs = Array(4) { k ->
				Comb((intArrayOf(297, 371, 411, 437)[k] * rateHz / 10000 * room).toInt().coerceAtLeast(64))
			}
			private val pass1 = AllPass((17 * rateHz / 10000).coerceAtLeast(32))
			private val pass2 = AllPass((7 * rateHz / 10000).coerceAtLeast(16))
			private val dry = 1.0 - mix * 0.45
			private val wetGain = mix * 0.9 / combs.size

			fun run(s: Double): Double {
				var wet = 0.0
				wet += combs[0].run(s)
				wet += combs[1].run(s)
				wet += combs[2].run(s)
				wet += combs[3].run(s)
				wet = pass1.run(wet)
				wet = pass2.run(wet)
				return s * dry + wet * wetGain
			}

			private class Comb(size: Int) {
				private val buf = DoubleArray(size)
				private var at = 0
				private var lp = 0.0
				fun run(s: Double): Double {
					val out = buf[at]
					// Amortiguación: los agudos decaen más rápido y
					// la cola suena cálida en vez de metálica.
					lp += DAMP * (out - lp)
					buf[at] = s + lp * FEEDBACK
					at = (at + 1) % buf.size
					return out
				}

				private companion object {
					const val FEEDBACK = 0.82
					const val DAMP = 0.25
				}
			}

			private class AllPass(size: Int) {
				private val buf = DoubleArray(size)
				private var at = 0
				fun run(s: Double): Double {
					val delayed = buf[at]
					val out = -s + delayed
					buf[at] = s + delayed * 0.5
					at = (at + 1) % buf.size
					return out
				}
			}
		}

		/**
		 * 4.3: cinta. Una linea de retardo cuya cabeza de lectura no
		 * va a paso constante, asi que la altura se mueve sola.
		 *
		 * No es el vibrato ([Vibrato]), que es una sinusoidal limpia y
		 * medida a proposito, ni el flanger, que barre con un ciclo
		 * lento. Aqui la deriva es un paseo: dos senos muy lentos y
		 * desiguales mas un termine que se mueve despacio hacia un
		 * objetivo nuevo cada medio segundo. Eso es lo que la hace
		 * sonar a cinta y no a efecto.
		 *
		 * Se lee con interpolacion lineal, asi que el retardo
		 * fraccionario no suena a escalon, y la realimentacion es
		 * corta para que las copias no se acumulen.
		 */
		private class Tape(rateHz: Int, private val mix: Double) {
			private val r = rateHz.coerceAtLeast(8000)
			private val size = (r * 0.05).toInt().coerceAtLeast(256)
			private val buf = DoubleArray(size)
			private var at = 0
			private var phase1 = 0.0
			private var phase2 = 0.0
			private var wander = 0.0
			private var wanderTarget = 0.0
			private var wanderLeft = 0
			private val step1 = 2.0 * Math.PI * 0.7 / r
			private val step2 = 2.0 * Math.PI * 0.23 / r
			private val maxDelay = size * 0.35
			/**
			 * 4.3: el paseo usaba `Math.random()`, y con eso dos
			 * cadenas iguales dan señales distintas. En el aparato
			 * no se nota (una cadena por frase, y el paseo cambia
			 * dos veces por segundo, más rápido que un vibrato, así
			 * que el oído no lo agarra), pero es una fuente de
			 * variación escondida: las pruebas medían una cosa y
			 * la siguiente medía otra, sin que se pudiera ver por
			 * qué.
			 *
			 * Con un LCG de semilla fija el paseo es igual de
			 * irreconocible y además se puede medir.
			 */
			private var seed = 0x5DEECE66DL

			private fun wander(): Double {
				seed = seed * 6364136223846793005L + 1442695040888963407L
				return ((seed ushr 33).toDouble() / 2147483648.0) - 1.0
			}
			fun run(s: Double): Double {
				phase1 += step1
				if (phase1 > 2.0 * Math.PI) phase1 -= 2.0 * Math.PI
				phase2 += step2
				if (phase2 > 2.0 * Math.PI) phase2 -= 2.0 * Math.PI
				// El paseo cambia de objetivo unas dos veces por
				// segundo: mas rapido que un LFO y mas lento que una
				// vibracion, que es donde no se oye el periodo.
				if (--wanderLeft <= 0) {
					wanderLeft = (r / 2).coerceAtLeast(1)
					wanderTarget = wander()
				}
				wander += (wanderTarget - wander) * 0.0008
				val depth = mix * 0.02 * r
				val delay = (size * 0.012 +
					depth * (0.5 * Math.sin(phase1) + 0.3 * Math.sin(phase2) + 0.4 * wander)
					).coerceIn(1.0, maxDelay)
				val back = at - delay
				val i0 = Math.floor(back).toInt()
				val frac = back - i0
				val a = buf[(i0 % size + size) % size]
				val b = buf[((i0 + 1) % size + size) % size]
				val delayed = a + (b - a) * frac
				val out = s * (1.0 - mix) + delayed * mix
				buf[at] = s + delayed * FEED
				at = (at + 1) % size
				return out
			}

			private companion object {
				const val FEED = 0.25
			}
		}


		/**
		 * 4.3: grave profundo. Un subarmónico de verdad bajo la voz.
		 *
		 * No es un ecualizador de graves ([Shelf.low]) ni una
		 * saturación de armónicos: es un oscilador a media
		 * frecuencia, al que se sigue el periodo de la fundamental de
		 * la voz.
		 *
		 * **Por qué un oscilador y no "reducir la banda a la
		 * mitad".** Eso es lo que se hizo dos veces y las dos no
		 * puede funcionar: quedarse con una muestra de cada dos deja
		 * el tono en hercios igual, porque lo que baja a la mitad es
		 * la frecuencia de muestreo, no el tono. Si además se vuelve a
		 * estirar a la misma tasa (que es lo obligatorio, porque la
		 * salida va a la tasa del motor) el tono vuelve a ser el de
		 * antes. Medido: con 120 Hz a la entrada, salía 120 Hz a la
		 * salida y nada a 60 Hz. Un subgrave no se puede sacar
		 * esperando; hay que generarlo.
		 *
		 * **Cómo se sigue el periodo.** La banda de la fundamental se
		 * filtra a [CROSSOVER] con dos polos y se mide el intervalo
		 * entre cruces de cero hacia abajo, que en una onda casi
		 * simétrica es el periodo. El intervalo se suaviza y solo se
		 * acepta si cae en el rango de una voz ([MIN_HZ] a
		 * [MAX_HZ]), así que un silencio o un ruido no lo descuadra.
		 *
		 * **Por qué el oscilador no se va de fase.** En cada cruce
		 * válido la fase se acerca un poco al cero más cercano, que es
		 * un múltiplo de pi, y no se pone a cero de golpe: el sub da
		 * media vuelta por periodo, así que reiniciarlo entero cada
		 * periodo lo deja en un diente de sierra a la fundamental, que
		 * es justo lo contrario de un subgrave. La corrección es
		 * suave y además cae donde la envolvente vale casi cero,
		 * porque la envolvente sigue a la banda filtrada y esa pasa
		 * por cero en el cruce; así no hay golpe al empezar ni entre
		 * frases.
		 *
		 * **Por qué dos polos.** Con uno solo el primer armónico
		 * (el doble de la fundamental) pasa casi entero, y el
		 * detector mide a armónicos en vez de a la fundamental, que
		 * es el error clásico de este efecto y suena a zumbido
		 * metálico. A 250 Hz con dos polos, el segundo armónico de
		 * una voz de 120 Hz (240 Hz) queda en el borde y el tercero
		 * (360 Hz) casi fuera.
		 */
		private class SubBass(rateHz: Int, private val mix: Double) {
			private val r = rateHz.coerceAtLeast(8000).toDouble()
			private val a = onePole(CROSSOVER, rateHz)
			/** Periodo medido, en muestras. Se suaviza para que un
			 *  error de uno no se oiga. */
			private var period = (r / 140.0).coerceIn(2.0, 100000.0)
			private val minPeriod = (r / MAX_HZ).coerceAtLeast(2.0)
			private val maxPeriod = (r / MIN_HZ).coerceAtLeast(minPeriod + 2.0)
			/** Envolvente de la banda: ataque rapido, salida lenta,
			 *  para que el sub no se oiga entre sílabas. */
			private val envAttack = 1.0 - Math.exp(-1.0 / (0.006 * r))
			private val envRelease = 1.0 - Math.exp(-1.0 / (0.10 * r))
			private var lp1 = 0.0
			private var lp2 = 0.0
			private var lastLp = 0.0
			private var env = 0.0
			private var phase = 0.0
			private var at = 0
			private var lastCross = -1

			fun run(s: Double): Double {
				val norm = s / 32768.0
				lp1 += a * (norm - lp1)
				lp2 += a * (lp1 - lp2)

				// Solo cuentan los cruces hacia abajo. Si tambien se
				// apunta en los de subida, entre dos marcas queda
				// media onda en vez de una onda, el periodo sale
				// partido por dos y el "subarmonico" sale por
				// encima de la fundamental: medido, 120 Hz de
				// entrada-producto 240 Hz de salida, que es justo
				// lo contrario de lo pedido.
				if (lastLp > 0.0 && lp2 <= 0.0) {
					if (lastCross >= 0) {
						val d = at - lastCross
						if (d.toDouble() in minPeriod..maxPeriod) {
							period += (d - period) * 0.30
							// El sub da media vuelta por periodo, asi
							// que en cada cruce deberia estar en un
							// cero, o sea en un multiplo de pi. Se
							// corrige un poco hacia ahi, no de golpe:
							// ponerlo a cero sin mas parte el
							// oscilador cada periodo, y entonces sale
							// un diente de sierra a la fundamental
							// en vez de la mitad. Medido: 100 Hz de
							// entrada producia picos en 200, 300, 400
							// y 500 Hz, que es el multiplo de la
							// fundamental, no su mitad.
							val zero = Math.round(phase / Math.PI) * Math.PI
							phase += (zero - phase) * 0.25
						}
					}
					lastCross = at
				}
				lastLp = lp2
				at++

				val level = Math.abs(lp2)
				env = if (level > env) envAttack * env + (1.0 - envAttack) * level
				else envRelease * env + (1.0 - envRelease) * level

				// pi por periodo es media vuelta por periodo: la
				// mitad Justamente.
				phase += Math.PI / period
				if (phase >= TWO_PI) phase -= TWO_PI
				// En silencio el sub se calla del todo; si no,
				// entre frases se oye un tono sin voz.
				val gate = if (env < SILENCE) 0.0 else env
				val sub = Math.sin(phase) * gate

				val amount = mix * GAIN
				// Se baja un poco la voz para que el sub tenga sitio,
				// pero no tanto que suene tapada.
				return (norm * (1.0 - amount * 0.12) + sub * amount) * 32768.0
			}

			private companion object {
				/** Hasta donde entra la fundamental de una voz. Dos
				 *  polos a 250 Hz dejan pasar lo justo. */
				const val CROSSOVER = 250.0
				/** Rango de fundamental que se sigue. Por debajo de 70
				 *  Hz ya no es una voz y por encima de 400 son
				 *  armónicos sueltos, no la fundamental. */
				const val MIN_HZ = 70.0
				const val MAX_HZ = 400.0
				/** Cuanto sube el sub sobre la banda. A 1,0 ya se oye
				 *  claro; de mas queda un bombo. */
				const val GAIN = 1.6
				const val TWO_PI = 2.0 * Math.PI
				/** Por debajo de esto no hay voz, y el sub se calla. */
				const val SILENCE = 0.002
			}
		}
	}
}
