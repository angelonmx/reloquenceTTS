package com.angelonmx.reloquence

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import timber.log.Timber

/**
 * Un motor propio para la pantalla de ajustes, y un lugar
 * para escucharlo.
 *
 * Ajustar una voz escribiendo un ajuste, yendose, y esperando
 * a que el lector de pantalla diga otra cosa en ella no es
 * ajustar. Así que la pantalla de ajustes conserva una
 * instancia propia y habla en un AudioTrack, que es el único
 * lugar en este programa que reproduce audio propio: el motor
 * con el que TalkBack habla entrega sus muestras a Android y
 * nunca ve un dispositivo.
 *
 * Todo corre en un solo hilo de fondo, así que una vista
 * previa pedida mientras otra está hablando la reemplaza en
 * lugar de solaparse.
 */
class EvvPreview(private val language: Int) {

	private val thread = HandlerThread("evv-preview").apply { start() }
	private val work = Handler(thread.looper)

	@Volatile
	private var engine: EvvEngine? = null

	@Volatile
	private var track: AudioTrack? = null

	@Volatile
	private var generation = 0

	@Volatile
	private var taughtWords: List<Pair<String, String>>? = null

	/** Cuántos bytes de audio han ido al track. Nada en la
	 *  app lo lee; es lo que deja decir a una prueba que la
	 *  vista previa realmente sonó, lo cual es útil porque una
	 *  vista previa que reproduce nada en silencio se ve exactamente
	 *  como una que funciona. */
	@Volatile
	var bytesPlayed: Long = 0L
		private set

	/** Si una muestra aún se está hablando, y si la última se
	 *  escuchó completa. Nada en la app lee ninguno de los
	 *  dos; son lo que deja decir a una prueba que la cola no se
	 *  cortó, lo cual es invisible desde fuera porque cada byte
	 *  fue entregado de todas formas. */
	@Volatile
	var playing: Boolean = false
		private set

	@Volatile
	var drained: Boolean = false
		private set

	/** Lee lo que tiene un preset ajustado. Contesta un mapa
	 *  vacío hasta que el motor ha abierto, que es lo primero
	 *  que hace el hilo. */
	fun presetShape(index: Int): Map<Int, Int> = engine?.presetShape(index) ?: emptyMap()

	/** Lee un parámetro de la voz actual de la vista previa. */
	fun getVoiceParam(param: Int): Int = engine?.getVoiceParam(param) ?: -1

	fun open(then: (() -> Unit)? = null) {
		work.post {
			if (engine == null) engine = EvvEngine.open(language, Eci.sampleRateHz(1))
			then?.let { Handler(android.os.Looper.getMainLooper()).post(it) }
		}
	}

