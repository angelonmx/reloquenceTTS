package com.angelonmx.reloquence

import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import timber.log.Timber
import java.util.Locale

/**
 * El motor con el que Android habla: TalkBack y cualquier otro
 * lector de pantalla o app que pida síntesis de voz.
 *
 * onSynthesizeText corre en el hilo de síntesis propio del
 * framework y es el único lugar donde se impulsa una emisión;
 * onStop llega desde otro hilo y no hace nada más que poner el
 * indicador de aborto del motor, que es lo que desbloquea la
 * lectura de abajo.
 */
class EvvTtsService : TextToSpeechService() {

	private var settings: Settings? = null

	@Volatile
	private var engine: EvvEngine? = null

	/** 5.0: la emisión en curso, con su propia cancelación. */
	@Volatile
	private var activeSession: TtsSession? = null

	@Volatile
	private var activeOutput: TtsOutput? = null

	/**
	 * 5.4: número de esta instancia dentro del proceso. El framework
	 * crea y suelta el servicio varias veces por sesión, así que el
	 * registro tiene que poder distinguir una instancia de otra. El
	 * contador vive en el `companion` (uno por proceso), no aquí: si
	 * viviera aquí volvería a empezar en 1 en cada instancia y dos
	 * servicios distintos se verían iguales.
	 */
	private val serviceNumber = serviceIds.incrementAndGet()

	/** 5.2: una línea del registro de errores, si el usuario lo
	 *  activó. Nunca lleva texto del usuario, notificaciones ni
	 *  rutas personales: solo ciclo de vida, motor y prosodia. */
	private fun diag(category: String, vararg values: Pair<String, String>) =
		Diagnostics.log(this, category, *values)

	/** Igual que [diag], pero con la clase y la pila de un fallo. El
	 *  mensaje de la excepción no se copia: puede traer texto del
	 *  usuario o una ruta personal. */
	private fun diagError(event: String, error: Throwable, vararg values: Pair<String, String>) =
		Diagnostics.logError(this, event, error, *values)

	private lateinit var foreground: TtsForeground

