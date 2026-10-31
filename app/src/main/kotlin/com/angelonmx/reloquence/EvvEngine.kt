package com.angelonmx.reloquence

import timber.log.Timber

/**
 * Una instancia del motor y los ajustes en vigor sobre ella.
 *
 * Todo excepto [stop] se llama desde el hilo de síntesis. [stop]
 * viene de cualquier hilo que lo pida, que es para lo que está
 * escrito el lado nativo.
 */
class EvvEngine internal constructor(
	private var handle: Long,
	val language: Int,
	private val desiredSampleRateHz: Int,
	private val native: EvvBridge = EvvNative
) : Emission.Target {

	/**
	 * 5.4: identidad única dentro del proceso para el diagnóstico.
	 *
	 * Antes se usaba `System.identityHashCode`, que es la dirección
	 * del objeto: al abrir y cerrar motores el mismo hueco se reusa y
	 * varias instancias distintas salían con el mismo número, así que
	 * el registro no dejaba seguir una instancia. Un contador que
	 * solo sube no se repite.
	 */
	val id: Long = ids.incrementAndGet()

	// 4.2: un Object de verdad y no un Any, porque [close] se apoya
	// en wait/notifyAll para que ningún lector se quede dentro
	// mientras se suelta la memoria.
	private val guard = Object()

	@Volatile
	private var closed = false

	/**
	 * 4.2: cuántas llamadas a [read] están dentro del puente ahora
	 * mismo. [close] espera a que llegue a cero antes de soltar la
	 * memoria, y [read] lo sube antes de entrar, que es la ventana
	 * que un `if (closed)` no cubría.
	 */
	private var readers = 0

	var sampleRateHz: Int = desiredSampleRateHz
		private set

	/** La velocidad a la que la voz fue puesta antes de que la
	 *  velocidad y el tono de Android se escalaran sobre ella,
	 *  para que una velocidad del 100% sea la voz tal como está
	 *  en vez de lo que dejó la última emisión. */
	private var baseSpeed = -1
	private var basePitch = -1
	private var baseFluct = -1
	private var baseHead = -1
	private var baseRough = -1
	private var baseBreath = -1
	private var baseGender = -1
	private var curVolume = -1

	/** El preset del motor (1..8) que [applyEmission] dejó copiado
	 *  sobre la voz en juego. Cero es "todavía no se aplicó ninguno".
	 *
	 *  5.4.2: se guarda ya en la numeración del motor, no en la de la
	 *  app. Antes se guardaba el índice de [VoicePlan] (0..7) y se
	 *  copiaba una voz distinta de la elegida.
	 *
	 *  5.4.4: [prosodyReset] ya **no** anuncia este número. El `` `vN ``
	 *  delante de cada trozo pasaba por `changeVoiceRun`, que vacía lo
	 *  que queda en cola, rehace el conversor de audio y cambia el
	 *  romanizador; eso era lo que hacía sonar cada frase como una
	 *  exclamación. La identidad se aplica una sola vez con
	 *  [EvvBridge.copyVoice], que no dispara ese recorrido. */
	private var currentVoice = 0

	/**
	 * 5.4.4: el texto hablado traía un `` `vN `` propio (del usuario o
	 *  de una entrada de diccionario) y dejó la voz del motor en otro
	 *  preset. La próxima [applyEmission] tiene que volver a copiar el
	 *  preset del plan aunque sea el mismo que [currentVoice] recuerda.
	 *
	 * Se marca en [speak], que es el único punto por el que entra el
	 * texto final: el reinicio que arma esta app ya no lleva `` `vN ``,
	 * así que lo que case aquí es una selección de voz de verdad.
	 */
	private var voiceChangedInline = false

	/** Lo que la velocidad y el tono pedidos por última vez dan en
	 *  los números propios del motor, enviados por delante de cada
	 *  emisión. Debajo de cero hasta que se ha aplicado una voz,
	 *  cuando no hay base a la que escalar. */
	private var wantSpeed = -1
	private var wantPitch = -1

	companion object {
		/** Cada cuánto mira [close] si ya no queda ningún lector
		 *  dentro, y hasta cuándo espera. */
		private const val CLOSE_STEP_MS = 20L
		private const val CLOSE_WAIT_MS = 2000L

		/** 5.4: contador de instancias del proceso, para el registro. */
		private val ids = java.util.concurrent.atomic.AtomicLong()

		/**
		 * 4.2: lo que contesta [read] cuando el motor se quedó
		 * parado, que es distinto de una parada. Diez segundos sin
		 * una sola muestra (el motor entrega marcos de 128 ms) no es
		 * una emisión lenta. Quien lo lee suelta la instancia y
		 * abre otra en la siguiente emisión, en vez de quedarse con
		 * un motor muerto.
		 */
		const val WEDGED = -2

		val available: IntArray by lazy {
			if (!EvvNative.loaded) IntArray(0) else runCatching { EvvNative.languages() }
				.onFailure { Timber.e(it, "no se pudo pedir los idiomas") }
				.getOrDefault(IntArray(0))
		}

		val version: String by lazy {
			if (!EvvNative.loaded) "" else runCatching { EvvNative.version() }.getOrDefault("")
		}

		/**
		 * Hace una instancia hablando [language], que es una de las
		 * palabras que eciGetAvailableLanguages contestó y nada más.
		 *
		 * [desiredSampleRateHz] es la tasa de muestreo real que el
		 * usuario quiere. Se configura de inmediato, sin pasar por
		 * la tasa nativa del motor primero.
		 */
		fun open(language: Int, desiredSampleRateHz: Int = Eci.sampleRateHz(1)): EvvEngine? {
			if (!EvvNative.loaded) {
				Timber.e(EvvNative.loadError, "la biblioteca nativa no se cargó")
				return null
			}
			// 4.3: el punto único por el que pasa todo, y la última
			// puerta antes del motor. Abrir un idioma que este binario
			// no trae no es un error devuelto: es una manija rota que
			// revienta más adentro, en el hilo de síntesis, y el
			// proceso se muere. Android reinicia el servicio y quien
			// lo ve solo ve que la voz se para y vuelve, que no dice
			// nada de por qué.
			//
			// Se sube al primer idioma declarado en vez de negarse,
			// porque la alternativa es el silencio.
			var want = language
			if (want == 0 || !available.contains(want)) {
				want = available.firstOrNull() ?: run {
					Timber.e("este binario no trae ningún idioma")
					return null
				}
				Timber.e("0x%08x no está en este motor; se abre 0x%08x", language, want)
			}
			val handle = EvvNative.create(want)
			if (handle == 0L) {
				Timber.e("no hay instancia del motor para el idioma 0x%08x".format(want))
				return null
			}
			// `want` y no `language`: el campo tiene que decir el idioma
			// con el que se ha abierto de verdad, o la caché de motores
			// compararía contra un idioma que nadie está hablando y
			// abriría otro en cada frase.
			return EvvEngine(handle, want, desiredSampleRateHz).apply { configure() }
		}
	}

	private fun configure() {
		// Primero el idioma/dialecto, como la referencia: la
		// instancia nace con él pero se deja asentado igual.
		native.setParam(handle, Eci.PARAM_LANGUAGE_DIALECT, language)
		// Primero todo lo demás, y al final la tasa de muestreo,
		// para que el motor la tome desde el principio.
		native.setParam(handle, Eci.PARAM_SYNTH_MODE, 0)
		native.setParam(handle, Eci.PARAM_REAL_WORLD_UNITS, 0)
		native.setParam(handle, Eci.PARAM_INPUT_TYPE, 1)
		// 4.2: el modo de lectura del texto y el de los números se
		// afirman aquí y en cada aplicación de ajustes. El motor
		// tiene cuatro modos de texto y dos de número, y el que trae
		// no siempre es "léelo como texto": hay uno que deletrea.
		// Como un `` `v `` mueve el estado del motor, a partir de un
		// cambio de voz un "1." suelto o un punto aislado se leían
		// como letra o como signo, y era el nivel de puntuación
		// NINGUNA el que se veía manchado por un código puesto
		// treinta palabras antes.
		native.setParam(handle, Eci.PARAM_TEXT_MODE, Eci.TEXT_MODE_PLAIN)
		native.setParam(handle, Eci.PARAM_NUMBER_MODE, Eci.NUMBER_MODE_PLAIN)
		// Configurar la tasa de muestreo deseada de inmediato,
		// antes de que el motor empiece a procesar.
		val index = Eci.sampleRateIndex(desiredSampleRateHz)
		native.setParam(handle, Eci.PARAM_SAMPLE_RATE, index)
		sampleRateHz = Eci.sampleRateHz(index)
	}

	fun setSampleRate(hz: Int) = synchronized(guard) {
		if (closed) return
		val index = Eci.sampleRateIndex(hz)
		native.setParam(handle, Eci.PARAM_SAMPLE_RATE, index)
		sampleRateHz = Eci.sampleRateHz(index)
	}

	/** El diccionario de abreviaturas, que es lo que eciDictionary conmuta.
	 *  Cero lo enciende: ese es el sentido propio del motor y no un error aquí. */
	fun setAbbreviations(on: Boolean) = synchronized(guard) {
		if (!closed) native.setParam(handle, Eci.PARAM_DICTIONARY, if (on) 0 else 1)
	}

	/** Qué preset [index] (basado en 0, así que 0 es Reed) está
	 *  ajustado a, sin cambiar la voz que se está hablando.
	 *  Esto es lo que muestra la pantalla de ajustes, así que un
	 *  deslizador empieza en el número propio de la voz en vez de
	 *  en uno nuestro. */
	fun presetShape(index: Int): Map<Int, Int> = synchronized(guard) {
		if (closed) return emptyMap()
		val preset = Eci.enginePreset(index)
		val out = LinkedHashMap<Int, Int>()
		for (param in 0 until Eci.NUM_VOICE_PARAMS) {
			val value = native.getVoiceParam(handle, preset, param)
			if (value >= 0) out[param] = value
		}
		return out
	}

	/**
	 * Escribe **los ocho** parámetros de [plan] en la voz actual,
	 * después de copiar sobre ella el preset que pide el plan (solo
	 * cuando cambia, o cuando un `` `vN `` inline la dejó en otro).
	 *
	 * No deja ningún hueco: el plan ya viene completo (ver
	 * [VoicePlan]), así que lo que no se escribe aquí no es "lo que
	 * hubiera antes". Es la diferencia entre una emisión determinista
	 * y la voz que cambiaba tras reiniciar el servicio.
	 *
	 * El motor rechaza escrituras mientras está hablando y contesta
	 * `-1`; por eso devuelve falso y el llamante reintenta. Después de
	 * escribir se lee de vuelta: si algo no quedó en un valor válido,
	 * la emisión no se habla. Nunca se deja un `-1` para `speak`.
	 */
	override fun applyEmission(plan: VoicePlan.Plan): Boolean = synchronized(guard) {
		if (closed) return false
		if (!plan.isComplete) return false
		// 5.4: si el plan pide un preset distinto de la voz en juego,
		// cópialo sobre la voz actual para que OI_VOICENO cambie y
		// todo se reenvíe (vc_copyVoice pone OI_VOICENO, OI_PREV_VOICENO
		// y OI_READY). Los ocho parámetros se escriben después, sobre
		// esa voz ya seleccionada: primero la identidad, luego los
		// valores, que es el orden que el motor espera.
		//
		// 5.4.2: el plan numera desde cero y el motor desde uno. La
		// conversión se hace aquí y se guarda ya convertida.
		//
		// 5.4.4: también se vuelve a copiar si el texto de la emisión
		// anterior traía un `` `vN `` propio y dejó otra voz puesta.
		// Sin esto, la identidad se quedaba cambiada hasta reiniciar
		// el servicio aunque los ocho parámetros ya fueran los del
		// plan. [EvvBridge.copyVoice] es el camino barato: no pasa por
		// changeVoiceRun ni corta el audio en cola.
		val preset = Eci.enginePreset(plan.voice)
		if (preset != currentVoice || voiceChangedInline) {
			if (native.copyVoice(handle, preset, Eci.VOICE_CURRENT) < 0) return false
			currentVoice = preset
			voiceChangedInline = false
		}
		for (param in Eci.SHAPE) {
			val value = plan.value(param)
			if (value < 0) return false
			if (native.setVoiceParam(
					handle, Eci.VOICE_CURRENT, param, Eci.clampVoice(param, value)
				) < 0
			) return false
		}
		// Leer nunca se rechaza, solo escribir, así que esto es lo que
		// el motor tiene de verdad. Si alguna lectura no es válida, no
		// se habla: es la última puerta antes del `-1`.
		val speed = native.getVoiceParam(handle, Eci.VOICE_CURRENT, Eci.VOICE_SPEED)
		val pitch = native.getVoiceParam(handle, Eci.VOICE_CURRENT, Eci.VOICE_PITCH_BASELINE)
		val fluct = native.getVoiceParam(handle, Eci.VOICE_CURRENT, Eci.VOICE_PITCH_FLUCTUATION)
		val head = native.getVoiceParam(handle, Eci.VOICE_CURRENT, Eci.VOICE_HEAD_SIZE)
		val rough = native.getVoiceParam(handle, Eci.VOICE_CURRENT, Eci.VOICE_ROUGHNESS)
		val breath = native.getVoiceParam(handle, Eci.VOICE_CURRENT, Eci.VOICE_BREATHINESS)
		val gender = native.getVoiceParam(handle, Eci.VOICE_CURRENT, Eci.VOICE_GENDER)
		val volume = native.getVoiceParam(handle, Eci.VOICE_CURRENT, Eci.VOICE_VOLUME)
		if (speed < 0 || pitch < 0 || fluct < 0 || head < 0 ||
			rough < 0 || breath < 0 || gender < 0 || volume < 0
		) return false
		baseSpeed = speed
		basePitch = pitch
		baseFluct = fluct
		baseHead = head
		baseRough = rough
		baseBreath = breath
		baseGender = gender
		curVolume = volume.coerceIn(0, Eci.VOLUME_MAX)
		wantSpeed = baseSpeed
		wantPitch = basePitch
		// 5.3: esto es lo que el motor tiene de verdad tras aplicar el
		// plan (los valores leídos de vuelta, no los que se pidieron).
		// Se registra para poder comparar antes/después de cada emisión
		// y detectar si el motor se quedó en un estado distinto al del
		// plan.
		Timber.i(
			"applyEmission aplicado: engine=%d voice=%d speed=%d pitch=%d fluct=%d head=%d rough=%d breath=%d gender=%d volume=%d wantSpeed=%d wantPitch=%d",
			id, currentVoice, speed, pitch, fluct, head, rough, breath, gender, volume,
			wantSpeed, wantPitch
		)
		return true
	}

	/**
	 * Restaura la prosodia de la emisión con **los ocho** parámetros
	 * aplicados: velocidad y tono de lo pedido, y el resto tal como
	 * [applyEmission] los dejó. Sin esto un `` `vb `` en una palabra
	 * se queda pegado en lo que sigue, porque el motor lo aplica desde
	 * ese punto en adelante.
	 *
	 * Los códigos son los del motor (eci_text.c): `vs `vb `vf
	 * `vh `vr `vy `vv `vg.
	 *
	 * 5.4.4: **no** se anuncia `` `vN ``. Ese código no es un
	 * parámetro: pasa por `changeVoiceRun`, que corta lo que queda en
	 * cola, rehace el conversor de audio y cambia el romanizador. Con
	 * uno por trozo, cada frase salía con un ataque y una entonación
	 * distintos: la voz "exclamaba" todo aunque los ocho valores
	 * fueran los del usuario. La identidad ya la deja puesta
	 * [applyEmission] con [EvvBridge.copyVoice], y solo se repite si
	 * el texto trajo un `` `vN `` propio ([voiceChangedInline]).
	 *
	 * Si algún valor no es válido, contesta vacío. El llamante
	 * ([Emission]) lo trata como "no se puede hablar": es preferible
	 * una emisión pospuesta a una emisión con `-1`.
	 */
	override fun prosodyReset(): String = synchronized(guard) {
		if (closed) return ""
		if (wantSpeed < 0 || wantPitch < 0 || baseFluct < 0 || baseHead < 0 ||
			baseRough < 0 || baseBreath < 0 || curVolume < 0 || baseGender < 0
		) return ""
		buildString {
			append("`vs").append(wantSpeed).append(' ')
			append("`vb").append(wantPitch).append(' ')
			append("`vf").append(baseFluct).append(' ')
			append("`vh").append(baseHead).append(' ')
			append("`vr").append(baseRough).append(' ')
			append("`vy").append(baseBreath).append(' ')
			append("`vv").append(curVolume).append(' ')
			append("`vg").append(baseGender).append(' ')
		}
	}

	/**
	 * Restablece el estado de prosodia del motor tras una [stop].
	 *
	 * Vuelve a escribir los ocho parámetros base que dejó la última
	 * [applyEmission] sobre la voz en juego, para que una emisión
	 * cortada a mitad de una inflexión (pregunta, exclamación, pausa)
	 * no deje residuo (tono, velocidad o inflexión pegados) en la
	 * siguiente. No cambia la voz seleccionada ni el diccionario.
	 *
	 * Aplicarlo varias veces es inofensivo: reafirma los mismos ocho
	 * valores que ya tiene el motor, así que no acumula estado.
	 */
	fun resetProsody() = synchronized(guard) {
		if (closed) return
		if (baseSpeed >= 0) native.setVoiceParam(handle, Eci.VOICE_CURRENT, Eci.VOICE_SPEED, baseSpeed)
		if (basePitch >= 0) native.setVoiceParam(handle, Eci.VOICE_CURRENT, Eci.VOICE_PITCH_BASELINE, basePitch)
		if (baseFluct >= 0) native.setVoiceParam(handle, Eci.VOICE_CURRENT, Eci.VOICE_PITCH_FLUCTUATION, baseFluct)
		if (baseHead >= 0) native.setVoiceParam(handle, Eci.VOICE_CURRENT, Eci.VOICE_HEAD_SIZE, baseHead)
		if (baseRough >= 0) native.setVoiceParam(handle, Eci.VOICE_CURRENT, Eci.VOICE_ROUGHNESS, baseRough)
		if (baseBreath >= 0) native.setVoiceParam(handle, Eci.VOICE_CURRENT, Eci.VOICE_BREATHINESS, baseBreath)
		if (baseGender >= 0) native.setVoiceParam(handle, Eci.VOICE_CURRENT, Eci.VOICE_GENDER, baseGender)
		if (curVolume >= 0) native.setVoiceParam(handle, Eci.VOICE_CURRENT, Eci.VOICE_VOLUME, curVolume)
		wantSpeed = baseSpeed
		wantPitch = basePitch
	}

	/** Android entrega la velocidad y el tono como porcentaje de lo
	 *  normal, donde el motor quiere sus propios números. Lo normal
	 *  es la voz tal como [applyEmission] la dejó, así que 100 no
	 *  cambia nada. Ninguno de los dos se escribe: [prosodyReset] los
	 *  envía por delante de las palabras. */
	override fun setRatePercent(percent: Int) = synchronized(guard) {
		if (closed || baseSpeed < 0) return
		// Cero o negativo no es "habla muy despacio": es que el
		// llamante no mandó tasa. Android lo deja en 1.0 (100) casi
		// siempre, pero no siempre, y velocidad 0 en el motor es un
		// arrastre que suena cantado y que el usuario no eligió.
		// Sin base propia, la del sistema manda tal cual, hasta el
		// tope que ya tiene el deslizador de la aplicación.
		wantSpeed = SpeechRate.speedForPercent(
			baseSpeed,
			VoicePlan.sanePercent(percent).coerceAtMost(VoicePlan.MAX_RATE_PERCENT)
		)
	}

	override fun setPitchPercent(percent: Int) = synchronized(guard) {
		if (closed || basePitch < 0) return
		wantPitch = (basePitch.toLong() * VoicePlan.sanePercent(percent) / 100)
			.toInt()
			.coerceIn(0, Eci.PERCENT_MAX)
	}

	/**
	 * Establece la ganancia de salida como porcentaje, de 0 a 500.
	 *
	 * 4.0: solo ganancia ([AudioFx.gain]). Antes además pisaba el
	 * parámetro VOLUME de la voz actual (0-100), así que el volumen
	 * global borraba el volumen propio de cada preset y cambiar de
	 * voz no respetaba lo guardado. Ahora el preset manda en el
	 * motor y el global solo multiplica la salida.
	 */
	@Volatile
	var volumeGain: Int = 100
		private set

	override fun setVolumePercent(percent: Int) {
		volumeGain = percent.coerceIn(0, 500)
	}

	/** Lee un parámetro de voz. -1 si está cerrado. */
	fun getVoiceParam(param: Int): Int = synchronized(guard) {
		if (closed) -1 else native.getVoiceParam(handle, Eci.VOICE_CURRENT, param)
	}

	/** Escribe un parámetro en la voz actual. Contesta si tuvo éxito. */
	fun setVoiceParam(param: Int, value: Int): Boolean = synchronized(guard) {
		if (closed) return false
		val clamped = if (param == Eci.VOICE_VOLUME) value.coerceIn(0, 200)
		else Eci.clampVoice(param, value)
		if (native.setVoiceParam(handle, Eci.VOICE_CURRENT, param, clamped) < 0) return false
		if (param == Eci.VOICE_SPEED) {
			baseSpeed = clamped
			wantSpeed = baseSpeed
		}
		if (param == Eci.VOICE_VOLUME) curVolume = clamped.coerceIn(0, 100)
		return true
	}

	fun teachWord(volume: Int, key: String, say: String): Int = synchronized(guard) {
		if (closed) -1 else native.teachWord(
			handle, volume, WesternText.encode(key), WesternText.encode(say)
		)
	}

	fun forgetDictionaries() = synchronized(guard) {
		if (!closed) native.forgetDictionaries(handle)
	}

	/** Pone [text] en cola y arranca el motor. Las muestras salen
	 *  de [read]. El texto ya trae por delante el reinicio de
	 *  prosodia que armó [Emission]; aquí no se añade nada, para que
	 *  lo que suena sea exactamente lo que se compuso.
	 *
	 *  4.3: el juego de caracteres sale del idioma. Para los ocho de
	 *  IBM es el occidental de siempre ([WesternText]), que ademas
	 *  es lo que traduce los símbolos a palabras ("=" a "menor o
	 *  igual que"). El japonés necesita Shift-JIS, y pasarle por
	 *  [WesternText] le dava un cero por cada byte UTF-8: medido en
	 *  el motor, `こんにちは` daba 0 muestras. Ver [Eci.encode]. */
	override fun speak(text: String): Boolean = synchronized(guard) {
		if (closed) return false
		// 5.4.4: el texto final puede traer una selección de voz
		// (`` `vN ``) escrita a mano o puesta por una entrada de
		// diccionario. El motor la aplica al encolar y se queda con
		// ella, así que la siguiente emisión tiene que reponer la voz
		// del plan. Se marca aquí y no antes de hablar, porque un
		// código que todavía no se ha encolado no ha cambiado nada.
		if (Dictionaries.hasVoiceSelectText(text)) voiceChangedInline = true
		val bytes = if (Eci.needsItsOwnCodeSet(language)) Eci.encode(text, language)
		else WesternText.encode(text)
		return native.speak(handle, bytes)
	}

	/** Bytes de PCM, bloqueante hasta que los haya. 0 termina la
	 *  emisión y -1 dice que se detuvo. */
	fun read(into: ByteArray): Int {
		val live: Long
		synchronized(guard) {
			if (closed) return -1
			live = handle
			readers++
		}
		try {
			return native.read(live, into)
		} finally {
			synchronized(guard) {
				if (--readers == 0) guard.notifyAll()
			}
		}
	}

	fun stop() = synchronized(guard) {
		if (!closed) native.stop(handle)
		// 5.3: constar que el motor se detuvo y quedó listo para ser
		// restablecido por [resetProsody] antes de la siguiente emisión.
		Timber.i("motor detenido: engine=%d", id)
	}

	/**
	 * 4.2: cierra la instancia.
	 *
	 * En tres tiempos, y el orden es lo único que importa. Primero
	 * [native.abort] despierta a quien esté esperando dentro (el
	 * hilo del motor y un lector en [read]) sin soltar nada; luego
	 * se espera a que no quede ningún lector dentro, con plazo para
	 * que un aviso perdido no deje a nadie colgado; y solo entonces
	 * [native.destroy], que ya puede liberar.
	 *
	 * Antes se llamaba a destroy directamente, y eso liberaba el
	 * búfer con el hilo de síntesis todavía esperando dentro: el
	 * proceso se iba por abajo sin aviso y el sistema lo contaba
	 * como un motor de voz parado. En un teléfono con poca memoria
	 * pasa al abrir otra app, que es exactamente lo reportado.
	 */
	fun close() {
		val doomed: Long
		synchronized(guard) {
			if (closed) return
			closed = true
			doomed = handle
			handle = 0
			if (doomed != 0L) {
				native.abort(doomed)
				var waited = 0L
				while (readers > 0 && waited < CLOSE_WAIT_MS) {
					guard.wait(CLOSE_STEP_MS)
					waited += CLOSE_STEP_MS
				}
			}
		}
		if (doomed != 0L) native.destroy(doomed)
	}
}