	/** Dice [texto] en [voz] con [forma] encima, al [volumen]
	 *  pedido y con [words] enseñadas, deteniendo lo que se
	 *  estuviera diciendo. Con [fx] la muestra suena como hablará:
	 *  ecualizador y reverb cuando están en vigor. La sustitución
	 *  del diccionario se hace aquí con la voz ya limpia, dejando
	 *  detrás lo que la devuelve: los códigos de una palabra no se
	 *  pegan en lo que sigue. */
	fun say(
		text: String, voice: Int, shape: Map<Int, Int>, rateHz: Int,
		volume: Int, words: List<Pair<String, String>>, fx: AudioFx.Chain? = null,
		prosody: String = "", ignoreTags: Boolean = false,
		useSystemSpeed: Boolean = true
	) {
		// Lo que se estaba hablando se detiene primero, y
		// el turno al que pertenece esta emisión se toma después
		// -- detenerse es lo que mueve el contador hacia
		// adelante, así que leerlo primero dejaría esta emisión
		// sosteniendo un número que ya es viejo y nunca se
		// hablaría.
		stopSpeaking()
		val mine = generation
		work.post {
			if (mine != generation) return@post
			val e = engine ?: EvvEngine.open(language, Eci.sampleRateHz(1))?.also { engine = it } ?: return@post
			e.setSampleRate(rateHz)
			// Lo enseñado nuevo reemplaza a lo anterior: sin olvidar,
			// una palabra borrada seguiría hablándose en la muestra.
			if (taughtWords != words) {
				e.forgetDictionaries()
				// Listas grandes en forma simple: la sustitución en
				// texto ya cubre mayúsculas y 4 formas por miles de
				// palabras tardaría segundos en arrancar la muestra.
				if (words.size > 200) {
					for ((key, sayWord) in words) {
						Dictionaries.teachSingle(e, Eci.DICT_MAIN, key, sayWord)
					}
				} else {
					for ((key, sayWord) in words) {
						Dictionaries.teachEntry(e, Eci.DICT_MAIN, key, sayWord)
					}
				}
				taughtWords = words.toList()
			}
			val plan = VoicePlan.plan(
				voice = voice,
				shape = shape,
				preset = e.presetShape(voice),
				requestRate = 100,
				requestPitch = 100,
				useSystemSpeed = useSystemSpeed,
				volumeGain = volume
			)
			when (Emission.speak(e, plan, text) { reset ->
				// 4.3: con el interruptor de ignorar códigos encendido,
				// la muestra suena exactamente como va a sonar el
				// servicio: sin reinicio delante (no hay códigos que
				// neutralizar) y con el texto limpio de anotaciones.
				val sub = Dictionaries.substitute(
					text, words, if (ignoreTags) prosody else reset + prosody
				)
				// Sin cola de reseteo al final: cada muestra re-aplica
				// la voz entera antes de hablar, así que no hay nada
				// que deshacer, y la cola tras el punto final confunde
				// la entonación. Los códigos del usuario mandan.
				val said = if (ignoreTags) Prosody.stripVoiceCodes(sub) else sub
				if (said.isBlank()) "" else if (ignoreTags) said else reset + prosody + said
			}) {
				Emission.Result.Spoken -> play(e, mine, fx)
				Emission.Result.Empty -> Unit
				Emission.Result.Rejected -> Timber.e("el motor rechazó la muestra")
				Emission.Result.NotApplied -> Timber.e("no se pudo aplicar la voz de la muestra")
			}
		}
	}

	/** Dice por turnos para la muestra del modo alternar:
	 *  cada [Triple] trae voz, forma y texto ya con pausas.
	 *  Todo corre en una sola pasada del hilo, un turno tras
	 *  otro con su propia voz aplicada. */
	fun sayAlternating(
		segments: List<Triple<Int, Map<Int, Int>, String>>, rateHz: Int,
		volume: Int, words: List<Pair<String, String>>, fx: AudioFx.Chain? = null,
		prosody: String = "", ignoreTags: Boolean = false,
		useSystemSpeed: Boolean = true
	) {
		if (segments.isEmpty()) return
		stopSpeaking()
		val mine = generation
		work.post {
			if (mine != generation) return@post
			val e = engine ?: EvvEngine.open(language, Eci.sampleRateHz(1))?.also { engine = it } ?: return@post
			e.setSampleRate(rateHz)
			if (taughtWords != words) {
				e.forgetDictionaries()
				if (words.size > 200) {
					for ((key, sayWord) in words) {
						Dictionaries.teachSingle(e, Eci.DICT_MAIN, key, sayWord)
					}
				} else {
					for ((key, sayWord) in words) {
						Dictionaries.teachEntry(e, Eci.DICT_MAIN, key, sayWord)
					}
				}
				taughtWords = words.toList()
			}
			for ((at, triple) in segments.withIndex()) {
				val (voice, shape, text) = triple
				if (mine != generation) return@post
				val plan = VoicePlan.plan(
					voice = voice,
					shape = shape,
					preset = e.presetShape(voice),
					requestRate = 100,
					requestPitch = 100,
					useSystemSpeed = useSystemSpeed,
					volumeGain = volume
				)
				// 4.3: ver [say]; mismo criterio que el servicio.
				when (Emission.speak(e, plan, text) { reset ->
					val sub = Dictionaries.substitute(
						text, words, if (ignoreTags) prosody else reset + prosody
					)
					val said = if (ignoreTags) Prosody.stripVoiceCodes(sub) else sub
					if (said.isBlank()) "" else if (ignoreTags) said else reset + prosody + said
				}) {
					Emission.Result.Spoken -> play(e, mine, fx)
					Emission.Result.Empty -> Unit
					Emission.Result.Rejected -> {
						Timber.e("el motor rechazó la muestra alterna")
						return@post
					}
					Emission.Result.NotApplied -> {
						Timber.e("no se pudo aplicar la voz alterna de la muestra")
						return@post
					}
				}
			}
		}
	}

