package com.angelonmx.reloquence

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 4.3: el aviso de que algo cambió, para el motor y para la pantalla.
 *
 * Antes cada consumidor se enteraba por su cuenta, y el resultado era
 * que importar una copia se oía pero no se veía. El motor relee los
 * ajustes en cada emisión, así que la voz cambiaba sola; la pantalla
 * de ajustes, en cambio, tiene su propio estado en memoria -- un
 * ViewModel con una copia de cada valor -- y ese estado no se enteraba
 * de nada que hubiera pasado fuera. Peor: el "Abrir con" de un archivo
 * desde el gestor de archivos o el correo importa en
 * [ImportDictionaryActivity], que es **otra** actividad, así que al
 * volver a la de ajustes todo seguía enseñando lo de antes hasta que
 * esa actividad se recreaba. Eso es lo que se veía como "no aparece
 * hasta que reinicias el servicio": lo que se reiniciaba no era el
 * motor, era la pantalla.
 *
 * Aquí hay un solo sitio que se entera de cada cambio de los ajustes,
 * y tres consumidores:
 *
 *  - El **servicio de voz**, al que se le pide reiniciar el motor
 *    cuando lo que cambió no basta con volver a aplicarlo: el
 *    diccionario, la tasa de muestreo, el idioma o una reposición
 *    completa. Volver a abrir una instancia cuesta lo que cuesta
 *    cargar trece megabytes de reglas, así que solo se hace cuando de
 *    verdad hace falta, y nunca a mitad de una emisión.
 *  - La **pantalla de ajustes**, que relee y se redibuja sola. Da igual
 *    de dónde venga el cambio: la propia app, otra actividad, o el
 *    motor.
 *  - La **vista previa**, que suelta lo enseñado al motor para que una
 *    palabra borrada deje de oírse en la muestra.
 *
 * Los avisos salen en el hilo que escribió el ajuste, que puede ser el
 * principal, el de síntesis o el de una importación en segundo plano.
 * Por eso [engineReload] entrega en el hilo principal, y quien lo
 * recibe tiene que poder trabajar en cualquiera de los tres.
 */
object SettingsSignal {

	/** Qué cambió, y si lo que cambió fue el diccionario. Lo segundo
	 *  se lleva aparte porque releer el archivo entero es lo único
	 *  caro de esta lista, y un cambio de volumen no puede costar lo
	 *  mismo que cambiar de diccionario. */
	class Change(val reason: String, val dictionary: Boolean)

	/**
	 * Cuántas veces ha cambiado algo. Quien guarda lo que ya leyó lo
	 * compara con esto para saber si tiene que volver a leer, y es lo
	 * que hace que la pantalla sepa que hay algo nuevo que mirar.
	 */
	private val epochNow = AtomicLong(1L)

	/**
	 * Cuántas veces ha cambiado el diccionario: el archivo elegido o
	 * las palabras propias del editor.
	 *
	 * El servicio ya releía el archivo cuando cambiaban su fecha o su
	 * tamaño, pero dos importaciones dentro del mismo segundo con el
	 * mismo número de entradas se veían como el mismo archivo y el
	 * motor seguía con el diccionario viejo, que es justo lo que se
	 * reportaba. Un contador que solo sube no se confunde con otro
	 * estado.
	 */
	private val dictionaryNow = AtomicLong(0L)

	/**
	 * Los que piden reiniciar el motor. Escriben desde donde les
	 * parezca, y el servicio se encarga de que su reacción valga en el
	 * principal, en el de síntesis y en el de una importación en
	 * segundo plano.
	 */
	private val engineListeners = CopyOnWriteArrayList<(Change) -> Unit>()

	/** Los que solo quieren enterarse de que algo se movió. */
	private val watchers = CopyOnWriteArrayList<(Change) -> Unit>()

	private val main: Handler by lazy { Handler(Looper.getMainLooper()) }

	/** Cuántas veces ha cambiado algo desde que arrancó el proceso. */
	val epoch: Long get() = epochNow.get()

	/** Cuántas veces ha cambiado el diccionario, para no fiarse solo
	 *  de la fecha y del tamaño del archivo. */
	val dictionaryRevision: Long get() = dictionaryNow.get()

	/**
	 * Se engancha a los ajustes una sola vez por proceso. Hay un
	 * [Settings] por la pantalla, otro por la vista previa y otro por
	 * el servicio, y si cada uno colgara su propio listener cada
	 * cambio se contaría tres veces.
	 */
	@Synchronized
	fun attach(prefs: SharedPreferences) {
		if (hooked) return
		prefs.registerOnSharedPreferenceChangeListener { _, key -> fromKey(key) }
		hooked = true
	}

	private var hooked = false