	/** 5.0: despierta una emisión que lleve demasiado sin avanzar. */
	private val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
		Thread(task, "evv-watchdog").apply { isDaemon = true }
	}

	/** Solo hay una emisión viva a la vez, pero `onStop` llega de otro
	 *  hilo y no puede tocar la sesión equivocada. */

	/**
	 * 4.3: hay algo que cambió y el motor tiene que reiniciarse antes
	 * de la siguiente emisión (el diccionario, la tasa, el idioma, una
	 * copia importada). Se pone en el hilo que escribió el ajuste y se
	 * aplica en el hilo de síntesis, al principio de la emisión
	 * siguiente: nunca a mitad, porque soltar una instancia que está
	 * leyendo el motor de abajo hace que la emisión termine en error,
	 * y un error de síntesis es justo lo que no se quiere al importar
	 * algo.
	 */
	@Volatile
	private var reloadRequested = false

	/** Por qué se pidió el reinicio, solo para el registro. */
	@Volatile
	private var reloadReason = ""

	/**
	 * 4.3: si ahora mismo hay una emisión en marcha. Lo consulta el
	 * aviso de reinicio, que llega al hilo principal, para no soltar
	 * el motor mientras se está hablando. La marca la pone el hilo de
	 * síntesis y solo ella la quita.
	 */
	@Volatile
	private var speaking = false

	/**
	 * 4.3: la instancia abierta antes de tiempo, para que la primera
	 * frase no espere a que el motor cargue sus trece megabytes de
	 * reglas de idioma.
	 *
	 * Abrir el motor es lo más caro que hay entre tocar una palabra y
	 * oírla: por eso el servicio abre la suya en cuanto el framework
	 * lo engancha, en vez de esperar a que TalkBack pida algo. Si llega
	 * una emisión con la instancia ya lista, se usa esa y no se
	 * desperdicia nada; si la emisión pide otro idioma, la instancia
	 * calentada se suelta y se abre la que hace falta.
	 */
	@Volatile
	private var warm: EvvEngine? = null

	@Volatile
	private var warmLanguage = 0

	/**
	 * 5.2: el aviso de que [prewarm] está en marcha, y para qué
	 * idioma.
	 *
	 * Solo sirve para no abrir dos motores calentados del mismo
	 * idioma a la vez. Una emisión que llega mientras tanto **no
	 * espera**: si el calentado ya está listo lo usa, y si no, abre
	 * el suyo al instante. Esperar al calentado era peor que no
	 * tenerlo: la primera frase pagaba el tope entero y luego abría
	 * otro motor de todas formas.
	 */
	@Volatile
	private var warmTicket: java.util.concurrent.CountDownLatch? = null

	@Volatile
	private var warmTicketLanguage = 0

	/** 5.2: si la instancia servida salió del motor calentado. Solo
	 *  lo lee el registro de la primera emisión. */
	@Volatile
	private var servedWarm = false

	/** 5.2: si el servicio se está yendo. [prewarm] lo mira antes de
	 *  guardar su instancia: si no, un calentado que termina justo
	 *  después de [onDestroy] dejaba un motor abierto para siempre. */
	@Volatile
	private var destroyed = false

	/**
	 * 4.3: los dos buferes de [pump], guardados entre llamadas.
	 *
	 * El hilo de sintesis es el que el sistema mide para decidir si
	 * el motor responde, y reservar dos arrays por cada trozo y cada
	 * ventana es trabajo de mas en ese hilo. Se crean la primera vez
	 * y despues solo crecen si el framework pide mas sitio.
	 */
	private var bufferA = ByteArray(0)
	private var bufferB = ByteArray(0)

	/**
	 * 4.4: el motor se quedó parado a mitad de esta emisión y se
	 * soltó. Es distinto de una interrupción: el framework no pidió
	 * silencio y la pista quedó abierta, así que sin un final se
	 * queda esperando.
	 */
	@Volatile
	private var engineLost = false

	/** Instancias por idioma: una emisión mezclada pasa por varias
	 *  sin abrir y cerrar en cada frase (se guardan dos, y una sola
	 *  cuando el sistema avisa que va justo de memoria). El mapa se
	 *  sincroniza porque lo tocan dos hilos: el de síntesis y el
	 *  principal. */
	private val engines = LinkedHashMap<Int, EvvEngine>()

	/** El sistema pidió recortar: solo se queda el idioma en vigor. */
	@Volatile
	private var memoryTight = false

	/** Lo que el servicio recuerda por instancia, bajo un candado
	 *  propio.
	 *
	 *  4.1: el hilo de síntesis lee y escribe esto en cada emisión y
	 *  el principal lo vacía en [onDestroy] y [onTrimMemory]. Con dos
	 *  mapas sueltos, un onDestroy en mitad de una emisión los
	 *  recorría mientras se modificaban y el servicio se caía con
	 *  una ConcurrentModificationException, que es una de las
	 *  formas de "se detuvo al abrir el correo" y de "no vuelve a
	 *  hablar". El candado es propio y solo vive aquí, así que no
	 *  se puede enredar con el del motor. */
	private class PerEngine {
		private val states = HashMap<EvvEngine, DictionaryState>()

		fun state(one: EvvEngine): DictionaryState? = synchronized(this) { states[one] }

		fun setState(one: EvvEngine, value: DictionaryState) = synchronized(this) { states[one] = value }

		fun forget(one: EvvEngine) = synchronized(this) { states.remove(one) }

		fun clear() = synchronized(this) { states.clear() }
	}

	/** La huella del último diccionario cargado en cada instancia:
	 *  archivo, tamaño, revisión y palabras propias. Antes de cada
	 *  emisión se compara con lo que hay ahora mismo y solo si algo
	 *  cambió se vuelve a preparar. La sustitución en texto es la
	 *  única vía de aplicación, así que no hay nada que reenseñar al
	 *  motor ni que llevar por rebanadas. */
	private val dictionaryStates = PerEngine()

	/** Una instancia lista y el plan completo que hay que aplicarle
	 *  antes de cada `speak`. */
	private class Ready(val engine: EvvEngine, val plan: VoicePlan.Plan)

	/**
	 * Deja [language] listo para hablar: instancia, diccionario,
	 * abreviaturas y el plan completo de voz. El plan se aplica antes
	 * de **cada** trozo en [Emission.speak], no solo al abrir.
	 *
	 * Los parámetros del usuario se releen siempre: un cambio entra en
	 * la siguiente frase aunque el servicio se haya reiniciado o el
	 * aviso no haya llegado.
	 */
	private fun readyEngine(language: Int, s: Settings?, reqRate: Int, reqPitch: Int): Ready? {
		val target = ensureEngine(language) ?: return null
		if (s == null) {
			val plan = VoicePlan.plan(
				voice = 0,
				shape = emptyMap(),
				preset = target.presetShape(0),
				requestRate = reqRate,
				requestPitch = reqPitch,
				useSystemSpeed = true,
				volumeGain = 100
			)
			target.setAbbreviations(false)
			return Ready(target, plan)
		}
		// El diccionario se revisa en cada pasada: cuando no
		// cambió no cuesta nada y una palabra nueva entra sin
		// tocar ningún ajuste.
		loadDictionary(target, s)
		target.setAbbreviations(s.abbreviations)
		val plan = VoicePlan.plan(
			voice = s.voice,
			shape = s.shape(s.voice),
			preset = target.presetShape(s.voice),
			requestRate = reqRate,
			requestPitch = reqPitch,
			useSystemSpeed = s.useSystemSpeed,
			volumeGain = s.volume,
			useSystemVolume = s.useSystemVolume
		)
		if (!plan.isComplete) {
			// No debería pasar: el plan rellena los ocho. Si pasa, no
			// se habla con una voz a medias.
			diag(
				"engine", "event" to "plan_incomplete",
				"language" to (Eci.bcp47Of(language) ?: "?"),
				"engine" to identity(target)
			)
			return null
		}
		diag(
			"settings", "event" to "plan",
			"language" to (Eci.bcp47Of(language) ?: "?"),
			"engine" to identity(target),
			*plan.diagnosticPairs().toTypedArray()
		)
		return Ready(target, plan)
	}

	private fun identity(one: EvvEngine): String = one.id.toString()

	/** En qué instancia quedó cargado el diccionario. 4.3:
	 *  `@Volatile` porque [dropEverything] también lo pone a cero desde
	 *  el hilo principal. */
	@Volatile
	private var loadedEngine: EvvEngine? = null

	private var loadedDictionary: String? = null
	private var loadedDictionaryModified: Long = 0L
	private var loadedWords: Map<String, String> = emptyMap()
	/**
	 * 4.1: el archivo se vuelve a leer cuando cambia su fecha **o
	 * su tamaño**. Una entrada quitada o añadida desde el editor
	 * puede caer dentro del mismo segundo, y con la fecha sola la
	 * sustitución en texto se quedaba con la lista vieja: la
	 * palabra quitada seguía sonando y la nueva no entraba.
	 *
	 * 4.3: y además cuando cambia el contador de [SettingsSignal],
	 * que es lo que de verdad avisa. Importar dos veces seguidas el
	 * mismo archivo, o uno con el mismo número de entradas, deja la
	 * fecha y el tamaño exactamente iguales, y el motor seguía con
	 * el diccionario viejo: lo que se oía no era lo importado.
	 */
	private var loadedDictionarySize: Long = -1L
	private var loadedDictionaryRevision: Long = 0L
	/** Entradas para la sustitución en texto (insensible a
	 *  mayúsculas): archivo más palabras propias. */
	private var subEntries: List<Pair<String, String>> = emptyList()
	private var subHasVoiceSelect = false

	/**
	 * 5.4.2: las palabras propias convertidas en lista, con el mapa
	 *  del que salieron. Sin esto, el caso sin archivo de diccionario
	 *  armaba una lista nueva en cada emisión, la caché de
	 *  [Dictionaries] la veía distinta y rehacía todos los patrones
	 *  antes de cada frase.
	 */
	private var wordSubEntries: Pair<Map<String, String>, List<Pair<String, String>>>? = null

	/**
	 * 4.3: el diccionario ya leído y con la lista de sustitución
	 * armada, en un hilo que no es el de síntesis.
	 *
	 * Leer unas miles de líneas y, sobre todo, armar la alternancia
	 * de las frases cuesta lo que cuesta, y eso pasaba en el hilo de
	 * síntesis: era la pausa larga antes de la primera palabra
	 * después de importar, que es justo cuando más se nota.
	 * Preparándolo aquí, la emisión solo lo recoge.
	 */
	private val preparedByLanguage =
		java.util.concurrent.ConcurrentHashMap<Int, PreparedDictionary>()
	private val dictionaryLock = Any()

	private class PreparedDictionary(
		val path: String?,
		val modified: Long,
		val size: Long,
		val words: Map<String, String>,
		val revision: Long,
		val entries: List<Pair<String, String>>,
		val substitutions: List<Pair<String, String>>,
		val voiceSelect: Boolean
	)

	private fun prepareDictionary(reason: String) {
		val s = settings ?: return
		val languages = LinkedHashSet<Int>()
		languages.add(s.resolvedLanguage())
		languages.add(s.activeDictionaryLanguage)
		for (entry in s.dictionaries) if (entry.active) languages.add(entry.language)
		Thread({
			for (language in languages) readDictionary(s, language)
			Dictionaries.invalidateSubCache()
		}, "evv-dict-$reason").apply { isDaemon = true }.start()
	}

	/**
	 * 4.3: el diccionario que mandan los ajustes ahora mismo, leído y
	 * con la lista de sustitución ya armada. Contesta lo que hay, o
	 * nada si no hay diccionario para [language].
	 */
	private fun readDictionary(s: Settings, language: Int): PreparedDictionary? =
		synchronized(dictionaryLock) {
			val want = s.dictionaryForLanguage(language)?.path
				?.takeIf { java.io.File(it).exists() }
			val file = want?.let { java.io.File(it) }
			val words = s.dictionaryWords
			val modified = file?.lastModified() ?: 0L
			val size = file?.length() ?: -1L
			val revision = SettingsSignal.dictionaryRevision
			val current = preparedByLanguage[language]
			if (current != null && current.path == want && current.modified == modified &&
				current.size == size && current.words == words && current.revision == revision
			) return current
			if (want == null) {
				preparedByLanguage.remove(language)
				return null
			}
			val fileEntries = Dictionaries.entries(file!!)
			val sub = (fileEntries.toMap() + words).toList()
			val ready = PreparedDictionary(
				want, modified, size, words, revision,
				fileEntries, sub, Dictionaries.hasVoiceSelect(sub)
			)
			preparedByLanguage[language] = ready
			ready
		}

	/**
	 * 5.2: el framework puede arrancar el servicio con `startService`
	 * además de engancharlo al motor de texto a voz. Se llama a
	 * `super` --saltárselo deja el servicio sin la inicialización del
	 * framework-- y se apunta en el registro, que es donde se ve si un
	 * arranque llegó por aquí o solo por enlace.
	 */
	override fun onStartCommand(
		intent: android.content.Intent?,
		flags: Int,
		startId: Int
	): Int {
		Timber.i("onStartCommand: flags=%d, startId=%d", flags, startId)
		diag("lifecycle", "event" to "start_command", "flags" to flags.toString(), "start_id" to startId.toString())
		return super.onStartCommand(intent, flags, startId)
	}

	override fun onCreate() {
		if (timber.log.Timber.forest().isEmpty()) timber.log.Timber.plant(timber.log.Timber.DebugTree())
		Timber.i("servicio creado")
		Diagnostics.install(this)
		diag(
			"lifecycle", "event" to "service_created",
			"engine_version" to EvvEngine.version.ifEmpty { "?" },
			"edition" to if (BuildConfig.LITE) "lite" else "full",
			"app_version" to BuildConfig.VERSION_NAME,
			"sdk" to android.os.Build.VERSION.SDK_INT.toString(),
			"abi" to (android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?"),
			"service" to serviceNumber.toString(),
			"pid" to android.os.Process.myPid().toString()
		)
		settings = Settings(this)
		CldrData.install(this)
		super.onCreate()
		foreground = TtsForeground(this)
		TtsHealth.service(true)
		TtsHealth.engineVersion(EvvEngine.version)
		// 5.0: una emisión que lleve demasiado sin entregar audio no
		// puede dejar al framework esperando para siempre.
		watchdog.scheduleWithFixedDelay({ watch() }, 1L, 1L, java.util.concurrent.TimeUnit.SECONDS)
		// 4.3: el motor se entera de los cambios de los ajustes por
		// aquí. Se cuelga en onCreate y se descuelga en onDestroy con
		// la misma función en los dos sitios, para que no se pueda
		// quedar uno de los dos sin hacer: sin este par, el servicio
		// anterior se seguiría enterando de cambios que ya no le
		// tocan.
		SettingsSignal.addEngineListener(onSettingsChanged)
		startOngoing()
		// 4.3: el framework engancha el servicio antes de que nadie
		// pida hablar, así que la instancia se abre aquí y en segundo
		// plano: la primera frase no espera a que el motor cargue sus
		// reglas de idioma. El diccionario se lee también, que si no
		// lo espera la primera frase.
		prepareDictionary("arranque")
		prewarm()
	}

	/**
	 * 5.0: si una emisión lleva [WATCHDOG_MS] sin entregar audio ni
	 * avanzar, se cancela la sesión y se pide al motor que pare. El
	 * servicio sigue vivo para la siguiente frase; lo que no puede
	 * pasar es que la pista quede abierta.
	 */
	private fun watch() {
		val s = activeSession ?: return
		if (s.cancelled) return
		if (System.nanoTime() - s.lastProgressNs < WATCHDOG_NS) return
		Timber.w("watchdog: la emisión %d lleva demasiado sin avanzar", s.id)
		diag("error", "event" to "watchdog", "session" to s.id.toString())
		if (s.cancel(fromWatchdog = true)) {
			TtsHealth.cancelled()
			TtsHealth.watchdog()
		}
		val live = synchronized(engines) { engines.values.toList() }
		for (one in live) runCatching { one.stop() }
	}

	/**
	 * 4.3: llega al hilo principal. La marca se pone ya, sin esperar,
	 * para que una emisión que empiece ahora mismo la vea; soltar el
	 * motor se hace solo cuando no hay nadie hablando, que es la
	 * única forma de hacerlo sin cortar una frase por la mitad.
	 */
	private val onSettingsChanged: (SettingsSignal.Change) -> Unit = { change ->
		diag(
			"settings", "event" to "changed",
			"reason" to change.reason,
			"dictionary" to change.dictionary.toString(),
			"speaking" to speaking.toString()
		)
		reloadRequested = true
		reloadReason = change.reason
		// El motor se suelta ya si no hay nadie hablando. La marca se
		// quita antes de hacerlo, no despues: si otro cambio llegara
		// mientras se suelta, tiene que quedar marcado para la
		// siguiente emision en vez de tragarselo este.
		if (!speaking) {
			reloadRequested = false
			reloadReason = ""
			dropEverything(change.reason)
		}
	}

	/**
	 * 4.3: suelta las instancias y todo lo que dependía de ellas, y
	 * vuelve a dejar el motor calentado.
	 *
	 * Soltar en caliente es lo que hace que un cambio se oiga entero y
	 * no a medias: la huella de la voz, el diccionario ya cargado y el
	 * archivo preparado se olvidan, así que la siguiente emisión abre
	 * una instancia nueva que solo sabe lo que el usuario acaba de
	 * dejar escrito.
	 *
	 * Solo se llama con el motor en reposo ([speaking] a cero) o desde
	 * el principio de una emisión, que es lo mismo: entre el framework
	 * no hay dos emisiones a la vez.
	 */
	private fun dropEverything(reason: String) {
		val live = ArrayList<EvvEngine>()
		synchronized(engines) {
			live.addAll(engines.values)
			engines.clear()
			val held = warm
			if (held != null && live.none { it === held }) live.add(held)
			warm = null
			warmLanguage = 0
			engine = null
		}
		// Fuera del candado: cerrar espera a que no quede ningún
		// lector dentro y eso puede tardar, y con el candado puesto
		// el resto del servicio se quedaría esperando.
		for (one in live) one.close()
		dictionaryStates.clear()
		loadedEngine = null
		loadedDictionary = null
		loadedDictionaryModified = 0L
		loadedDictionarySize = -1L
		loadedWords = emptyMap()
		loadedDictionaryRevision = 0L
		// La alternancia de frases del diccionario queda armada con el
		// reinicio de voz de antes, asi que se tira: si no, la
		// primera frase tras importar sale con la prosodia anterior.
		Dictionaries.invalidateSubCache()
		Timber.i("motor reiniciado: $reason")
		diag(
			"engine", "event" to "reload",
			"reason" to reason,
			"closed" to live.size.toString(),
			"service" to serviceNumber.toString()
		)
		// El diccionario tambien se lee aqui y no en la primera
		// emision: leerlo y armar la lista de sustitucion es la parte
		// cara, y hacerla alli era la pausa larga que se notaba justo
		// despues de importar.
		prepareDictionary(reason)
		prewarm()
	}

	/**
	 * 4.3: abre el motor del idioma que toca en segundo plano, para
	 * que la primera frase no espere a que el motor cargue sus reglas.
	 *
	 * Va en su propio hilo, y lo que se abre ahí solo se guarda si al
	 * terminar no llegó ya una instancia, si el servicio no se está
	 * yendo y si mientras tanto no cambió nada. Nunca hay más de una
	 * instancia calentada.
	 *
	 * 5.3: el motor calentado se abre **crudo**: aquí NO se aplica el
	 * plan de voz. El plan (voz, tono, velocidad, volumen, ... del
	 * usuario) se aplica única y exclusivamente en el path de emisión
	 * ([readyEngine] + [Emission.speak]), con los valores reales del
	 * sistema (`requestRate`, `requestPitch`) y los del usuario
	 * (`Settings`). Nunca se aplica aquí un plan con valores
	 * hardcodeados distintos a los de la emisión, porque eso deja al
	 * motor en un estado distinto al que dice el plan y la voz suena
	 * distinta tras cada parada.
	 */
	private fun prewarm() {
		// Con el sistema apretado de memoria no se guarda una instancia
		// de sobra: en un telefono de 4 GB con Telegram abierto, un
		// motor de mas es un motor de menos, y el arranque instantaneo
		// no vale una muerte del proceso.
		if (memoryTight) return
		val s = settings ?: return
		val language = s.resolvedLanguage()
		if (language == 0 || !EvvEngine.available.contains(language)) return
		if (warm != null && warmLanguage == language) return
		// 5.2: si ya hay uno abriéndose para este idioma, no se abre
		// otro. La emisión que llegue no lo espera: abre el suyo si
		// hace falta y el que llegue tarde se cierra al adoptarlo.
		if (warmTicket != null && warmTicketLanguage == language) return
		val rate = s.sampleRateHz
		val epoch = SettingsSignal.epoch
		// 5.2: se anuncia que hay un motor en camino antes de abrir
		// el hilo, para que no se lance otro precalentado del mismo
		// idioma mientras tanto.
		val ticket = java.util.concurrent.CountDownLatch(1)
		warmTicket = ticket
		warmTicketLanguage = language
		val startedNs = System.nanoTime()
		diag(
			"engine", "event" to "prewarm_start",
			"language" to (Eci.bcp47Of(language) ?: "?"),
			"rate" to rate.toString(),
			"epoch" to epoch.toString()
		)
		Thread({
			try {
				// Prioriza el motor: inflar CLDR antes de eciNewEx competía
				// con la inicialización que determina la primera voz.
				val made = EvvEngine.open(language, rate) ?: return@Thread
				// 5.3: el motor calentado solo se abre; el plan de voz se
				// aplica únicamente en el path de emisión
				// ([readyEngine] + [Emission.speak]), con los valores
				// reales del sistema y del usuario. Nunca aquí.
				made.setSampleRate(s.sampleRateHz)
				made.setAbbreviations(s.abbreviations)
				var old: EvvEngine? = null
				var keep = false
				var stale = false
				synchronized(engines) {
					stale = destroyed || reloadRequested || SettingsSignal.epoch != epoch ||
						engines[language] != null
					if (!stale) {
						old = warm.takeIf { it !== made && warmLanguage != language }
						warm = made
						warmLanguage = language
						keep = true
					}
				}
				if (keep) {
					diag(
						"engine", "event" to "prewarm_ready",
						"language" to (Eci.bcp47Of(language) ?: "?"),
						"engine" to identity(made),
						"rate" to made.sampleRateHz.toString(),
						"elapsed_ms" to ((System.nanoTime() - startedNs) / 1_000_000L).toString()
					)
				} else {
					diag(
						"engine", "event" to "prewarm_discarded",
						"language" to (Eci.bcp47Of(language) ?: "?"),
						"stale" to stale.toString()
					)
					made.close()
				}
				// La instancia calentada anterior se cierra fuera del
				// candado: close() espera a que no quede ningún lector.
				if (old != null && old !== made) old.close()
				if (keep) {
					Eci.localeOf(language)?.first?.let {
						CldrData.prewarm(it, s.processEmojis, s.readUnicodeSymbols)
					}
				}
			} catch (e: Throwable) {
				diagError(
					"prewarm_failed",
					e,
					"language" to (Eci.bcp47Of(language) ?: "?")
				)
			} finally {
				// Se avisa siempre, salga como salga: una emisión
				// esperando aquí no puede quedarse esperando a un
				// motor que ya no va a llegar.
				if (warmTicket === ticket) {
					warmTicket = null
					warmTicketLanguage = 0
				}
				ticket.countDown()
			}
		}, "evv-prewarm").apply { isDaemon = true }.start()
	}

	/**
	 * 4.3: la petición de reinicio, atendida en el hilo de síntesis
	 * antes de mirar nada de la emisión. Aquí sí se puede soltar el
	 * motor sin riesgo, porque el framework no llama a
	 * [onSynthesizeText] hasta que la anterior ha terminado.
	 */
	private fun takeReload() {
		if (!reloadRequested) return
		reloadRequested = false
		val reason = reloadReason
		reloadReason = ""
		Timber.i("el motor toma el reinicio pendiente: $reason")
		dropEverything(reason)
	}

	/**
	 * Notificación permanente: mantiene vivo el servicio y avisa
	 * si falta algún permiso (tocarla abre los ajustes). Si el
	 * sistema no deja ponerla, el servicio sigue sin ella, pero
	 * [keepForeground] lo vuelve a intentar: en Android 13 en
	 * adelante la primera llamada falla porque el permiso de
	 * notificaciones aún no está, y un servicio sin primer plano es
	 * un servicio que el sistema se lleva en cuanto falta memoria,
	 * que es justo lo que se reportaba.
	 */
	private fun startOngoing() {
		foreground.start("arranque")
	}

	private fun keepForeground() {
		foreground.ensure("emisión")
	}

	/** Android 15 puede retirar el primer plano aunque el motor siga
	 *  hablando. No se relanza en bucle: se deja constancia y el
	 *  servicio sigue mientras el sistema lo permita. */
	override fun onTimeout(startId: Int, fgsType: Int) {
		diag(
			"lifecycle", "event" to "timeout",
			"start_id" to startId.toString(),
			"fgs_type" to fgsType.toString()
		)
		foreground.timedOut()
		super.onTimeout(startId, fgsType)
	}

	/**
	 * La app se cerró de la pantalla de recientes y el servicio
	 * sigue vivo. En Android 12 en adelante quitar una tarea puede
	 * cancelar el primer plano, así que se vuelve a pedir.
	 */
	override fun onTaskRemoved(rootIntent: android.content.Intent?) {
		diag("lifecycle", "event" to "task_removed")
		super.onTaskRemoved(rootIntent)
		keepForeground()
	}

	override fun onDestroy() {
		// 5.2: se marca antes que nada: un [prewarm] que esté
		// terminando en este instante tiene que verlo y cerrar su
		// motor en vez de guardarlo en un servicio que ya no existe.
		destroyed = true
		Timber.i("servicio destruido")
		diag(
			"lifecycle", "event" to "service_destroyed",
			"service" to serviceNumber.toString()
		)
		// 4.3: primero se descuelga el aviso. Si no, este servicio
		// sigue registrado y su reacción al próximo cambio de ajustes
		// opera sobre un motor que ya no existe.
		SettingsSignal.removeEngineListener(onSettingsChanged)
		if (activeSession?.cancel() == true) TtsHealth.cancelled()
		activeOutput?.abandon()
		watchdog.shutdownNow()
		foreground.stop()
		TtsHealth.speaking(false)
		TtsHealth.service(false)
		super.onDestroy()
		// El mapa se vacía aquí, en el hilo principal, mientras el
		// de síntesis puede estar recorriéndolo: por eso el candado
		// (y por eso no se sueltan las instancias fuera de él).
		val held = ArrayList<EvvEngine>()
		synchronized(engines) {
			held.addAll(engines.values)
			engines.clear()
			warm?.let { extra ->
				if (held.none { it === extra }) held.add(extra)
			}
			warm = null
			warmLanguage = 0
		}
		for (one in held) one.close()
		engine = null
		dictionaryStates.clear()
		loadedEngine = null
		diag(
			"lifecycle", "event" to "engines_closed",
			"count" to held.size.toString(),
			"service" to serviceNumber.toString()
		)
		// 5.4: el framework suelta el servicio y el proceso puede
		// morir justo después. Las escrituras van por su propio hilo,
		// así que sin esperar aquí la línea de cierre (y las últimas
		// del motor) se quedaban en la cola: el registro real tenía
		// 35 `service_created` y ningún `service_destroyed`.
		Diagnostics.flush()
	}

	// ---- idiomas -------------------------------------------------------

	private fun matchLanguage(lang: String?, country: String?): Pair<Int, Int>? {
		val s = settings
		if (lang.isNullOrEmpty()) {
			// Sin idioma pedido: el predeterminado de los ajustes. Y
			// 4.1: "predeterminado" ya no es el primer idioma que el
			// motor declara (el inglés) sino el que corresponde al
			// idioma del sistema, que es lo que el apartado de
			// ajustes llama "Sistema". Un teléfono en español sin
			// nada tocado hablaba con voz inglesa, y de ahí lo de
			// "versión" dicho como una palabra sola.
			val fallback = s?.resolvedLanguage()?.takeIf { it != 0 && Eci.localeOf(it) != null }
				?: EvvEngine.available.firstOrNull() ?: return null
			return fallback to TextToSpeech.LANG_AVAILABLE
		}
		val wantLang = normalise(lang)
		val wantCountry = country?.takeIf { it.isNotEmpty() }?.let { normaliseCountry(it) }
		var byLanguage: Int? = null
		for (candidate in EvvEngine.available) {
			val loc = Eci.localeOf(candidate) ?: continue
			if (!loc.first.equals(wantLang, ignoreCase = true)) continue
			if (byLanguage == null) byLanguage = candidate
			if (wantCountry == null) continue
			// 5.2: se compara contra la etiqueta que se anuncia y
			// contra la región del módulo. El español latinoamericano
			// anuncia `es-419` y su región es `419`; pedir `MX` ya no
			// cae en una variante aparte porque esa variante se
			// retiró, pero `419` sigue casando.
			for (tag in Eci.tagsOf(candidate)) {
				val region = Eci.tagCountry(tag) ?: tag.substringAfter('-', "")
				if (region.equals(wantCountry, ignoreCase = true)) {
					return candidate to TextToSpeech.LANG_COUNTRY_AVAILABLE
				}
			}
			if (loc.second.equals(wantCountry, ignoreCase = true)) {
				return candidate to TextToSpeech.LANG_COUNTRY_AVAILABLE
			}
		}
		return byLanguage?.let { it to TextToSpeech.LANG_AVAILABLE }
	}

	/**
	 * 5.2: el idioma en ISO-3, venga como venga.
	 *
	 * Android manda `lang` en ISO-639-2 (`"spa"`) casi siempre, pero
	 * hay clientes que mandan ISO-639-1 (`"es"`). `Locale.forLanguageTag`
	 * acepta las dos formas y sustituye al constructor `Locale(String)`,
	 * que está obsoleto desde la API 36. Lo que no se entiende se deja
	 * como vino, que es lo que hacía antes.
	 */
	private fun normalise(lang: String): String =
		runCatching { Locale.forLanguageTag(lang).isO3Language }
			.getOrNull()?.takeIf { it.isNotEmpty() } ?: lang

	/** Lo mismo para el país: `"MX"` -> `"MEX"`. La región `"419"`
	 *  no es un país y se queda tal cual, igual que antes. */
	private fun normaliseCountry(country: String): String =
		runCatching { Locale.forLanguageTag("und-$country").isO3Country }
			.getOrNull()?.takeIf { it.isNotEmpty() } ?: country

	override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
		val found = matchLanguage(lang, country) ?: return TextToSpeech.LANG_NOT_SUPPORTED
		return found.second
	}

	override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
		val found = matchLanguage(lang, country) ?: return TextToSpeech.LANG_NOT_SUPPORTED
		// 5.4: aquí no se abre ningún motor.
		//
		// El framework llama a esto con el idioma del sistema (en el
		// registro real, `es-ES`) antes de pedir la síntesis, y la
		// síntesis viene con el país del cliente (`es-419`). Abrir en
		// `onLoadLanguage` gastaba una instancia del idioma equivocado
		// por cada servicio: se abría `es-ES`, la emisión abría
		// `es-419` y la primera se cerraba acto seguido con
		// `reason=live_limit`. De las 35 creaciones del registro,
		// todas hacían esa pareja. Contestar que el idioma está
		// disponible es lo que el framework necesita; la instancia la
		// abre [ensureEngine] cuando ya se sabe qué se va a leer.
		return found.second
	}

	override fun onGetLanguage(): Array<String> {
		val language = engine?.language
			?: settings?.resolvedLanguage()?.takeIf { it != 0 }
			?: EvvEngine.available.firstOrNull()
			?: return arrayOf("eng", "USA", "")
		val loc = Eci.localeOf(language) ?: return arrayOf("eng", "USA", "")
		return arrayOf(loc.first, loc.second, loc.third)
	}

	// ---- voces ----------------------------------------------------------

	/** Una voz elegida: el módulo que la habla, el preset y la
	 *  etiqueta anunciada. Desde 5.2 hay una sola etiqueta por
	 *  módulo. */
	private class FoundVoice(val language: Int, val preset: Int, val tag: String?)

	private fun voiceName(tag: String, preset: Int): String =
		"$tag-${Eci.PRESET_NAMES[preset]}"

	/** La etiqueta con la que se anuncia [language]. Desde 5.2 es
	 *  una sola por módulo; el país ya no elige entre variantes. */
	private fun tagFor(language: Int): String? =
		Eci.tagsOf(language).firstOrNull()

	override fun onGetVoices(): MutableList<Voice> {
		val out = ArrayList<Voice>()
		for (language in EvvEngine.available) {
			for (tag in Eci.tagsOf(language)) {
				// 5.0: `Locale.forLanguageTag` y no
				// `Locale("spa","MEX")`: lo que Android compara y
				// enseña es la etiqueta BCP-47, y con el nombre del
				// módulo no casaba ninguna voz.
				val locale = Locale.forLanguageTag(tag)
				for (preset in Eci.PRESET_NAMES.indices) {
					out.add(
						Voice(
							voiceName(tag, preset),
							locale,
							Voice.QUALITY_NORMAL,
							Voice.LATENCY_VERY_LOW,
							false,
							emptySet()
						)
					)
				}
			}
		}
		return out
	}

	private fun findVoice(name: String?): FoundVoice? {
		if (name.isNullOrEmpty()) return null
		// 5.2: los nombres que anunciaba la variante retirada
		// (`es-MX-Reed`) se leen como los del módulo
		// (`es-419-Reed`). Quien los tenga guardados en un lector de
		// pantalla no se queda sin voz al actualizar. La etiqueta se
		// migra aquí y no se anuncia nunca más.
		val wanted = Eci.migrateVoiceName(name) ?: name
		for (language in EvvEngine.available) {
			for (tag in Eci.tagsOf(language)) {
				for (preset in Eci.PRESET_NAMES.indices) {
					if (voiceName(tag, preset) == wanted) return FoundVoice(language, preset, tag)
				}
			}
		}
		// 5.0: los nombres que anunciaban las versiones anteriores
		// ("spa-MEX-Reed", "jpn-JPN-Reed") siguen valiendo. Quien los
		// tenga guardados no se queda sin voz al actualizar. El
		// `spa-MEX` viejo era el módulo genérico, no México, así que
		// se mapea a `es-419`.
		for (language in EvvEngine.available) {
			val loc = Eci.localeOf(language) ?: continue
			val legacy = "${loc.first}-${loc.second}"
			for (preset in Eci.PRESET_NAMES.indices) {
				if ("$legacy-${Eci.PRESET_NAMES[preset]}" == wanted) {
					return FoundVoice(language, preset, Eci.bcp47Of(language))
				}
			}
		}
		return null
	}

	override fun onIsValidVoiceName(name: String?): Int =
		if (findVoice(name) != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR

	override fun onLoadVoice(name: String?): Int {
		// El framework consulta la voz durante su propia inicialización.
		// Abrir aquí el motor bloquea ese camino; la instancia se prepara
		// en segundo plano desde onCreate y, si aún no está lista, se abre
		// antes de sintetizar la primera solicitud.
		return if (findVoice(name) != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR
	}

	override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? {
		val s = settings
		// 4.1: mismo criterio que [matchLanguage]: sin ajuste
		// elegido manda el idioma del sistema, no el inglés.
		val preferred = s?.resolvedLanguage()?.takeIf { it != 0 && Eci.localeOf(it) != null }
		val language = preferred ?: matchLanguage(lang, country)?.first ?: return null
		val tag = tagFor(language) ?: return null
		return voiceName(tag, s?.voice ?: 0)
	}

	// ---- hablar --------------------------------------------------------

	/**
	 * Cuántas instancias del motor se dejan vivas a la vez.
	 *
	 * 4.1: eran tres y cada una lleva dentro las reglas del idioma,
	 * que son trece megabytes de tablas. Con el detector de idioma
	 * encendido eso son tres motores residentes mientras el usuario
	 * habla, y Telegram o el correo abiertos del lado-layout ya
	 * piden memoria: el sistema mata primero a lo que no está en
	 * primer plano, que es este servicio, y el motor se cae a mitad
	 * de lo que estaba hablando. Dos es de sobra (el idioma en
	 * vigor y el que se acaba de dejar) y una cuando el sistema
	 * avisa que va justo de memoria ([onTrimMemory]).
	 */
	/**
	 * Suelta [one] para siempre: se cierra y se olvida, así la
	 * siguiente emisión abre una instancia nueva con su diccionario
	 * y sus parámetros desde cero. Es la vuelta atrás de [ensureEngine]
	 * para cuando el motor se pone en mal estado.
	 */
	private fun forget(one: EvvEngine?) {
		if (one == null) return
		dictionaryStates.forget(one)
		var dropped = false
		synchronized(engines) {
			if (engines.remove(one.language) === one) dropped = true
			if (engine === one) engine = null
		}
		if (dropped) {
			if (loadedEngine === one) loadedEngine = null
			one.close()
		}
	}

	/**
	 * Cuántas instancias del motor se dejan vivas a la vez.
	 *
	 * 4.2: una sola por omisión. Cada instancia lleva dentro las
	 * reglas de su idioma, y el sistema mata primero a lo que no
	 * está en primer plano, que es este servicio: en un Samsung A54
	 * (4 GB) con Telegram abierto, un motor de más es un motor de
	 * menos. Con el detector de idioma encendido hacen falta dos,
	 * porque una emisión mezclada va y viene entre los dos.
	 */
	private fun liveEngines(): Int =
		if (memoryTight || settings?.autoDetectLanguage != true) 1 else 2

	private fun ensureEngine(language: Int): EvvEngine? {
		servedWarm = false
		// 4.3: última puerta antes del motor. Pedirle una instancia de
		// un idioma que este binario no trae no da error, da una
		// manija rota y revienta más adentro, en el hilo de síntesis,
		// donde el proceso se muere y Android reinicia el servicio.
		// Es la forma que se veía como "la voz se para y vuelve".
		//
		// Aquí se sube al primer idioma que el binario declare, que es
		// preferible a callarse: si el usuario está en polaco y no hay
		// polaco, lo que quiere es que le hablen, no que se calle.
		if (language == 0 || !EvvEngine.available.contains(language)) {
			val fallback = EvvEngine.available.firstOrNull() ?: return null
			Timber.e("0x%08x no está en este motor; se abre 0x%08x", language, fallback)
			return ensureEngine(fallback)
		}
		engine?.takeIf { it.language == language }?.let { return it }
		// El mapa lo tocan el hilo de síntesis (aquí) y el principal
		// (onDestroy, onTrimMemory). Sin este candado, un onDestroy
		// en mitad de una emisión vacía el mapa mientras se recorre
		// y el servicio se cae con una ConcurrentModification, que es
		// una de las formas de "se detuvo al abrir el correo".
		synchronized(engines) {
			engines[language]?.let {
				engine = it
				return it
			}
		}
		// 4.3: si la instancia calentada es del idioma pedido, se
		// usa. Abrir el motor es lo caro que hay entre pedir una frase
		// y oírla, y [prewarm] ya lo hizo mientras no había nada que
		// decir: aquí solo queda cogerla.
		//
		// 5.4.4: **no se espera** al calentado. La versión anterior le
		// daba 200 ms si estaba en marcha, y eso era un retardo que la
		// app añadía a la primera frase: el motor tarda unos 13 ms en
		// abrir, así que la espera solo servía para no abrir un
		// segundo. Ahora gana el que llegue primero: si el calentado ya
		// está guardado se adopta y se cierra el recién abierto; si el
		// recién abierto se guarda antes, [prewarm] descarta el suyo al
		// verlo. Se paga una apertura de más, nunca un silencio. Se
		// prefiere eso a retrasar lo que el usuario ya pidió decir.
		val rate = settings?.sampleRateHz ?: Eci.sampleRateHz(1)
		var made: EvvEngine? = null
		synchronized(engines) {
			val held = warm
			if (held != null && warmLanguage == language && engines[language] == null) {
				warm = null
				warmLanguage = 0
				made = held
				servedWarm = true
			}
		}
		if (made == null) {
			made = EvvEngine.open(language, rate)
			if (made == null) {
				diag(
					"engine", "event" to "open_failed",
					"language" to (Eci.bcp47Of(language) ?: "?")
				)
				return null
			}
			diag(
				"engine", "event" to "open",
				"language" to (Eci.bcp47Of(language) ?: "?"),
				"engine" to identity(made),
				"rate" to rate.toString(),
				"warm" to servedWarm.toString()
			)
		} else {
			diag(
				"engine", "event" to "served_warm",
				"language" to (Eci.bcp47Of(language) ?: "?"),
				"engine" to identity(made),
				"rate" to made.sampleRateHz.toString()
			)
		}
		val fresh = made ?: return null
		// 5.0: una apertura nueva se configura antes de guardarla. Si
		// el motor no acepta la voz en medio segundo, se cierra y se
		// reintenta en la emisión siguiente; no se bloquea el hilo de
		// síntesis durante diez segundos como antes.
		// 5.3: una apertura nueva se configura antes de guardarla. El plan
		// de voz (prosodia) NO se aplica aquí: se aplica únicamente en el
		// path de emisión ([Emission.speak]), con los valores reales del
		// usuario y del sistema. Aplicar un plan con
		// requestRate/requestPitch hardcodeados aquí dejaba al motor en un
		// estado distinto al de la emisión y la voz sonaba distinta tras
		// cada parada.
		val s = settings
		if (s != null) {
			fresh.setSampleRate(s.sampleRateHz)
			fresh.setAbbreviations(s.abbreviations)
		}
		val drop = ArrayList<EvvEngine>()
		var use: EvvEngine
		var adoptedWarm = false
		synchronized(engines) {
			val existing = engines[language]
			val held = warm
			if (existing != null) {
				// Otra vía abrió el mismo idioma mientras tanto. Gana
				// la que ya estaba; [made] se cierra fuera.
				drop.add(fresh)
				use = existing
			} else if (held != null && warmLanguage == language) {
				// 5.4: el calentado llegó justo mientras se abría
				// este. Se queda el calentado (ya abierto, sin aplicar
				// el plan de voz: eso lo hace la emisión), y el recién
				// abierto se cierra fuera. Sin esto el calentado se
				// quedaba guardado sin usar y la siguiente emisión
				// volvía a abrir otro.
				warm = null
				warmLanguage = 0
				engines[language] = held
				drop.add(fresh)
				use = held
				adoptedWarm = true
				servedWarm = true
			} else {
				engines[language] = fresh
				use = fresh
			}
			val keep = liveEngines()
			while (engines.size > keep) {
				val oldest = engines.keys.firstOrNull { it != language } ?: break
				val dropped = engines.remove(oldest) ?: break
				if (dropped !== use) drop.add(dropped)
			}
			if (engine !== use) engine = use
		}
		if (adoptedWarm) {
			diag(
				"engine", "event" to "served_warm",
				"language" to (Eci.bcp47Of(language) ?: "?"),
				"engine" to identity(use),
				"rate" to use.sampleRateHz.toString()
			)
		}
		// 5.0: cerrar espera a que no quede ningún lector. Hacerlo con
		// el candado del mapa puesto bloqueaba al hilo de síntesis y
		// al principal a la vez.
		for (one in drop) {
			dictionaryStates.forget(one)
			if (loadedEngine === one) loadedEngine = null
			diag(
				"engine", "event" to "closed",
				"language" to (Eci.bcp47Of(one.language) ?: "?"),
				"engine" to identity(one),
				"reason" to "live_limit"
			)
			runCatching { one.close() }
		}
		return use
	}

	/**
	 * El sistema avisa que va justo de memoria. Cada instancia del
	 * motor es grande y solo hace falta una, así que se sueltan
	 * todas menos la del idioma en vigor. Volver a abrir una cuesta
	 * unos milisegundos, y no abrirla del todo sería no hablar.
	 */
	override fun onTrimMemory(level: Int) {
		super.onTrimMemory(level)
		// 5.2: UI_HIDDEN (20) no es falta de memoria: es que la app
		// dejó de verse. El umbral de antes era RUNNING_LOW (10), así
		// que irse al inicio de Android marcaba [memoryTight] **para
		// siempre**: al volver a la app ya no se calentaba ningún
		// motor y la primera frase pagaba la apertura entera. Los
		// RUNNING_* (5, 10, 15) dejaron además de enviarse a las apps
		// con targetSdk 34+ y sus constantes están obsoletas; los
		// niveles que sí llegan y significan apuro son BACKGROUND
		// (40), MODERATE (60) y COMPLETE (80). Soltar instancias de
		// más se sigue haciendo desde UI_HIDDEN: ahí no se oye nada.
		if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return
		if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
			memoryTight = true
		}
		val current = engine?.language
		val drop = ArrayList<EvvEngine>()
		synchronized(engines) {
			for ((at, held) in engines) {
				if (at != current) drop.add(held)
			}
			for (held in drop) {
				engines.remove(held.language)
				dictionaryStates.forget(held)
				if (loadedEngine === held) loadedEngine = null
			}
			// 4.3: la instancia calentada tambien se va. Es una mas
			// de las que el sistema no cuenta como necesaria, y aqui
			// lo que se busca es justo que no sobre ninguna.
			val held = warm
			if (held != null) {
				warm = null
				warmLanguage = 0
				if (drop.none { it === held }) drop.add(held)
			}
		}
		// 4.4: cerrar va FUERA del candado, y aquí no estaba.
		// [EvvEngine.close] espera hasta dos segundos a que no quede
		// ningún lector dentro, y el candado de `engines` es el mismo
		// que necesita el hilo de síntesis en [ensureEngine]. Con el
		// candado puesto, un recorte de memoria en el hilo principal
		// bloqueaba el hilo de síntesis hasta dos segundos por motor,
		// y este servicio es justo el primero que el sistema mata
		// cuando va justo de memoria: un bloqueo de los dos lados, y
		// peor aún, el principal parado. [dropEverything] y
		// [onDestroy] ya lo hacían bien; este no.
		//
		// Y cada motor se cierra una sola vez. Antes se cerraba
		// dentro del candado y otra vez fuera, lo que era inocuo
		// porque [EvvEngine.close] es idempotente, pero sonaba a
		// fallo y no lo era.
		for (held in drop) held.close()
		dictionaryStates.clear()
		diag(
			"lifecycle", "event" to "trim_memory",
			"level" to level.toString(),
			"current" to (current?.let { Eci.bcp47Of(it) } ?: "?"),
			"dropped" to drop.size.toString()
		)
	}

	override fun onLowMemory() {
		diag("lifecycle", "event" to "low_memory")
		// 5.2: el nivel COMPLETE (80) de la plataforma está obsoleto
		// en las versiones nuevas, así que se nombra aquí en vez de
		// arrastrar el aviso del compilador. El valor no cambia: es
		// el más alto de la escala y lo que pide es soltarlo todo.
		onTrimMemory(TRIM_COMPLETE)
	}

	/** Lo que identifica al diccionario ya cargado en una instancia.
	 *  No guarda las entradas: la sustitución en texto se aplica
	 *  antes de cada emisión y no hay nada pendiente en el motor. */
	private class DictionaryState(
		val want: String?,
		val modified: Long,
		val size: Long,
		val revision: Long,
		val words: Map<String, String>
	)

	/**
	 * 4.1: el estado del diccionario también vive en [PerEngine], con
	 * su candado, porque lo vacía el hilo principal en onDestroy
	 * mientras el de síntesis lo lee y lo escribe.
	 */

	/** Compara la huella del diccionario y, si cambió, olvida las
	 *  reglas viejas y arma la lista de sustitución nueva. Ya no se
	 *  enseña nada al motor: hacerlo duplicaba la aplicación porque
	 *  la pronunciación de una entrada puede contener otra clave. */
	private fun loadDictionary(target: EvvEngine, s: Settings) {
		// Lo que hay en lugar de lo que se pidió. Un diccionario
		// elegido antes de esta compilación no está donde dicen
		// los ajustes hasta que el teléfono se haya desbloqueado
		// una vez, y recordar solo lo que se leyó significa que se
		// recoge cuando llega en lugar de darse por escrito como
		// cargado.
		// Otra instancia no trae el diccionario cargado en la anterior.
		if (target !== loadedEngine) {
			loadedDictionary = null
			loadedDictionaryModified = 0L
			loadedDictionarySize = -1L
			loadedDictionaryRevision = 0L
			loadedWords = emptyMap()
		}
		// 4.3: el archivo y la lista de sustitución se traen ya
		// hechos de la caché por idioma cuando se pudo preparar antes.
		// La primera vez, o si algo cambió entre medias, se leen aquí
		// mismo, que es el camino caro y por eso se procura dejar
		// hecho en segundo plano en [prepareDictionary]. La comparación
		// (fecha, tamaño y el contador de [SettingsSignal]) está en
		// [readDictionary], que es el único sitio que la hace.
		val ready = readDictionary(s, target.language)
		val want = ready?.path
		val words = ready?.words ?: s.dictionaryWords
		// 5.4.2: las palabras propias también van por la sustitución
		// en texto cuando no hay archivo de diccionario. Antes se
		// enseñaban solo al motor, con lo que se saltaban las
		// protecciones de la sustitución (mayúsculas estrictas,
		// números puros, letras sueltas) y podían sonar distinto que
		// dentro de un diccionario. Si no hay ni archivo ni palabras
		// propias, la lista queda vacía y no hay nada que enseñar.
		val wordSubs = if (words.isEmpty()) {
			emptyList()
		} else {
			val cached = wordSubEntries
			if (cached != null && cached.first === words) {
				cached.second
			} else {
				words.entries.map { it.toPair() }.also { wordSubEntries = words to it }
			}
		}
		subEntries = ready?.substitutions ?: wordSubs
		subHasVoiceSelect = ready?.voiceSelect ?: Dictionaries.hasVoiceSelect(subEntries)
		val modified = ready?.modified ?: 0L
		val size = ready?.size ?: -1L
		val revision = ready?.revision ?: SettingsSignal.dictionaryRevision
		val state = dictionaryStates.state(target)
		// 4.3: el atajo incluye el contador. Con la fecha y el tamaño
		// solos, importar dos veces el mismo archivo con las mismas
		// entradas pasaba por "nada ha cambiado" y la emisión seguía
		// con el diccionario viejo, que es lo que se oía.
		if (state != null && state.want == want && state.modified == modified &&
			state.words == words &&
			target === loadedEngine && want == loadedDictionary &&
			size == loadedDictionarySize && revision == loadedDictionaryRevision
		) return
		if (state == null || state.want != want || state.modified != modified ||
			state.words != words || size != loadedDictionarySize ||
			revision != loadedDictionaryRevision
		) {
			target.forgetDictionaries()
			// 4.1: lo que hubiera enseñado una versión anterior se
			// olvida de verdad cuando el archivo o las palabras
			// propias cambian. La sustitución en texto cubre lo nuevo
			// desde la primera emisión, así que purgar no se nota.
			for (one in synchronized(engines) { engines.values.toList() }) one.forgetDictionaries()
			// Solo se cuentan para el diagnóstico: las entradas ya no
			// se enseñan al motor.
			val fileEntries = ready?.entries.orEmpty()
			if (words.isNotEmpty()) Timber.i("diccionario: ${words.size} palabras propias")
			if (fileEntries.isNotEmpty()) Timber.i("diccionario: ${fileEntries.size} entradas")
			diag(
				"dictionary", "event" to "loaded",
				"language" to (Eci.bcp47Of(target.language) ?: "?"),
				"engine" to identity(target),
				"present" to (want != null).toString(),
				"file_entries" to fileEntries.size.toString(),
				"words" to words.size.toString(),
				// 5.4: -1 no es un tamaño: es "no hay archivo o no se
				// pudo leer". Escribirlo como `unknown` evita que el
				// registro parezca decir que el diccionario pesa -1
				// bytes (que es lo que salía 102 veces).
				"bytes" to if (size >= 0) size.toString() else "unknown",
				"revision" to revision.toString(),
				"voice_select" to subHasVoiceSelect.toString()
			)
			dictionaryStates.setState(
				target,
				DictionaryState(want, modified, size, revision, words)
			)
			loadedDictionary = want
			loadedDictionaryModified = modified
			loadedDictionarySize = size
			loadedDictionaryRevision = revision
			loadedWords = words
			loadedEngine = target
		}
	}

	override fun onStop() {
		diag(
			"lifecycle", "event" to "stop",
			"service" to serviceNumber.toString()
		)
		if (activeSession?.cancel() == true) TtsHealth.cancelled()
		// 5.0: nada más de esta emisión sale hacia el framework. La
		// marca de la sesión ya lo impide en el siguiente paso, pero
		// el callback se abandona aquí para que una parada no espere
		// ni un bloque más.
		activeOutput?.abandon()
		// 5.4: aquí NO se borra [dictionaryStates].
		//
		// Lo que guarda no es una huella de la voz: es la del
		// diccionario cargado, y [Emission] vuelve a aplicar los ocho
		// parámetros antes de cada trozo de todas formas. Al borrarlo
		// en cada parada, la siguiente emisión volvía a "cargar" el
		// diccionario desde cero (102 `dictionary loaded` para 35
		// servicios en el registro real) y rehacía los patrones de
		// sustitución aunque nada hubiera cambiado. La voz ya se
		// repone sola; el diccionario no tiene por qué olvidarse.
		val live = ArrayList<EvvEngine>()
		synchronized(engines) { live.addAll(engines.values) }
		// No escribir parámetros aquí: el motor todavía puede estar
		// desenrollando su callback de audio. La siguiente emisión aplica
		// el plan completo cuando el motor vuelve a aceptar escrituras.
		for (held in live) {
			held.stop()
		}
		diag(
			"engine", "event" to "stop_abort",
			"engines" to live.size.toString(),
			"service" to serviceNumber.toString()
		)
	}

	override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
		if (request == null || callback == null) return
		keepForeground()
		// 4.3: lo pendiente del reinicio se atiende aqui, antes de
		// tocar nada de la emision. El framework no llama a este
		// metodo hasta que la anterior ha terminado, asi que es el
		// unico momento en que se puede soltar el motor sin cortar
		// una frase: imported un diccionario o una copia, o cambio
		// la tasa, y la emision siguiente ya sale con todo lo nuevo
		// y sin un solo error de sintesis por el camino.
		takeReload()
		// 5.0: cada emisión nace con su propia marca. Una parada que
		// llegue durante la emisión siguiente ya no la mata al nacer.
		val session = TtsSession(sessionIds.incrementAndGet())
		activeSession = session
		TtsHealth.utterance()
		TtsHealth.speaking(true)
		val output = TtsOutput(callback)
		activeOutput = output
		speaking = true
		try {
			synthesize(request, output, session)
		} catch (e: Throwable) {
			Timber.e(e, "la emision ha muerto sin terminar")
			TtsHealth.failed(e.message ?: e.javaClass.simpleName)
			if (!session.watchdog) output.fail(TextToSpeech.ERROR_SYNTHESIS)
		} finally {
			// 5.0: toda salida deja una sola terminal. Si el servicio
			// paró, se abandona sin tocar el callback; si el watchdog
			// saltó, se cierra con error para que el cliente no espere.
			if (!output.doneOrFailed) {
				if (session.alive || session.watchdog) output.fail(TextToSpeech.ERROR_SYNTHESIS)
				else output.abandon()
			}
			speaking = false
			TtsHealth.speaking(false)
			activeOutput = null
			activeSession = null
		}
	}

	private fun synthesize(request: SynthesisRequest, output: TtsOutput, session: TtsSession) {
		val rawText = request.charSequenceText?.toString().orEmpty()
		val s = settings
		// El idioma lo manda el ajuste cuando hay uno elegido: así
		// cambiarlo se oye en la siguiente emisión, sin
		// reseleccionar el motor en TalkBack ni en los ajustes de
		// texto a voz. Sin ajuste manda lo pedido, como siempre.
		// 4.1: aquí sigue mandando solo el idioma **elegido**, no el
		// que sale del sistema. El ajuste idiomático es "qué voz
		// quieres", y quien no ha tocado nada lo que quiere es la
		// que pide el lector de pantalla. El idioma del sistema solo
		// entra cuando no hay nada pedido ni nada elegido
		// ([matchLanguage]), que es donde antes caía el inglés.
		val forced = s?.defaultLanguage?.takeIf { it != 0 && EvvEngine.available.contains(it) }
		val found = matchLanguage(request.language, request.country)
		if (found == null && forced == null) {
			output.fail(TextToSpeech.ERROR_NOT_INSTALLED_YET)
			return
		}
		// La voz es la que dice la pantalla de ajustes, no la que
		// nombró el que llama. Un lector que se conectó mientras
		// Reed estaba seleccionado sigue pidiendo Reed sin importar
		// cuántas veces cambie el ajuste debajo. El nombre solo
		// decide el idioma cuando no hay ajuste que mande.
		val wanted = findVoice(request.voiceName)
		recordHistory(rawText)
		// 4.0: detección automática híbrida (ML Kit con Google,
		// heurística sin Google). Si está segura, manda sobre
		// el ajuste y lo pedido: esa es su gracia. Si duda y la
		// detección está activada, manda lo pedido en vivo (el
		// idioma real de la pantalla) antes que el ajuste fijado,
		// que puede ser un resto viejo. Apagada, todo como siempre.
		val autoOn = s?.autoDetectLanguage == true
		val detectedTag = if (autoOn && rawText.isNotBlank()) AutoLang.detect(rawText) else null
		val detected = detectedTag?.let { tag ->
			AutoLang.preferredEngine(tag, request.country, s?.autoVariantFor(tag) ?: 0)
		}
		// Traza sin el texto (puede ser privado): etiqueta, motor
		// elegido, ajuste fijado y pedido, para diagnosticar.
		if (autoOn) Timber.d("autodetecta: $detectedTag -> $detected (fijado=$forced pedido=$found)")
		val language = detected
			?: (if (autoOn) null else forced)
			?: wanted?.language
			?: found?.first
			?: forced
			// 4.3: aquí se acababa la cadena y se pedía al motor un
			// idioma que no existe. Con la autodetección encendida y la
			// app o el aparato en un idioma que el motor no trae --
			// polaco, portugués, ruso, turco -- no hay nada que case,
			// y el resultado era un 0 que se iba directo a `eciNewEx`.
			// Eso es un crash nativo, no un error de Kotlin: el proceso
			// se muere, Android reinicia el servicio, y lo que ve el
			// usuario es que la voz "se para" y vuelve. Por eso no
			// bastaba con devolver null: había que no llegar ahí.
			?: run {
				Timber.e("ningún idioma sirve para este texto; se usa el que haya")
				EvvEngine.available.firstOrNull() ?: 0
			}
		// Y la misma puerta por si algo se cuela por otro lado: al
		// motor solo se le pide un idioma que declarar tener. La lista
		// sale de `eciGetAvailableLanguages`, o sea que es la del
		// binario y no una suposición.
		val safe = if (language != 0 && EvvEngine.available.contains(language)) language
		else {
			// `forced` ya viene filtrado contra la misma lista (linea
			// de arriba), así que aquí solo hace falta el primer idioma
			// que el binario declare.
			val fallback = forced ?: EvvEngine.available.firstOrNull()
			if (fallback == null) {
				output.fail(TextToSpeech.ERROR_NOT_INSTALLED_YET)
				return
			}
			if (language != 0) {
				Timber.e("el idioma 0x%08x no está en el motor; se usa 0x%08x", language, fallback)
			}
			fallback
		}
		diag(
			"emission", "event" to "language",
			"session" to session.id.toString(),
			"chosen" to (Eci.bcp47Of(safe) ?: "?"),
			"requested" to (normalise(request.language.orEmpty()).ifEmpty { "?" }),
			"country" to (request.country?.ifEmpty { "?" } ?: "?"),
			"voice_name" to (request.voiceName ?: "?"),
			"forced" to (forced?.let { Eci.bcp47Of(it) } ?: "?"),
			"detected" to (detectedTag ?: "?"),
			"detected_engine" to (detected?.let { Eci.bcp47Of(it) } ?: "?"),
			"auto" to autoOn.toString()
		)
		TtsHealth.speaking(true, safe)
		val ready = readyEngine(safe, s, request.speechRate, request.pitch)
		if (ready == null) {
			diag(
				"error", "event" to "emission_unprepared",
				"language" to (Eci.bcp47Of(safe) ?: "?"),
				"session" to session.id.toString()
			)
			output.fail(TextToSpeech.ERROR_SERVICE)
			return
		}
		val target = ready.engine
		diag(
			"emission", "event" to "begin",
			"session" to session.id.toString(),
			"language" to (Eci.bcp47Of(safe) ?: "?"),
			"voice" to ready.plan.voice.toString(),
			"chars" to rawText.length.toString(),
			"auto" to autoOn.toString(),
			"mix" to (s != null && s.mixEnabled && s.mixPartner != s.voice).toString(),
			"warm" to servedWarm.toString()
		)
		// Abrir motor y diccionario puede tardar; el watchdog cuenta
		// desde el último avance real, no desde que nació la tarea.
		session.progress()
		if (engineLost) {
			TtsHealth.recovered()
			engineLost = false
		}
		// Foto de ajustes para toda la emisión: leer prefs a mitad
		// de un texto largo movía pausas y voz a mitad de frase.
		val doEmojis = s?.processEmojis == true
		val doUnicode = s?.readUnicodeSymbols == true
		val punctOn = s?.punctEnabled == true
		val punctLevel = s?.punctLevel ?: 0
		val skipParens = s?.skipParentheses == true
		val numOn = s?.numberForceEnabled == true
		val numGroup = s?.numberGroup ?: 1
		val pauses = s?.pauses ?: Pauses.KEEP
		val pauseMs = s?.pauseDurationMs ?: Settings.DEFAULT_PAUSE_MS
		// 4.2: con el nivel NINGUNA la puntuación se quita de verdad
		// (ver [Punctuation.silence]) y el final de cada trozo es una
		// pausa en vez de un punto. Un punto ahí se oía, y va justo
		// donde se oye el final de cada etiqueta. Sin ese nivel, el
		// texto entra tal cual y el motor hace lo de siempre.
		val silentPunct = punctOn && punctLevel == Punctuation.NONE
		val terminal = if (silentPunct) "`p${pauseMs.coerceIn(1, 2000)}" else "."
		val prosody = Prosody.natural()
		val mixWith = s?.takeIf { it.mixEnabled && it.mixPartner != it.voice }
		val fx = s?.let {
			val bands = if (it.eqEnabled) it.eqBands() else null
			val reverb = if (it.reverbEnabled) it.reverbLevel else 0
			val bass = if (it.eqEnabled) it.bassDb else 0
			val presence = if (it.eqEnabled) it.presenceDb else 0
		val echo = if (it.echoEnabled) it.echoLevel else 0
		AudioFx.Chain(
				rateHz = target.sampleRateHz,
				eqDb = bands,
				reverbMix = reverb,
				bassDb = bass,
				presenceDb = presence,
				echoMix = echo,
				compress = it.compressorEnabled,
				boostPercent = it.boostPercent,
				gate = it.gateEnabled,
				tremoloMix = it.tremoloLevel,
				chorus = it.chorusEnabled,
				crusher = it.crusherEnabled,
				flanger = it.flangerEnabled,
				exciterMix = it.exciterLevel,
				warmth = it.warmthEnabled,
				hdEnhance = it.hdEnhance,
				deesser = it.deesserEnabled,
				body = it.bodyEnabled,
				echoDelayMs = it.echoTimeMs,
				tremoloRateHz = it.tremoloRate / 10.0,
				phaser = it.phaserEnabled,
				vibratoMix = it.vibratoLevel,
				ringmodMix = it.ringmodLevel,
				fuzzMix = it.fuzzLevel,
				wahMix = it.wahLevel,
				maximizerMix = it.maximizerLevel,
				tapeMix = it.tapeMix,
				subBassMix = it.subBassMix,
				reverbSize = it.reverbSize,
				crusherBits = it.crusherBits,
				deesserDepth = it.deesserDepth,
				telephone = it.telephoneEnabled,
				normalizer = it.normalizerEnabled,
				highpass = it.highpassEnabled,
				drive = it.driveEnabled
			).takeUnless { chain -> chain.idle }
		}
		diag(
			"effects", "event" to "chain",
			"session" to session.id.toString(),
			"active" to (fx != null).toString(),
			"rate" to target.sampleRateHz.toString(),
			"eq" to (s?.eqEnabled == true).toString(),
			"reverb" to (s?.reverbEnabled == true).toString(),
			"echo" to (s?.echoEnabled == true).toString(),
			"compress" to (s?.compressorEnabled == true).toString(),
			"gate" to (s?.gateEnabled == true).toString(),
			"hd" to (s?.hdEnhance == true).toString(),
			"telephone" to (s?.telephoneEnabled == true).toString(),
			"normalizer" to (s?.normalizerEnabled == true).toString(),
			"highpass" to (s?.highpassEnabled == true).toString(),
			"drive" to (s?.driveEnabled == true).toString(),
			"boost" to (s?.boostPercent ?: 100).toString()
		)
		// 5.0: las transformaciones van por ventana, no sobre el
		// texto entero. La primera frase paga solo su trozo y suena
		// antes; la memoria ya no crece con el tamaño del correo.
		val pipeline = TtsTextPipeline(
			emojis = doEmojis,
			unicodeSymbols = doUnicode,
			punctOn = punctOn,
			punctLevel = punctLevel,
			skipParens = skipParens,
			numbersOn = numOn,
			numberGroup = numGroup,
			silentPunct = silentPunct,
			pauseMs = pauseMs,
			tag = Eci.localeOf(safe)?.first ?: "eng"
		)
		val pace = TtsPacer(target.sampleRateHz * BYTES_PER_SAMPLE)
		// 5.3: una sola línea por proceso con el plan completo que va
		// a sonar en la primera frase. Es lo que deja ver si la
		// primera emisión salió del motor calentado y con qué ocho
		// valores, sin depender de que el motor conteste al leerlos.
		if (firstEmissionLogged.compareAndSet(false, true)) {
			Timber.i(
				"primera emisión: idioma=0x%08x (%s), calentado=%s, tono=%d, velocidad=%d, plan=%s",
				safe, Eci.bcp47Of(safe) ?: "?", servedWarm, request.pitch,
				request.speechRate, ready.plan.params
			)
			diag(
				"prosody", "event" to "first_emission",
				"language" to (Eci.bcp47Of(safe) ?: "?"),
				"warm" to servedWarm.toString(),
				*ready.plan.diagnosticPairs().toTypedArray()
			)
		}
		var sawVoiceSelect = subHasVoiceSelect
		// Terminación única del callback: el framework no admite
		// done() tras error() ni dos terminales. Las ayudas de
		// abajo no tocan el callback salvo audio; el
		// rechazo del motor llega como SpeakFailed y el error se
		// pone aquí una sola vez. Parada o callback soltado es
		// interrupción silenciosa (falso), no error.
		val completed = try {
			if (mixWith != null) {
				speakAlternatingStream(
					request, ready, mixWith, rawText, pipeline, output, pace, session, fx,
					prosody, pauses, pauseMs, terminal, autoOn,
					onVoiceSelect = { sawVoiceSelect = true }
				)
			} else {
				speakStream(
					ready, rawText, pipeline, output, pace, session, fx,
					prosody, pauses, pauseMs, terminal,
					s, request.speechRate, request.pitch, autoOn, request.country,
					onVoiceSelect = { sawVoiceSelect = true }
				)
			}
		} catch (e: SpeakFailed) {
			Timber.e("el motor rechazó la emisión")
			diagError(
				"speak_rejected", e,
				"session" to session.id.toString(),
				"language" to (Eci.bcp47Of(safe) ?: "?")
			)
			// 4.2: la instancia se va. Un motor que rechaza lo que se
			// le pide está en mal estado, y dejarlo puesto es lo que
			// hace que un motor se quede mudo para siempre en vez de
			// para una frase. La siguiente emisión abre uno nuevo, con
			// su diccionario y sus parámetros, y no se nota más que
			// un instante de silencio.
			forget(target)
			remember(pace)
			TtsHealth.failed("motor rechazó la emisión")
			output.fail(TextToSpeech.ERROR_SYNTHESIS)
			return
		} catch (e: Exception) {
			Timber.e(e, "emisión interrumpida")
			diagError(
				"emission_failed", e,
				"session" to session.id.toString(),
				"language" to (Eci.bcp47Of(safe) ?: "?")
			)
			remember(pace)
			TtsHealth.failed(e.message ?: e.javaClass.simpleName)
			if (!session.cancelled) output.fail(TextToSpeech.ERROR_SYNTHESIS)
			return
		}
		remember(pace)
		logLatency(pace, output)
		// Cortada a mitad (el caso normal de un lector de pantalla).
		// 5.4: la voz se repone sola porque [Emission] la vuelve a
		// aplicar antes del siguiente trozo; lo que no se toca aquí es
		// el progreso del diccionario, que no tiene nada que ver con
		// una parada.
		if (!completed) {
			diag(
				"emission", "event" to "stopped",
				"session" to session.id.toString(),
				"cancelled" to session.cancelled.toString(),
				"watchdog" to session.watchdog.toString(),
				"lost" to engineLost.toString(),
				"bytes" to output.handedBytes.toString()
			)
			if (engineLost && !session.cancelled) TtsHealth.failed("motor perdido")
			// Si la parada vino de fuera, el framework ya soltó la
			// pista: no se le manda nada. Si fue el watchdog, el motor
			// se perdió o cualquier otra cosa, se cierra con error para
			// que el cliente no espere.
			if (session.watchdog || engineLost || session.alive) {
				output.fail(TextToSpeech.ERROR_SYNTHESIS)
			}
			return
		}
		diag(
			"emission", "event" to "end",
			"session" to session.id.toString(),
			"language" to (Eci.bcp47Of(safe) ?: "?"),
			"voice" to ready.plan.voice.toString(),
			"chars" to rawText.length.toString(),
			"bytes" to output.handedBytes.toString(),
			"ahead_ms" to pace.ahead().toString(),
			"voice_select" to sawVoiceSelect.toString(),
			"lost" to engineLost.toString()
		)
		if (output.finishOk(target.sampleRateHz)) TtsHealth.completed()
	}

	/** El motor rechazó un trozo o una voz: la emisión termina en
	 *  error, puesto una sola vez por quien llama. */
	private class SpeakFailed : Exception()

	/**
	 * 4.4: una etapa del texto que, si falla, deja pasar el texto sin
	 * transformar en vez de tumbar la emisión.
	 *
	 * Se coge `Throwable` y no `Exception` a propósito, y el motivo es
	 * el tamaño: [Numbers.apply], [TextFixes.apply] y
	 * [EmojiSpeech.describeLang] hacen una copia del texto entero cada
	 * una, y sobre un texto enorme la que falla es la que se queda
	 * sin memoria. Un `OutOfMemoryError` es un `Error`, así que el
	 * `catch (e: Exception)` de la emisión no lo habría cogido, y el
	 * resultado era el hilo de síntesis muerto con el callback
	 * abierto.
	 *
	 * Perder una mejora del texto es una molestia; perder la emisión
	 * es que el motor deje de hablar. El orden de [step] es además el
	 * mismo de antes, así que una etapa que se cae solo deja de
	 * aplicarse la suya y las siguientes siguen igual.
	 */
	private inline fun step(input: String, transform: (String) -> String): String =
		try {
			val out = transform(input)
			if (out.isEmpty() && input.isNotEmpty()) {
				Timber.w("una etapa devolvió el texto vacío; se lee sin ella")
				input
			} else {
				out
			}
		} catch (e: Throwable) {
			Timber.e(e, "una etapa del texto falló; se lee sin ella")
			input
		}

	/** Habla [numbered] por ventanas: cada ventana se sustituye,
	 *  se parte en trozos y se habla antes de mirar la siguiente.
	 *  La primera ventana tarda milisegundos venga un párrafo o un
	 *  libro, así que no hay pausa ni cola antes de empezar; las
	 *  pausas de puntuación suenan igual porque cada trozo lleva
	 *  las suyas como siempre. Contesta verdadero si se habló todo
	 *  y falso si se pidió silencio o el framework soltó el
	 *  callback a mitad. El rechazo del motor lanza [SpeakFailed].
	 *  [onVoiceSelect] avisa si algún trozo trajo `` `vN `` para
	 *  re-aplicar la voz en la siguiente emisión. */
	private fun speakStream(
		first: Ready, rawText: String, pipeline: TtsTextPipeline,
		output: TtsOutput, pace: TtsPacer, session: TtsSession, fx: AudioFx.Chain?,
		prosody: String, pauses: Int, pauseMs: Int, terminal: String,
		s: Settings?, reqRate: Int, reqPitch: Int,
		autoOn: Boolean, reqCountry: String?,
		onVoiceSelect: () -> Unit
	): Boolean {
		val it = TextPieces.windows(rawText).iterator()
		if (!it.hasNext()) return true
		var ready = first
		var target = ready.engine
		var pending = it.next()
		while (true) {
			if (!session.alive) return false
			val lastWindow = !it.hasNext()
			// 5.0: primero se transforma la ventana y luego se elige
			// motor con lo que de verdad se va a decir.
			val prepared = pipeline.apply(pending)
			// 4.0: la ventana que cambia de idioma cambia de motor
			// al instante, con su voz y diccionario listos. Solo
			// heurística aquí (instantánea): ML Kit frenaría entre
			// ventanas y se cortaría el flujo.
			if (autoOn && prepared.isNotBlank()) {
				AutoLang.heuristic(prepared)?.let { tag ->
					AutoLang.preferredEngine(tag, reqCountry, s?.autoVariantFor(tag) ?: 0)
				}?.takeIf { it != target.language }?.let { lang ->
					readyEngine(lang, s, reqRate, reqPitch)?.let {
						ready = it
						target = it.engine
					}
				}
			}
			// 4.3: con el interruptor de ignorar códigos encendido, el
			// reinicio de prosodia no se pasa al diccionario (no hay
			// códigos que neutralice) y lo que salga --texto propio o
			// entrada de diccionario-- se limpia antes de hablar. La
			// palabra se dice entera y la voz no se mueve.
			val ignoreTags = s?.ignoreVoiceTags == true
			// Se prepara el plan antes de sustituir el diccionario y se
			// vuelve a aplicar antes de cada trozo en [Emission.speak].
			// Ambos caminos usan los mismos reintentos para no reutilizar
			// parámetros antiguos si el motor aún está abortando.
			val guard = if (ignoreTags) "" else Emission.prepare(target, ready.plan)
				?: run {
					postponed(target, ready.plan, session)
					return false
				}
			val sub = Dictionaries.substitute(prepared, subEntries, guard)
			val said = if (ignoreTags) Prosody.stripVoiceCodes(sub) else sub
			if (!ignoreTags && Dictionaries.hasVoiceSelectText(said)) onVoiceSelect()
			if (said.isNotBlank()) {
				val pieces = TextPieces.split(said)
				for ((at, raw) in pieces.withIndex()) {
					if (!session.alive) return false
					val lastOverall = lastWindow && at == pieces.lastIndex
					// 4.0: punto terminal por trozo. Sin marca final el
					// motor sostiene la frase y sube como pregunta; con
					// el punto cada trozo cae como enunciado.
					val dotted = TextFixes.terminalDot(raw, terminal)
					val body = Pauses.apply(dotted, pauses, lastOverall, pauseMs, pauseMs)
					if (!output.open(target.sampleRateHz)) {
						target.stop()
						return false
					}
					// 5.3: el plan se aplica **otra vez** aquí, y el
					// reinicio se compone con lo que el motor acaba de
					// aceptar. No se hereda nada del trozo anterior.
					when (Emission.speak(
						target, ready.plan, body,
						onReset = { reset ->
							diag(
								"prosody", "event" to "reset",
								"session" to session.id.toString(),
								"voice" to ready.plan.voice.toString(),
								"codes" to reset.trim()
							)
						}
					) { reset ->
						reset + prosody + body
					}) {
						Emission.Result.Spoken -> Unit
						Emission.Result.Empty -> continue
						Emission.Result.Rejected -> {
							Timber.e("el motor rechazó ${body.length} caracteres")
							throw SpeakFailed()
						}
						Emission.Result.NotApplied -> {
							postponed(target, ready.plan, session)
							return false
						}
					}
					if (!pump(
							target, output, pace, session,
							fadeIn = true, fadeOutLast = !lastOverall, fx = fx
						)
					) {
						return false
					}
				}
			}
			if (lastWindow) break
			pending = it.next()
		}
		return true
	}

	/** Una emisión que no pudo aplicar su plan no se habla: se deja
	 *  constancia y se pospone, que es preferible a sonar con la voz
	 *  de otra emisión o con un `-1`. */
	private fun postponed(target: EvvEngine, plan: VoicePlan.Plan, session: TtsSession) {
		Timber.e("no se pudo aplicar el plan de voz; la emisión se pospone")
		diag(
			"error", "event" to "emission_postponed",
			"session" to session.id.toString(),
			"language" to (Eci.bcp47Of(target.language) ?: "?"),
			"complete" to plan.isComplete.toString(),
			"voice" to plan.voice.toString()
		)
	}

	/**
	 * Habla por turnos de palabras, por ventanas: un turno la voz
	 * A, el siguiente la B, y así. Cada turno lleva su propio
	 * [VoicePlan] completo y lo aplica antes de hablar, así que la
	 * voz alterna no depende de lo que dejara el turno anterior.
	 *
	 * Por ventanas para el mismo arranque instantáneo que
	 * [speakStream]: la alternancia sigue con un contador global,
	 * así que la voz no se reinicia en cada ventana (los límites de
	 * turno caen en límites de ventana, diferencia inaudible frente
	 * a partir el texto entero). Contesta como [speakStream] y lanza
	 * [SpeakFailed] igual.
	 */
	private fun speakAlternatingStream(
		request: SynthesisRequest, first: Ready,
		s: Settings, rawText: String, pipeline: TtsTextPipeline,
		output: TtsOutput, pace: TtsPacer, session: TtsSession, fx: AudioFx.Chain?,
		prosody: String, pauses: Int, pauseMs: Int, terminal: String,
		autoOn: Boolean,
		onVoiceSelect: () -> Unit
	): Boolean {
		val windows = TextPieces.windows(rawText).iterator()
		if (!windows.hasNext()) return true
		var ready = first
		var target = ready.engine
		// El plan de cada voz se arma una vez por ventana; el preset
		// se lee del motor, así que al cambiar de instancia se tira.
		val plans = HashMap<Int, VoicePlan.Plan>()
		var pending = pipeline.apply(windows.next())
		var turn = 0
		// 4.0: punto terminal por ventana (no por turno: los turnos
		// parten frases a medias y un punto ahí cortaría el flujo).
		pending = TextFixes.terminalDot(pending, terminal)
		while (true) {
			if (!session.alive) return false
			val lastWindow = !windows.hasNext()
			// Como en [speakStream]: la ventana en otro idioma cambia
			// de motor al instante (heurística, sin frenar).
			if (autoOn && pending.isNotBlank()) {
				AutoLang.heuristic(pending)?.let { tag ->
					AutoLang.preferredEngine(tag, request.country, s.autoVariantFor(tag))
				}?.takeIf { it != target.language }?.let { lang ->
					readyEngine(lang, s, request.speechRate, request.pitch)?.let {
						ready = it
						target = it.engine
						plans.clear()
					}
				}
			}
			val chunks = TextPieces.alternateWords(pending, s.mixInterval)
			for ((at, chunk) in chunks.withIndex()) {
				if (!session.alive) return false
				if (chunk.isBlank()) continue
				val lastOverall = lastWindow && at == chunks.lastIndex
				val voiceIdx = if (turn % 2 == 0) s.voice else s.mixPartner
				turn++
				val plan = plans.getOrPut(voiceIdx) {
					VoicePlan.plan(
						voice = voiceIdx,
						shape = s.shape(voiceIdx),
						preset = target.presetShape(voiceIdx),
						requestRate = request.speechRate,
						requestPitch = request.pitch,
						useSystemSpeed = s.useSystemSpeed,
						volumeGain = s.volume,
						useSystemVolume = s.useSystemVolume
					)
				}
				val ignoreTags = s.ignoreVoiceTags
				if (!output.open(target.sampleRateHz)) {
					target.stop()
					return false
				}
				when (Emission.speak(
					target, plan, chunk,
					onReset = { reset ->
						diag(
							"prosody", "event" to "reset",
							"session" to session.id.toString(),
							"voice" to plan.voice.toString(),
							"codes" to reset.trim()
						)
					}
				) { reset ->
					// 4.3: el interruptor de ignorar códigos deja el
					// texto limpio y sin reinicio delante, porque no
					// hay anotaciones que neutralizar.
					val sub = Dictionaries.substitute(
						chunk, subEntries, if (ignoreTags) "" else reset
					)
					val said = if (ignoreTags) Prosody.stripVoiceCodes(sub) else sub
					if (said.isBlank()) {
						""
					} else {
						if (!ignoreTags && Dictionaries.hasVoiceSelectText(said)) onVoiceSelect()
						val body = Pauses.apply(said, pauses, lastOverall, pauseMs, pauseMs)
						reset + prosody + body
					}
				}) {
					Emission.Result.Spoken -> Unit
					Emission.Result.Empty -> continue
					Emission.Result.Rejected -> {
						Timber.e("el motor rechazó la voz alterna $voiceIdx")
						throw SpeakFailed()
					}
					Emission.Result.NotApplied -> {
						postponed(target, plan, session)
						return false
					}
				}
				if (!pump(
						target, output, pace, session,
						fadeIn = true, fadeOutLast = !lastOverall, fx = fx
					)
				) {
					return false
				}
			}
			if (lastWindow) break
			pending = TextFixes.terminalDot(pipeline.apply(windows.next()), terminal)
		}
		return true
	}

	/** Entrega las muestras de un trozo a medida que llegan, y
	 *  contesta si el que llama puede seguir al siguiente.
	 *  La entrada del trozo siempre sube poco a poco (15 ms) y,
	 *  con [fadeOutLast], la salida del trozo baja poco a poco
	 *  (12 ms): cada trozo es una emisión propia del motor y sin
	 *  esos dos fundidos la unión entre trozos y el arranque en
	 *  44k/48k sonaban como golpecitos.
	 *  [fx] lleva ecualizador y reverb cuando están en vigor. */
	private fun pump(
		target: EvvEngine,
		output: TtsOutput,
		pace: TtsPacer,
		session: TtsSession,
		fadeIn: Boolean = true,
		fadeOutLast: Boolean = false,
		fx: AudioFx.Chain? = null
	): Boolean {
		// El límite lo declara el framework. Antes se hacía
		// coerceIn(MIN_CHUNK, MAX_CHUNK) y, si el máximo era menor
		// que MIN_CHUNK, se le entregaba igualmente de más.
		val limit = output.maxBufferSize
		if (limit < 2) {
			Timber.e("el framework no dio tamaño de búfer")
			return false
		}
		val size = (minOf(limit, MAX_CHUNK) and 1.inv()).coerceAtLeast(2)
		if (bufferA.size < size) bufferA = ByteArray(size)
		if (bufferB.size < size) bufferB = ByteArray(size)
		var natural = false
		try {
			if (!session.alive) return false
			var current = bufferA
			var next = bufferB
			var n = target.read(current)
			if (n == EvvEngine.WEDGED) {
				Timber.e("el motor no entregó muestras y se suelta")
				engineLost = true
				forget(target)
				return false
			}
			if (n <= 0) {
				natural = session.alive
				return natural
			}
			var first = true
			while (session.alive && n > 0) {
				val m = target.read(next)
				if (m == EvvEngine.WEDGED) {
					Timber.e("el motor no entregó muestras y se suelta")
					engineLost = true
					forget(target)
					return false
				}
				val isLast = m <= 0
				if (!output.open(target.sampleRateHz)) {
					target.stop()
					return false
				}
				if (first) {
					first = false
					if (fadeIn) AudioFx.fadeIn(current, n, target.sampleRateHz)
				}
				AudioFx.gain(current, n, target.volumeGain)
				fx?.process(current, n)
				if (isLast && fadeOutLast) {
					AudioFx.fadeOut(current, n, target.sampleRateHz)
				}
				if (!output.audio(current, 0, n)) {
					target.stop()
					return false
				}
				pace.handed(n)
				session.progress()
				hold(pace, session)
				if (isLast) {
					natural = session.alive
					break
				}
				val tmp = current
				current = next
				next = tmp
				n = m
			}
			bufferA = current
			bufferB = next
			return natural
		} finally {
			// Parar dos veces no hace daño: `stop` solo pone la marca
			// de aborto y avisa, y `speak` la borra al empezar.
			if (!natural) target.stop()
		}
	}

	private fun remember(pace: TtsPacer) {
		if (pace.handedMs() > mostAudioHandedMs) mostAudioHandedMs = pace.handedMs()
	}

	/**
	 * Espera hasta que el audio ya entregado casi haya alcanzado
	 * el ritmo de reproducción.
	 *
	 * Este motor es mucho más rápido que el habla que hace -- un
	 * segundo y un tercio de audio sale en doce milisegundos-- y
	 * Android toma cada byte de él sin quejarse y lo encola.
	 * Así que un lector que desliza rápido tiene cinco o seis
	 * elementos sintetizados y en cola mientras el primero aún
	 * se está hablando, y cuando entonces pide silencio se va
	 * toda la cola, que son elementos que nunca se escucharon.
	 * No correr más adelantado que [LEAD_MS] deja como máximo una
	 * emisión en el aire, para que el silencio llegue donde el
	 * lector realmente está.
	 *
	 * La espera es en rebanadas para que una parada se note al
	 * instante en lugar de al final de ella.
	 */
	private fun hold(
		pace: TtsPacer,
		session: TtsSession,
		leadMs: Long = LEAD_MS,
		sliceMs: Long = SLICE_MS
	) {
		while (session.alive) {
			val ahead = pace.ahead() - leadMs
			if (ahead <= 0) return
			try {
				Thread.sleep(minOf(ahead, sliceMs))
			} catch (stopping: InterruptedException) {
				Thread.currentThread().interrupt()
				return
			}
		}
	}

	/**
	 * 4.3: deja la latencia real en el registro.
	 *
	 * El motivo de existir es que [LEAD_MS] no se puede bajar a
	 * ciegas. Bajar el tope de.audio adelantado reduce el retardo y
	 * aumenta el riesgo de que la pista se seque, y ese riesgo no se
	 * ve leyendo el código: se ve en un aparato, con la cifra al lado.
	 *
	 * Se registra en `d`, que es lo que el nivel de registro de
	 * desarrollo enseña, así que no cuesta nada en una release.
	 *
	 * Lo que sale son dos cosas distintas y no hay que mezclarlas:
	 *
	 *  - **cuánto se entregó por delante del habla**, que es lo que
	 *    manda [LEAD_MS] y es el retardo que oye quien escucha;
	 *  - **cuánto tardó en abrirse el motor**, que es lo que se paga
	 *    la primera vez después de que el servicio se levante, y que
	 *    con el precalentado debería ser cero.
	 */
	private fun logLatency(pace: TtsPacer, output: TtsOutput, leadMs: Long = LEAD_MS) {
		val ahead = pace.ahead()
		val opened = output.openedAfterMs
		if (opened > 0L) {
			Timber.d("latencia: motor abierto en %d ms", opened)
			TtsHealth.latency(opened)
		}
		Timber.d(
			"latencia: %d ms de audio adelantado, %d ms entregados, tope %d ms",
			ahead, pace.handedMs(), leadMs
		)
	}

	internal companion object {
		/**
		 * 5.4: contadores del **proceso**, no de la instancia.
		 *
		 * El framework crea y suelta este servicio muchas veces en
		 * una sola sesión (el registro real mostraba 35 creaciones en
		 * 105 s). Con los contadores como campos de instancia, las
		 * sesiones volvían a 1, 2, 3 en cada servicio y la primera
		 * emisión se apuntaba 35 veces. Aquí siguen subiendo aunque
		 * el servicio se recree, que es lo que permite seguir una
		 * emisión concreta en todo el archivo.
		 */
		private val serviceIds = java.util.concurrent.atomic.AtomicLong()
		private val sessionIds = java.util.concurrent.atomic.AtomicLong()
		private val firstEmissionLogged = java.util.concurrent.atomic.AtomicBoolean(false)

		/** La mayor cantidad de audio, en milisegundos, que una
		 *  emisión ha entregado desde que se limpió lo anterior.
		 *  Nada en la app lo lee. Es lo que deja decir a una
		 *  prueba que el motor no va por delante del habla, lo
		 *  cual es invisible desde fuera: un motor que encola seis
		 *  elementos en una décima de segundo y uno que se pone
		 *  límite a sí mismo se ve igual para el framework y suena
		 *  completamente distinto para una persona. */
		@Volatile
		var mostAudioHandedMs: Long = 0

		/** Últimas emisiones tal como llegaron (lo que el lector
		 *  pidió decir), para el Historial de los ajustes. Sin
		 *  audio ni datos personales más allá del texto ya hablado;
		 *  vive solo en memoria y se pierde al cerrar el servicio. */
		private const val HISTORY_MAX = 30
		private const val HISTORY_CHARS = 200
		private val history = ArrayDeque<String>()

		@Synchronized
		fun recordHistory(text: String) {
			// Solo interesan los primeros caracteres: cortar antes de
			// recortar evita copiar un texto enorme en el hilo de
			// síntesis antes de la primera palabra.
			val clean = text.take(HISTORY_CHARS).trim()
			if (clean.isEmpty() || history.lastOrNull() == clean) return
			history.addLast(clean)
			while (history.size > HISTORY_MAX) history.removeFirst()
		}

		@Synchronized
		fun historySnapshot(): List<String> = history.toList().asReversed()

		@Synchronized
		fun clearHistory() {
			history.clear()
		}

		const val NOTIF_ID = 1
		/**
		 * 5.0: 4 kB en vez de 8. El primer bloque llega antes y una
		 * parada descarta menos audio ya entregado; a 11 kHz son
		 * unos 186 ms, no los 370 de antes.
		 *
		 * 5.4.3: se queda en 4 kB para todas las emisiones. El modo
		 * de ultrabaja latencia usaba bloques de 2 kB y despertaba
		 * cada 10 ms; en la práctica eso añadía trabajo de hilos y
		 * empeoraba la respuesta, así que se retiró.
		 */
		const val MAX_CHUNK = 4096
		/** 5.0: una emisión que no avanza en este tiempo se cancela. */
		const val WATCHDOG_MS = 15_000L
		const val WATCHDOG_NS = WATCHDOG_MS * 1_000_000L
		/** Cada cuánto se reintenta la notificación permanente, con
		 *  espera creciente hasta ese tope. Arranca en un segundo
		 *  porque mientras el permiso de notificaciones no esté, el
		 *  servicio no tiene vida garantizada y eso urge. */
		val FOREGROUND_RETRY_STEPS_MS = longArrayOf(1000L, 2000L, 5000L, 15000L, 30000L)
		/**
		 * 4.1: cuántas instancias se dejan vivas lo decidió aquí un
		 * `LIVE_ENGINES = 2` (antes tres), y 4.2 lo sustituyó por
		 * [liveEngines], que da una sola salvo con el detector de
		 * idioma encendido. 5.2 quita el número, que ya no se leía y
		 * estaba pegado al comentario de abajo: queda la explicación
		 * de por qué dos era el tope y no tres.
		 */
		/** 5.2: el nivel de [onLowMemory] (COMPLETE en la escala de
		 *  la plataforma). Se nombra aquí porque la constante del
		 *  sistema está obsoleta en las versiones nuevas. */
		const val TRIM_COMPLETE = 80
		const val BYTES_PER_SAMPLE = 2
		/**
		 * Cuánto audio puede estar adelantado al habla, para que la
		 * pista nunca se quede seca mientras el motor está retenido.
		 *
		 * **Esto no es la latencia, es su freno.** El framework da
		 * un búfer de `maxBufferSize` y hay que llenarlo antes de que
		 * suene; a 11025 Hz ese búfer son del orden de 370 ms, así
		 * que la espera la pone el framework. Lo que sí es nuestro es
		 * no encolar *encima* de eso, que es lo que hace este tope:
		 * un motor que entrega seis trozos en una décima de segundo
		 * suena igual para el framework y como una lista de la
		 * compra para quien escucha.
		 *
		 * 5.0: 150 ms. El motor entrega mucho más rápido que el
		 * habla, y con 300 había hasta trescientas milésimas de
		 * audio en cola que una parada tenía que tirar.
		 *
		 * 5.4.3: 100 ms para todas las emisiones. Es menos audio
		 * encolado --una parada llega antes-- sin bajar a los 60 ms
		 * del modo retirado, que con bloques pequeños dejaba la
		 * pista demasiado justa. [logLatency] sigue dejando la
		 * cifra real si hay que moverlo otra vez.
		 */
		const val LEAD_MS = 100L
		/** 5.4.3: 20 ms por rebanada. Suficiente para que una parada
		 *  se note al instante sin despertar el hilo de más. */
		const val SLICE_MS = 20L
	}
}