	private fun play(e: EvvEngine, mine: Int, fx: AudioFx.Chain? = null) {		playing = true
		drained = false
		val rate = e.sampleRateHz
		val minimum = AudioTrack.getMinBufferSize(
			rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
		)
		val size = if (minimum > 0) maxOf(minimum, CHUNK * 4) else CHUNK * 4
		// 4.3: el track se pide con tres intentos, porque el Builder
		// falla en mas aparatos de los que deberia, y antes eso
		// significaba una muestra en silencio sin mas explicacion.
		// Primero el Builder con la tasa pedida; si el aparato la
		// rechaza, el constructor de toda la vida, que es mas
		// indulgente; y si tampoco, a la tasa que el propio aparato
		// dice que usa, que es donde la mayoria de las
		// implementaciones de HAL resuelven la conversion. Con eso, una
		// marca que no acepta 48 kHz sigue escuchando la muestra en vez
		// de callar.
		val t = openTrack(rate, size) ?: run {
			Timber.e("el aparato no dio pista de audio para la muestra")
			playing = false
			return
		}
		// 4.3: si el aparato ha obligado a otra tasa, de esa hay que
		// medir el fundido y el vaciado. Con la del motor, el fundido
		// saldria mal y el "suena entero" mintiria.
		val out = t.sampleRate.takeIf { it > 0 } ?: rate
		track = t
		runCatching { t.play() }.onFailure {
			Timber.e(it, "el AudioTrack no pudo arrancar")
			runCatching { t.release() }
			if (track === t) track = null
			playing = false
			return
		}
		val buffer = ByteArray(CHUNK)
		val gain = e.volumeGain
		var written = 0L
		var first = true
		while (mine == generation) {
			val n = e.read(buffer)
			if (n <= 0) break
			// Fundido de arranque (15 ms, igual potencia): sin esto
			// el transitorio de los filtros sonaba como un
			// golpecito al empezar a hablar, sobre todo a
			// 44k/48k con el HD fuerte.
			if (first) {
				first = false
				AudioFx.fadeIn(buffer, n, out)
			}
			AudioFx.gain(buffer, n, gain)
			fx?.process(buffer, n)
			var at = 0
			while (at < n && mine == generation) {
				val wrote = t.write(buffer, at, n - at)
				if (wrote <= 0) break
				at += wrote
				bytesPlayed += wrote
				written += wrote / BYTES_PER_FRAME
			}
		}
		if (mine == generation) {
			// Se esperó antes de detener en lugar de después.
			// El track sostiene la mayoría de un segundo, que es
			// las últimas palabras de una muestra corta, y
			// release() la corta; stop() la dejaría sonar pero
			// también pondría la cabeza de reproducción a cero,
			// así que no quedaría nada para ver. Así que esto
			// espera a que la cabeza llegue al final de lo
			// escrito mientras el track sigue corriendo.
			drained = waitToDrain(t, mine, written, out)
			runCatching { t.stop() }
		} else {
			runCatching { t.pause(); t.flush(); t.stop() }
		}
		runCatching { t.release() }
		if (track === t) track = null
		playing = false
	}