	/**
	 * Las claves cuyo cambio obliga a **reiniciar** el motor en vez de
	 * limitarse a volver a aplicarlo.
	 *
	 * La voz, la velocidad, el volumen, las abreviaturas, las pausas y
	 * todo lo demás se reponen en cada emisión comparando una huella
	 * con lo que el motor dice que tiene ([EvvTtsService.readyEngine]),
	 * y eso es lo que hace que mover un deslizador se oiga al instante
	 * sin tirar abajo un motor. Lo de esta lista es otra cosa: o pesa
	 * en la nativa (el diccionario, la tasa de muestreo) o decide qué
	 * instancia se abre (el idioma, la autodetección), y en los dos
	 * casos una instancia ya abierta no vale: hay que soltarla.
	 */
	private val RELOAD_KEYS: Set<String> = setOf(
		Settings.KEY_DICTIONARY_PATH, Settings.KEY_DICTIONARY_NAME,
		Settings.KEY_DICTIONARY_WORDS, Settings.KEY_SAMPLE_RATE,
		Settings.KEY_DEFAULT_LANGUAGE, Settings.KEY_AUTO_DETECT,
		Settings.KEY_AUTO_VARIANT_EN, Settings.KEY_AUTO_VARIANT_ES,
		Settings.KEY_AUTO_VARIANT_FR
	)

	private val DICT_KEYS: Set<String> = setOf(
		Settings.KEY_DICTIONARY_PATH, Settings.KEY_DICTIONARY_NAME,
		Settings.KEY_DICTIONARY_WORDS
	)

	/**
	 * Lo que el framework avisa. El listener de los ajustes entrega
	 * una clave por llamada, y una clave nula cuando lo que se hizo
	 * fue vaciarlos todos, que es lo que hace [Settings.restorePrefs]
	 * antes de reponer la copia.
	 */
	private fun fromKey(key: String?) {
		epochNow.incrementAndGet()
		val dict = key == null || key in DICT_KEYS
		if (dict) dictionaryNow.incrementAndGet()
		val reload = key == null || key in RELOAD_KEYS
		val change = Change("ajustes", dict)
		// La pantalla se entera siempre y en el hilo principal, porque
		// su estado es lo que se dibuja.
		main.post { tell(watchers, change) }
		if (reload) engine(change)
	}

	/**
	 * Dice que **el diccionario** ha cambiado: el archivo se ha
	 * reescrito o las palabras propias del editor han cambiado. Es la
	 * forma que tienen los caminos que escriben el archivo sin tocar
	 * ninguna clave (editar una palabra, quitar las repetidas), donde
	 * la fecha y el tamaño no dicen nada útil.
	 */
	fun dictionaryChanged(reason: String) {
		epochNow.incrementAndGet()
		dictionaryNow.incrementAndGet()
		val change = Change(reason, true)
		main.post { tell(watchers, change) }
		engine(change)
	}

	/**
	 * Pide que el motor se reinicie porque lo que cambió no basta con
	 * volver a aplicarlo: una copia importada, un apartado devuelto a
	 * su valor inicial, un perfil entero.
	 *
	 * El contador sube ya, en el hilo que llama, para que la emisión
	 * que esté a punto de empezar lo vea aunque el aviso llegue
	 * después, y el aviso en sí va al hilo principal, que es donde el
	 * motor se puede soltar sin cortar lo que se esté diciendo.
	 */
	fun engineReload(reason: String) {
		epochNow.incrementAndGet()
		engine(Change(reason, false))
	}

	/**
	 * Entrega [change] a los que esperan reinicio del motor, en el
	 * hilo principal. El servicio marca la petición al instante desde
	 * el hilo que escribe y la aplica entre emisiones, así que esto
	 * solo sirve para soltar el motor en cuanto se puede sin cortar lo
	 * que esté sonando.
	 */
	private fun engine(change: Change) {
		if (engineListeners.isEmpty()) return
		main.post { tell(engineListeners, change) }
	}

	private fun tell(list: CopyOnWriteArrayList<(Change) -> Unit>, change: Change) {
		for (listener in list) {
			// Un consumidor que se cae no puede arrastrar al cambio:
			// lo que viene detrás (el motor, la pantalla) es lo que de
			// verdad importa.
			try {
				listener(change)
			} catch (broken: Exception) {
				Timber.w(broken, "un consumidor del cambio de ajustes falló")
			}
		}
	}

	fun addEngineListener(listener: (Change) -> Unit) {
		engineListeners.addIfAbsent(listener)
	}

	fun removeEngineListener(listener: (Change) -> Unit) {
		engineListeners.remove(listener)
	}

	fun addWatcher(listener: (Change) -> Unit) {
		watchers.addIfAbsent(listener)
	}

	fun removeWatcher(listener: (Change) -> Unit) {
		watchers.remove(listener)
	}
}