	/**
	 * 4.3: una pista de audio monofono PCM a [rate], o nada si el
	 * aparato no deja.
	 *
	 * Tres maneras, de la que mejor se les da a la que peor: el
	 * Builder (la de ahora, la unica que admite atributos de audio
	 * decentes), el constructor viejo (mas indulgente con tasas raras)
	 * y, en ultimo caso, la tasa que el propio aparato declara, que
	 * es donde la mayoria de las implementaciones de HAL resuelven la
	 * conversion. Con una sola, un modelo antiguo o una marca con un
	 * HAL caprichoso se quedaba sin muestras y sin decir nada.
	 */
	private fun openTrack(rate: Int, size: Int): AudioTrack? {
		runCatching {
			AudioTrack.Builder()
				.setAudioAttributes(
					AudioAttributes.Builder()
						.setUsage(AudioAttributes.USAGE_ASSISTANT)
						.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
						.build()
				)
				.setAudioFormat(
					AudioFormat.Builder()
						.setEncoding(AudioFormat.ENCODING_PCM_16BIT)
						.setSampleRate(rate)
						.setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
						.build()
				)
				.setBufferSizeInBytes(size)
				.setTransferMode(AudioTrack.MODE_STREAM)
				.build()
		}.getOrNull()?.let { return it }
		// El constructor con atributos, que dentro de la plataforma es
		// un camino distinto al Builder y acepta tasas que el Builder
		// rehúsa.
		runCatching {
			AudioTrack(
				AudioAttributes.Builder()
					.setUsage(AudioAttributes.USAGE_ASSISTANT)
					.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
					.build(),
				AudioFormat.Builder()
					.setEncoding(AudioFormat.ENCODING_PCM_16BIT)
					.setSampleRate(rate)
					.setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
					.build(),
				size, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
			)
		}.getOrNull()?.let { return it }
		// El aparato dice cual es la suya, y a esa se conforma todo el
		// mundo: es la tasa para la que el HAL tiene ruta.
		val native = runCatching {
			AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
		}.getOrDefault(0)
		if (native <= 0 || native == rate) {
			Timber.e("el aparato no tiene salida de audio para la muestra")
			return null
		}
		Timber.i("la muestra suena a $native Hz en vez de a $rate")
		return runCatching {
			AudioTrack.Builder()
				.setAudioAttributes(
					AudioAttributes.Builder()
						.setUsage(AudioAttributes.USAGE_ASSISTANT)
						.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
						.build()
				)
				.setAudioFormat(
					AudioFormat.Builder()
						.setEncoding(AudioFormat.ENCODING_PCM_16BIT)
						.setSampleRate(native)
						.setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
						.build()
				)
				.setBufferSizeInBytes(size * native / rate.coerceAtLeast(1))
				.setTransferMode(AudioTrack.MODE_STREAM)
				.build()
		}.getOrNull()
	}

	/** Contesta si todo lo escrito se escuchó realmente. */
	private fun waitToDrain(t: AudioTrack, mine: Int, frames: Long, rate: Int): Boolean {
		if (frames <= 0L) return true
		// Tenga el tiempo que tenga el audio, más un margen,
		// para que un track que deja de reportar no pueda
		// mantener este hilo para siempre.
		val giveUp = System.currentTimeMillis() + frames * 1000L / rate + MARGIN_MS
		while (mine == generation && System.currentTimeMillis() < giveUp) {
			val head = runCatching { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }
				.getOrElse { return false }
			if (head >= frames) return true
			try {
				Thread.sleep(SIP_MS)
			} catch (stopping: InterruptedException) {
				Thread.currentThread().interrupt()
				return false
			}
		}
		return false
	}

	fun stopSpeaking() {
		generation++
		engine?.stop()
		runCatching { track?.pause(); track?.flush() }
	}

	fun close() {
		stopSpeaking()
		work.post {
			engine?.close()
			engine = null
			thread.quitSafely()
		}
	}

	private companion object {
		const val CHUNK = 4096
		const val BYTES_PER_FRAME = 2
		const val MARGIN_MS = 750L
		const val SIP_MS = 20L
	}
}
