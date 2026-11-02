package com.angelonmx.reloquence

import android.content.Context
import android.content.SharedPreferences

/**
 * Lo que la pantalla de ajustes escribió, leído de vuelta para el motor.
 *
 * Los ajustes de voz se guardan por voz. Reed a un tono de 55 sigue
 * a un tono de 55 después de un viaje por Glen y vuelta, y se queda
 * así hasta que Reed se reinicie. La velocidad es la excepción y se
 * guarda una sola vez para todos: seis de los ocho presets salen a
 * velocidad del motor 50 y Glen y Sandy salen a 70, así que una
 * velocidad que siguiera a la voz cambiaría el ritmo cada vez que
 * la voz cambiara.
 *
 * Nada se almacena hasta que alguien lo elige. Eso es lo importante
 * en lugar de un detalle: cada preset lleva su propia cabeza,
 * inflexión y volumen, así que un valor por defecto propio escrito
 * encima haría que cada voz sonara mal de la misma forma.
 *
 * Una revisión sube cada vez que algo cambia, para que el servicio
 * pueda saber si la instancia que sostiene aún está configurada
 * como la dejó el usuario sin comparar cada valor.
 */
class Settings(context: Context) {

	// Se lee a través de almacenamiento protegido contra arranque,
	// porque el servicio de voz lo lee mientras el teléfono aún está
	// bloqueado. DirectBoot tiene el resto de eso.
	private val prefs: SharedPreferences = DirectBoot.storage(context)
		.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

	@Volatile
	var revision: Int = 0
		private set

	private val watcher = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }

	init {
		// 4.3: uno solo para todo el proceso, al que se cuelga el
		// motor y la pantalla. Va antes que las cargas, porque las dos
		// escriben y ese cambio tiene que verse igual que los demás.
		SettingsSignal.attach(prefs)
		dropLegacyKeys()
		carryOverTheOldKeys()
		carryOverMixInterval()
		carryOverDictionaryLibrary()
		prefs.registerOnSharedPreferenceChangeListener(watcher)
	}

	/**
	 * 4.3: avisa de que el diccionario cambió **en el archivo**, que
	 * es el caso que ninguna clave delata. Editar una palabra, quitar
	 * las repetidas o importar un archivo reescriben el diccionario
	 * sin tocar una sola clave de los ajustes, así que ni la fecha ni
	 * el tamaño sirven de nada aquí y el motor se quedaría hablando
	 * con lo de antes hasta el siguiente reinicio del servicio.
	 */
	fun dictionaryRewritten(reason: String) = SettingsSignal.dictionaryChanged(reason)

	/**
	 * 4.3: pide que el motor se reinicie. Para lo que no basta con
	 * volver a aplicarlo en la siguiente emisión: una copia
	 * importada, un apartado devuelto a su valor inicial, un perfil
	 * entero, o cualquier cambio que venga de otra actividad.
	 */
	fun engineReload(reason: String) = SettingsSignal.engineReload(reason)

	/**
	 * Escribe y **espera**.
	 *
	 * 4.1: `apply()` devuelve antes de que el valor esté en el
	 * disco, y aquí eso se notaba. El servicio de voz corre en otro
	 * momento del ciclo de vida, y lo que se complaintaba era
	 * justamente eso: cambiar la velocidad y que al reiniciarse el
	 * servicio volviera la de antes. Si el sistema mata el proceso
	 * entre la escritura y el volcado, el ajuste se perdía y la voz
	 * volvía a ser la que el motor trae de fábrica, que es lo que
	 * parecía un ajuste que "no se toma".
	 *
	 * Son unas pocas claves pequeñas y se escriben desde un
	 * deslizador, así que el coste de esperar no se nota y a
	 * cambio el ajuste está en el disco cuando el usuario suelta.
	 */
	private fun write(block: SharedPreferences.Editor.() -> Unit) {
		val editor = prefs.edit()
		editor.block()
		editor.commit()
	}

	/**
	 * El idioma del motor en vigor como palabra ECI, o cero si
	 * todavía no hay ninguno.
	 *
	 * 4.1: cero ya no cae en el primer idioma que el motor declara,
	 * que es el inglés, sino en el que corresponde al idioma del
	 * sistema. Un teléfono en español sin nada tocado speaks with
	 * la voz inglesa, y de ahí salen las quejas de pronunciación
	 * ("versión" leída como una palabra sola, la "s" de un nombre
	 * propia sonando a otra cosa): no es que el léxico español
	 * falle, es que no se estaba usando.
	 *
	 * El ajuste manda sobre lo pedido cuando hay uno elegido, así
	 * que aquí solo se decide el caso de "sistema", que es lo que
	 * el propio apartado de ajustes llama así.
	 */
	fun resolvedLanguage(): Int {
		val chosen = defaultLanguage
		if (chosen != 0 && EvvEngine.available.contains(chosen)) return chosen
		val here = runCatching { java.util.Locale.getDefault() }.getOrNull() ?: return 0
		return AutoLang.preferredEngine(here.language, here.country, 0) ?: 0
	}

	var voice: Int
		get() = prefs.getInt(KEY_VOICE, 0).coerceIn(0, Eci.PRESET_NAMES.lastIndex)
		set(value) = write { putInt(KEY_VOICE, value) }

	var sampleRateHz: Int
		get() = prefs.getInt(KEY_SAMPLE_RATE, DEFAULT_SAMPLE_RATE)
		set(value) = write { putInt(KEY_SAMPLE_RATE, value) }

	/** El idioma predeterminado del motor, como palabra de idioma ECI.
	 *  Cero significa el primero disponible. */
	var defaultLanguage: Int
		get() = prefs.getInt(KEY_DEFAULT_LANGUAGE, 0)
		set(value) = write { putInt(KEY_DEFAULT_LANGUAGE, value) }

	/** El diccionario de abreviaturas. Apagado por defecto: expande
	 *  lo que toma por una abreviatura tanto si se quería como si
	 *  no. */
	var abbreviations: Boolean
		get() = prefs.getBoolean(KEY_ABBREVIATIONS, false)
		set(value) = write { putBoolean(KEY_ABBREVIATIONS, value) }

	/** Cuánto de las pausas propias del motor conservar. Uno de
	 *  los constantes de Pauses, y no es asunto de una voz.
	 *  4.1: por defecto intactas. Recortarlas todas apresura la
	 *  frase y suena raro a la vez que natural; el hueco final se
	 *  puede acortar aparte y la puntuación la manda el motor.
	 *  Quien quiera el recorte total lo elige. */
	var pauses: Int
		get() = prefs.getInt(KEY_PAUSES, Pauses.KEEP).coerceIn(Pauses.KEEP, Pauses.ALL)
		set(value) = write { putInt(KEY_PAUSES, value.coerceIn(Pauses.KEEP, Pauses.ALL)) }

	/** Cuántos milisegundos dura la pausa al final de cada texto.
	 *  Es la espera que se siente entre un elemento y el siguiente. */
	var pauseDurationMs: Int
		get() = prefs.getInt(KEY_PAUSE_DURATION_MS, DEFAULT_PAUSE_MS)
		set(value) = write { putInt(KEY_PAUSE_DURATION_MS, value.coerceIn(0, 2000)) }

	/** Una velocidad para todas las voces. */
	var speed: Int
		get() = Eci.clampVoice(Eci.VOICE_SPEED, prefs.getInt(KEY_SPEED, Eci.DEFAULT_SPEED))
		set(value) = write { putInt(KEY_SPEED, Eci.clampVoice(Eci.VOICE_SPEED, value)) }

	/** Si la velocidad la manda solo el sistema (TalkBack), sin base propia.
	 *  4.1: activado por defecto al instalar; el usuario lo apaga si
	 *  elige un porcentaje propio. */
	var useSystemSpeed: Boolean
		get() = prefs.getBoolean(KEY_USE_SYSTEM_SPEED, true)
		set(value) = write { putBoolean(KEY_USE_SYSTEM_SPEED, value) }

	/**
	 * 4.3: si se ignoran los códigos de voz del texto y de los
	 * diccionarios.
	 *
	 * Un acento grave seguido de una letra es una instrucción de
	 * elocución para el motor, y el motor la aplica al encolar: por
	 * eso un `` `vr22 `` en mitad de una frase mueve el timbre a
	 * partir de ahí y lo deja movido. Es una capacidad de verdad y
	 * también un vector de entrada: un diccionario compartido puede
	 * traerlos y quien no lo sepa va a oír una voz que nadie eligió.
	 *
	 * Apagado (lo de fábrica) es como se ha comportado siempre y los
	 * códigos funcionan. Encendido se quitan antes de que el texto
	 * llegue al motor, así que la palabra se dice como es y la voz
	 * no se mueve. No se borra nada: el código sigue en el
	 * diccionario y basta con apagar el interruptor para que
	 * vuelva a hacer lo que hacía.
	 */
	var ignoreVoiceTags: Boolean
		get() = prefs.getBoolean(KEY_IGNORE_VOICE_TAGS, false)
		set(value) = write { putBoolean(KEY_IGNORE_VOICE_TAGS, value) }

	// ---- volumen -----------------------------------------------------

	/** Si el volumen lo manda el sistema (volumen multimedia), sin
	 *  ganancia propia. 4.1: activado por defecto al instalar; 100
	 *  es neutro y el usuario lo apaga si elige un nivel propio. */
	var useSystemVolume: Boolean
		get() = prefs.getBoolean(KEY_USE_SYSTEM_VOLUME, true)
		set(value) = write { putBoolean(KEY_USE_SYSTEM_VOLUME, value) }

	/** El volumen general, de 0 a 500. 100 es lo normal (neutro).
	 *  Con [useSystemVolume] en vigor contesta 100 sin leer nada. */
	var volume: Int
		get() = if (useSystemVolume) 100 else prefs.getInt(KEY_VOLUME, 100).coerceIn(0, 500)
		set(value) = write { putInt(KEY_VOLUME, value.coerceIn(0, 500)) }

	// ---- efectos de audio --------------------------------------------

	/** Si el ecualizador de diez bandas está en vigor. */
	var eqEnabled: Boolean
		get() = prefs.getBoolean(KEY_EQ_ENABLED, false)
		set(value) = prefs.edit().putBoolean(KEY_EQ_ENABLED, value).apply()

	/** Ganancia de la banda [band] (0 a 9), en dB de -12 a +12. */
	fun eqBand(band: Int): Int =
		prefs.getInt("$KEY_EQ_BAND$band", 0).coerceIn(-12, 12)

	fun setEqBand(band: Int, db: Int) {
		prefs.edit().putInt("$KEY_EQ_BAND$band", db.coerceIn(-12, 12)).apply()
	}

	/** Las diez ganancias, para armar la cadena de audio. */
	fun eqBands(): IntArray = IntArray(10) { eqBand(it) }

	/** Si la reverberación está en vigor. */
	var reverbEnabled: Boolean
		get() = prefs.getBoolean(KEY_REVERB_ENABLED, false)
		set(value) = prefs.edit().putBoolean(KEY_REVERB_ENABLED, value).apply()

	/** Mezcla de reverberación, de 0 a 100. */
	var reverbLevel: Int
		get() = prefs.getInt(KEY_REVERB_LEVEL, 25).coerceIn(0, 100)
		set(value) = prefs.edit().putInt(KEY_REVERB_LEVEL, value.coerceIn(0, 100)).apply()

	/** Realce de graves, en dB de -12 a +12. */
	var bassDb: Int
		get() = prefs.getInt(KEY_BASS_DB, 0).coerceIn(-12, 12)
		set(value) = prefs.edit().putInt(KEY_BASS_DB, value.coerceIn(-12, 12)).apply()

	/** Presencia (brillo de la voz), en dB de -12 a +12. */
	var presenceDb: Int
		get() = prefs.getInt(KEY_PRESENCE_DB, 0).coerceIn(-12, 12)
		set(value) = prefs.edit().putInt(KEY_PRESENCE_DB, value.coerceIn(-12, 12)).apply()

	/** Si el eco está en vigor. */
	var echoEnabled: Boolean
		get() = prefs.getBoolean(KEY_ECHO_ENABLED, false)
		set(value) = prefs.edit().putBoolean(KEY_ECHO_ENABLED, value).apply()

	/** Mezcla de eco, de 0 a 100. */
	var echoLevel: Int
		get() = prefs.getInt(KEY_ECHO_LEVEL, 30).coerceIn(0, 100)
		set(value) = prefs.edit().putInt(KEY_ECHO_LEVEL, value.coerceIn(0, 100)).apply()

	/** Retardo del eco en ms: 120 bofetada, 220 normal, 350 amplio. */
	var echoTimeMs: Int
		get() = prefs.getInt(KEY_ECHO_TIME, 220).coerceIn(80, 600)
		set(value) = prefs.edit().putInt(KEY_ECHO_TIME, value.coerceIn(80, 600)).apply()

	/** Si el compresor nocturno está en vigor. */
	var compressorEnabled: Boolean
		get() = prefs.getBoolean(KEY_COMPRESSOR, false)
		set(value) = prefs.edit().putBoolean(KEY_COMPRESSOR, value).apply()

	/** Multiplicador de ganancia de entrada, de 0 a 200.
	 *  100 es intacto. */
	var boostPercent: Int
		get() = prefs.getInt(KEY_BOOST, 100).coerceIn(0, 200)
		set(value) = prefs.edit().putInt(KEY_BOOST, value.coerceIn(0, 200)).apply()

	/** Si la puerta de ruido está en vigor. */
	var gateEnabled: Boolean
		get() = prefs.getBoolean(KEY_GATE, false)
		set(value) = prefs.edit().putBoolean(KEY_GATE, value).apply()

	/** Nivel de trémolo, de 0 a 100. Cero es apagado. */
	var tremoloLevel: Int
		get() = prefs.getInt(KEY_TREMOLO, 0).coerceIn(0, 100)
		set(value) = prefs.edit().putInt(KEY_TREMOLO, value.coerceIn(0, 100)).apply()

	/** Velocidad del trémolo en décimas de hercio: 30 lento,
	 *  55 normal, 80 rápido. */
	var tremoloRate: Int
		get() = prefs.getInt(KEY_TREMOLO_RATE, 55).coerceIn(20, 120)
		set(value) = prefs.edit().putInt(KEY_TREMOLO_RATE, value.coerceIn(20, 120)).apply()

	/** Si el coro está en vigor. */
	var chorusEnabled: Boolean
		get() = prefs.getBoolean(KEY_CHORUS, false)
		set(value) = prefs.edit().putBoolean(KEY_CHORUS, value).apply()

	/** Si el bitcrusher lofi está en vigor. */
	var crusherEnabled: Boolean
		get() = prefs.getBoolean(KEY_CRUSHER, false)
		set(value) = prefs.edit().putBoolean(KEY_CRUSHER, value).apply()

	/** Si el flanger está en vigor. */
	var flangerEnabled: Boolean
		get() = prefs.getBoolean(KEY_FLANGER, false)
		set(value) = prefs.edit().putBoolean(KEY_FLANGER, value).apply()

	/** Si el phaser está en vigor (barrido suave tipo espacial,
	 *  distinto del flanger: más aireado y menos metálico). */
	var phaserEnabled: Boolean
		get() = prefs.getBoolean(KEY_PHASER, false)
		set(value) = prefs.edit().putBoolean(KEY_PHASER, value).apply()

	/** Nivel de vibrato, de 0 a 100. Cero es apagado: trémolo
	 *  de tono (5 Hz) que da calidez de voz cantada. */
	var vibratoLevel: Int
		get() = prefs.getInt(KEY_VIBRATO, 0).coerceIn(0, 100)
		set(value) = prefs.edit().putInt(KEY_VIBRATO, value.coerceIn(0, 100)).apply()

	/** Nivel de modulador en anillo, de 0 a 100. Cero es apagado:
	 *  multiplica la voz por una portadora de 30 Hz (estilo Dalek)
	 *  y da voz metálica/robot distinta del trémolo, coro, flanger
	 *  y phaser. 4.1: efecto nuevo. */
	var ringmodLevel: Int
		get() = prefs.getInt(KEY_RINGMOD, 0).coerceIn(0, 100)
		set(value) = prefs.edit().putInt(KEY_RINGMOD, value.coerceIn(0, 100)).apply()

	/** Nivel de fuzz, de 0 a 100. Cero es apagado: recorte duro
	 *  con pre-ganancia (distorsión pesada) para voces agresivas,
	 *  distinto de la saturación suave de [driveEnabled].
	 *  4.2: efecto nuevo. */
	var fuzzLevel: Int
		get() = prefs.getInt(KEY_FUZZ, 0).coerceIn(0, 100)
		set(value) = prefs.edit().putInt(KEY_FUZZ, value.coerceIn(0, 100)).apply()

	/** Nivel de wah, de 0 a 100. Cero es apagado: un pasa-bajos
	 *  cuyo corte sigue la envolvente de la voz (funky), distinto
	 *  del vibrato y del phaser. 4.2: efecto nuevo. */
	var wahLevel: Int
		get() = prefs.getInt(KEY_WAH, 0).coerceIn(0, 100)
		set(value) = prefs.edit().putInt(KEY_WAH, value.coerceIn(0, 100)).apply()

	/** Nivel de maximizador, de 0 a 100. Cero es apagado: brickwall
	 *  con antelación que densifica la voz como radio procesada,
	 *  sin recorte áspero. 4.1: efecto nuevo. */
	var maximizerLevel: Int
		get() = prefs.getInt(KEY_MAXIMIZER, 0).coerceIn(0, 100)
		set(value) = prefs.edit().putInt(KEY_MAXIMIZER, value.coerceIn(0, 100)).apply()

	/** Si el filtro de teléfono está en vigor (banda 300-3400 Hz:
	 *  voz de radio/teléfono, muy inteligible con ruido). */
	var telephoneEnabled: Boolean
		get() = prefs.getBoolean(KEY_TELEPHONE, false)
		set(value) = prefs.edit().putBoolean(KEY_TELEPHONE, value).apply()

	/**
	 * 4.3: cinta. Una linea de retardo con la cabeza de lectura
	 * movida, que es lo que hace que la altura se mueva sola como
	 * en una cinta. 0 apagado, 100 muy inestable.
	 */
	var tapeMix: Int
		get() = prefs.getInt(KEY_TAPE, 0).coerceIn(0, 100)
		set(value) = prefs.edit().putInt(KEY_TAPE, value.coerceIn(0, 100)).apply()

	/**
	 * 4.3: grave profundo. Un subarmonico de verdad bajo la voz,
	 * para que no suene fragil en un altavoz pequeno. 0 apagado.
	 */
	var subBassMix: Int
		get() = prefs.getInt(KEY_SUB_BASS, 0).coerceIn(0, 100)
		set(value) = prefs.edit().putInt(KEY_SUB_BASS, value.coerceIn(0, 100)).apply()

	/**
	 * 4.3: tamaño de la sala. Estira los peineos de la
	 * reverberacion y sube la realimentacion, que es lo que
	 * convierte un cuarto en una catedral. 25 sala, 50 medio, 100
	 * catedral. Solo suena con la reverberacion encendida.
	 */
	var reverbSize: Int
		get() = prefs.getInt(KEY_REVERB_SIZE, 50).coerceIn(0, 100)
		set(value) = prefs.edit().putInt(KEY_REVERB_SIZE, value.coerceIn(0, 100)).apply()

	/**
	 * 4.3: cuántos bits conserva el lofi. 16 es intacto y 2 es un
	 * griton. Antes el paso era fijo y no se podia pedir nada
	 * intermedio.
	 */
	var crusherBits: Int
		get() = prefs.getInt(KEY_CRUSHER_BITS, 6).coerceIn(4, 16)
		set(value) = prefs.edit().putInt(KEY_CRUSHER_BITS, value.coerceIn(4, 16)).apply()

	/**
	 * 4.3: cuánto quita el anti-siseo. 100 es lo de antes (todo lo
	 * que se pueda) y 0 no quita nada, así que el interruptor deja
	 * de ser todo o nada.
	 */
	var deesserDepth: Int
		get() = prefs.getInt(KEY_DEESSER_DEPTH, 100).coerceIn(0, 100)
		set(value) = prefs.edit().putInt(KEY_DEESSER_DEPTH, value.coerceIn(0, 100)).apply()
	/** Si el normalizador está en vigor (nivela el volumen entre
	 *  voces y frases con un regulador lento: lo flojo sube y lo
	 *  fuerte queda igual, sin inflar el silencio). */
	var normalizerEnabled: Boolean
		get() = prefs.getBoolean(KEY_NORMALIZER, false)
		set(value) = prefs.edit().putBoolean(KEY_NORMALIZER, value).apply()

	/** Si el corte de graves está en vigor (pasa-altos a ~90 Hz:
	 *  quita retumbe sin tocar la voz, ideal con cuerpo o graves
	 *  fuertes y en altavoces pequeños). */
	var highpassEnabled: Boolean
		get() = prefs.getBoolean(KEY_HIGHPASS, false)
		set(value) = prefs.edit().putBoolean(KEY_HIGHPASS, value).apply()

	/** Si la saturación suave está en vigor (realza armónicos para
	 *  dar presencia en altavoces pequeños sin subir el nivel). */
	var driveEnabled: Boolean
		get() = prefs.getBoolean(KEY_DRIVE, false)
		set(value) = prefs.edit().putBoolean(KEY_DRIVE, value).apply()

	/** Si el anti-siseo está en vigor (doma s, sh sin apagar voz). */
	var deesserEnabled: Boolean
		get() = prefs.getBoolean(KEY_DEESSER, false)
		set(value) = prefs.edit().putBoolean(KEY_DEESSER, value).apply()

	/** Nivel del excitador de claridad, de 0 a 100. Cero es apagado;
	 *  el realce HD por tasa siempre suma aunque esto esté en cero. */
	var exciterLevel: Int
		get() = prefs.getInt(KEY_EXCITER, 0).coerceIn(0, 100)
		set(value) = prefs.edit().putInt(KEY_EXCITER, value.coerceIn(0, 100)).apply()

	/** Si la calidez (saturación suave) está en vigor. */
	var warmthEnabled: Boolean
		get() = prefs.getBoolean(KEY_WARMTH, false)
		set(value) = prefs.edit().putBoolean(KEY_WARMTH, value).apply()

	/** Si el realce HD automático por tasa está en vigor (cada
	 *  salto de tasa suena más abierto: 16k +3 dB, 22k +5 dB,
	 *  32k +6 dB, 44k +8 dB, 48k +9 dB más excitador). Apagado
	 *  por defecto: el motor tal como es, y quien quiera lo
	 *  enciende. */
	var hdEnhance: Boolean
		get() = prefs.getBoolean(KEY_HD_ENHANCE, false)
		set(value) = prefs.edit().putBoolean(KEY_HD_ENHANCE, value).apply()

	/** Nombre del perfil de efectos en vigor, o nada (manual). */
	var fxProfile: String?
		get() = prefs.getString(KEY_FX_PROFILE, null)
		set(value) = prefs.edit().putString(KEY_FX_PROFILE, value).apply()

	/** Nombre del perfil de voz en vigor, o nada (manual). */
	var voiceProfile: String?
		get() = prefs.getString(KEY_VOICE_PROFILE, null)
		set(value) = prefs.edit().putString(KEY_VOICE_PROFILE, value).apply()

	/** Orden de la lista del diccionario: 0 archivo tal cual, 1
	 *  alfabético, 2 larga primero, 3 corta primero. */
	var dictOrder: Int
		get() = prefs.getInt(KEY_DICT_ORDER, 1).coerceIn(0, 3)
		set(value) = prefs.edit().putInt(KEY_DICT_ORDER, value.coerceIn(0, 3)).apply()

	/** Un perfil de efectos: diez bandas, reverb, graves,
	 *  presencia, eco y su tiempo, compresor, multiplicador,
	 *  puerta, trémolo y su velocidad, coro, crusher, flanger,
	 *  phaser, vibrato, teléfono, normalizador, corte de graves,
	 *  saturación, excitador, calidez, anti-siseo, modulador en
	 *  anillo, fuzz y wah. Los últimos van al final para no mover
	 *  los anteriores.
	 *  4.1: @Serializable para compartir perfiles en JSON. */
	@kotlinx.serialization.Serializable
	data class FxPatch(
		val bands: IntArray = IntArray(10),
		val reverb: Int = 0,
		val bass: Int = 0,
		val presence: Int = 0,
		val echo: Int = 0,
		val comp: Boolean = false,
		val boost: Int = 100,
		val gate: Boolean = false,
		val tremolo: Int = 0,
		val chorus: Boolean = false,
		val crusher: Boolean = false,
		val flanger: Boolean = false,
		val exciter: Int = 0,
		val warmth: Boolean = false,
		val deesser: Boolean = false,
		val echoTime: Int = 220,
		val tremoloRate: Int = 55,
		val phaser: Boolean = false,
		val vibrato: Int = 0,
		val telephone: Boolean = false,
		val normalizer: Boolean = false,
		val highpass: Boolean = false,
		val drive: Boolean = false,
		val ringmod: Int = 0,
		val fuzz: Int = 0,
		val wah: Int = 0,
		val maximizer: Int = 0
	) {
		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (other !is FxPatch) return false
			return bands.contentEquals(other.bands) && reverb == other.reverb &&
				bass == other.bass && presence == other.presence && echo == other.echo &&
				comp == other.comp && boost == other.boost && gate == other.gate &&
				tremolo == other.tremolo && chorus == other.chorus && crusher == other.crusher &&
				flanger == other.flanger && exciter == other.exciter && warmth == other.warmth &&
				deesser == other.deesser && echoTime == other.echoTime &&
				tremoloRate == other.tremoloRate && phaser == other.phaser &&
				vibrato == other.vibrato && telephone == other.telephone &&
				normalizer == other.normalizer && highpass == other.highpass &&
				drive == other.drive && ringmod == other.ringmod &&
				fuzz == other.fuzz && wah == other.wah && maximizer == other.maximizer
		}

		override fun hashCode(): Int {
			var r = bands.contentHashCode()
			r = 31 * r + reverb; r = 31 * r + bass; r = 31 * r + presence
			r = 31 * r + echo; r = 31 * r + boost; r = 31 * r + tremolo
			r = 31 * r + exciter; r = 31 * r + echoTime; r = 31 * r + tremoloRate
			r = 31 * r + vibrato; r = 31 * r + ringmod; r = 31 * r + fuzz
			r = 31 * r + wah; r = 31 * r + maximizer
			return r
		}
	}

	/** Perfiles propios. Se guardan como líneas
	 *  "nombre|g0,...,g9|reverb|bass|presence|eco|comp|gate|boost|trem|chorus|crusher|flanger|exciter|warmth|deesser|echoTime|tremRate|phaser|vibrato|telephone|normalizer|highpass|drive|ringmod|fuzz|wah|maximizer";
	 *  las líneas viejas más cortas también se leen. */
	var fxProfiles: Map<String, FxPatch>
		get() {
			val raw = prefs.getString(KEY_FX_PROFILES, null) ?: return emptyMap()
			val out = LinkedHashMap<String, FxPatch>()
			for (line in raw.lines()) {
				val parts = line.split('|')
				// Hasta 33 para no volver a romper al añadir campos.
				if (parts.size != 3 && parts.size !in 6..33) continue
				val name = parts[0]
				if (name.isEmpty() || out.containsKey(name)) continue
				val gains = parts[1].split(',').mapNotNull { it.toIntOrNull()?.coerceIn(-12, 12) }
				if (gains.size != 10) continue
				val reverb = parts[2].toIntOrNull()?.coerceIn(0, 100) ?: continue
				val bass = parts.getOrNull(3)?.toIntOrNull()?.coerceIn(-12, 12) ?: 0
				val presence = parts.getOrNull(4)?.toIntOrNull()?.coerceIn(-12, 12) ?: 0
				val echo = parts.getOrNull(5)?.toIntOrNull()?.coerceIn(0, 100) ?: 0
				val comp = parts.getOrNull(6)?.toIntOrNull()?.let { it != 0 } ?: false
				val gate = parts.getOrNull(7)?.toIntOrNull()?.let { it != 0 } ?: false
				val boost = parts.getOrNull(8)?.toIntOrNull()?.coerceIn(0, 200) ?: 100
				val tremolo = parts.getOrNull(9)?.toIntOrNull()?.coerceIn(0, 100) ?: 0
				val chorus = parts.getOrNull(10)?.toIntOrNull()?.let { it != 0 } ?: false
				val crusher = parts.getOrNull(11)?.toIntOrNull()?.let { it != 0 } ?: false
				val flanger = parts.getOrNull(12)?.toIntOrNull()?.let { it != 0 } ?: false
				val exciter = parts.getOrNull(13)?.toIntOrNull()?.coerceIn(0, 100) ?: 0
				val warmth = parts.getOrNull(14)?.toIntOrNull()?.let { it != 0 } ?: false
				val deesser = parts.getOrNull(15)?.toIntOrNull()?.let { it != 0 } ?: false
				val echoTime = parts.getOrNull(16)?.toIntOrNull()?.coerceIn(80, 600) ?: 220
				val tremoloRate = parts.getOrNull(17)?.toIntOrNull()?.coerceIn(20, 120) ?: 55
				val phaser = parts.getOrNull(18)?.toIntOrNull()?.let { it != 0 } ?: false
				val vibrato = parts.getOrNull(19)?.toIntOrNull()?.coerceIn(0, 100) ?: 0
				val telephone = parts.getOrNull(20)?.toIntOrNull()?.let { it != 0 } ?: false
				val normalizer = parts.getOrNull(21)?.toIntOrNull()?.let { it != 0 } ?: false
				val highpass = parts.getOrNull(22)?.toIntOrNull()?.let { it != 0 } ?: false
				val drive = parts.getOrNull(23)?.toIntOrNull()?.let { it != 0 } ?: false
				val ringmod = parts.getOrNull(24)?.toIntOrNull()?.coerceIn(0, 100) ?: 0
				val fuzz = parts.getOrNull(25)?.toIntOrNull()?.coerceIn(0, 100) ?: 0
				val wah = parts.getOrNull(26)?.toIntOrNull()?.coerceIn(0, 100) ?: 0
				val maximizer = parts.getOrNull(27)?.toIntOrNull()?.coerceIn(0, 100) ?: 0
				out[name] = FxPatch(IntArray(10) { gains[it] }, reverb, bass, presence, echo, comp, boost, gate, tremolo, chorus, crusher, flanger, exciter, warmth, deesser, echoTime, tremoloRate, phaser, vibrato, telephone, normalizer, highpass, drive, ringmod, fuzz, wah, maximizer)
			}
			return out
		}
		set(value) {
			val raw = value.entries.joinToString("\n") { (name, patch) ->
				val clean = name.replace('|', ' ').replace('\n', ' ').trim()
				"$clean|${patch.bands.joinToString(",")}|${patch.reverb}|${patch.bass}|${patch.presence}|${patch.echo}|${if (patch.comp) 1 else 0}|${if (patch.gate) 1 else 0}|${patch.boost}|${patch.tremolo}|${if (patch.chorus) 1 else 0}|${if (patch.crusher) 1 else 0}|${if (patch.flanger) 1 else 0}|${patch.exciter}|${if (patch.warmth) 1 else 0}|${if (patch.deesser) 1 else 0}|${patch.echoTime}|${patch.tremoloRate}|${if (patch.phaser) 1 else 0}|${patch.vibrato}|${if (patch.telephone) 1 else 0}|${if (patch.normalizer) 1 else 0}|${if (patch.highpass) 1 else 0}|${if (patch.drive) 1 else 0}|${patch.ringmod}|${patch.fuzz}|${patch.wah}|${patch.maximizer}"
			}
			prefs.edit().putString(KEY_FX_PROFILES, raw).apply()
		}

	/** Un perfil de voz: qué preset base, con qué forma encima
	 *  (tono, inflexión, cabeza, rugosidad, respiración, género),
	 *  velocidad, volumen, tasa de muestreo, cuerpo y realce HD.
	 *  4.0: @Serializable para compartir perfiles en JSON. */
	@kotlinx.serialization.Serializable
	data class VoicePatch(
		val baseVoice: Int = 0,
		val params: Map<Int, Int> = emptyMap(),
		val speed: Int = Eci.DEFAULT_SPEED,
		val systemSpeed: Boolean = true,
		val volume: Int = 100,
		val systemVolume: Boolean = true,
		val sampleRateHz: Int = DEFAULT_SAMPLE_RATE,
		val body: Boolean = false,
		val hdEnhance: Boolean = false
	)

	/** Perfiles de voz propios, en JSON (kotlinx-serialization).
	 *  Si el JSON no parsea, se ignora y queda vacío. */
	var voiceProfiles: Map<String, VoicePatch>
		get() {
			val raw = prefs.getString(KEY_VOICE_PROFILES, null) ?: return emptyMap()
			return runCatching {
				VOICE_JSON.decodeFromString(
					kotlinx.serialization.builtins.MapSerializer(
						kotlinx.serialization.serializer<String>(),
						VoicePatch.serializer()
					),
					raw
				)
			}.getOrDefault(emptyMap())
		}
		set(value) {
			val raw = runCatching {
				VOICE_JSON.encodeToString(
					kotlinx.serialization.builtins.MapSerializer(
						kotlinx.serialization.serializer<String>(),
						VoicePatch.serializer()
					),
					value
				)
			}.getOrNull() ?: return
			prefs.edit().putString(KEY_VOICE_PROFILES, raw).apply()
		}

	/** Aplica un perfil entero de una vez. */
	fun applyFx(patch: FxPatch) {
		val edit = prefs.edit()
		for (b in 0 until 10) edit.putInt("$KEY_EQ_BAND$b", patch.bands.getOrElse(b) { 0 }.coerceIn(-12, 12))
		edit.putInt(KEY_REVERB_LEVEL, patch.reverb.coerceIn(0, 100))
		edit.putInt(KEY_BASS_DB, patch.bass.coerceIn(-12, 12))
		edit.putInt(KEY_PRESENCE_DB, patch.presence.coerceIn(-12, 12))
		edit.putInt(KEY_ECHO_LEVEL, patch.echo.coerceIn(0, 100))
		edit.putInt(KEY_ECHO_TIME, patch.echoTime.coerceIn(80, 600))
		edit.putBoolean(KEY_COMPRESSOR, patch.comp)
		edit.putInt(KEY_BOOST, patch.boost.coerceIn(0, 200))
		edit.putBoolean(KEY_GATE, patch.gate)
		edit.putInt(KEY_TREMOLO, patch.tremolo.coerceIn(0, 100))
		edit.putInt(KEY_TREMOLO_RATE, patch.tremoloRate.coerceIn(20, 120))
		edit.putBoolean(KEY_CHORUS, patch.chorus)
		edit.putBoolean(KEY_CRUSHER, patch.crusher)
		edit.putBoolean(KEY_FLANGER, patch.flanger)
		edit.putBoolean(KEY_PHASER, patch.phaser)
		edit.putInt(KEY_VIBRATO, patch.vibrato.coerceIn(0, 100))
		edit.putBoolean(KEY_TELEPHONE, patch.telephone)
		edit.putBoolean(KEY_NORMALIZER, patch.normalizer)
		edit.putBoolean(KEY_HIGHPASS, patch.highpass)
		edit.putBoolean(KEY_DRIVE, patch.drive)
		edit.putInt(KEY_EXCITER, patch.exciter.coerceIn(0, 100))
		edit.putBoolean(KEY_WARMTH, patch.warmth)
		edit.putBoolean(KEY_DEESSER, patch.deesser)
		edit.putInt(KEY_RINGMOD, patch.ringmod.coerceIn(0, 100))
		edit.putInt(KEY_FUZZ, patch.fuzz.coerceIn(0, 100))
		edit.putInt(KEY_WAH, patch.wah.coerceIn(0, 100))
		edit.putInt(KEY_MAXIMIZER, patch.maximizer.coerceIn(0, 100))
		edit.apply()
	}

	/** Si el idioma se detecta solo por emisión (heurística con
	 *  ML Kit cuando hay Google). Apagado manda el ajuste o lo
	 *  pedido, como siempre. 4.0: apagado por defecto. */
	var autoDetectLanguage: Boolean
		get() = prefs.getBoolean(KEY_AUTO_DETECT, false)
		set(value) = prefs.edit().putBoolean(KEY_AUTO_DETECT, value).apply()

	/** Variante preferida al detectar [tag] ("eng", "spa", "fra"):
	 *  el motor elegido para ese idioma. Cero es la primera
	 *  disponible. 4.0: se elige en Ajustes de autodetección. */
	var autoVariantEn: Int
		get() = prefs.getInt(KEY_AUTO_VARIANT_EN, 0)
		set(value) = prefs.edit().putInt(KEY_AUTO_VARIANT_EN, value).apply()

	var autoVariantEs: Int
		get() = prefs.getInt(KEY_AUTO_VARIANT_ES, 0)
		set(value) = prefs.edit().putInt(KEY_AUTO_VARIANT_ES, value).apply()

	var autoVariantFr: Int
		get() = prefs.getInt(KEY_AUTO_VARIANT_FR, 0)
		set(value) = prefs.edit().putInt(KEY_AUTO_VARIANT_FR, value).apply()

	/** La variante en vigor para [tag] ("spa" o "es", da igual),
	 *  o cero si ninguna vale (no compilada o de otro idioma):
	 *  quien llama usa la primera disponible. */
	fun autoVariantFor(tag: String): Int {
		val want = when (AutoLang.iso3(tag)) {
			"eng" -> autoVariantEn
			"spa" -> autoVariantEs
			"fra" -> autoVariantFr
			else -> 0
		}
		if (want == 0 || !EvvEngine.available.contains(want)) return 0
		return if (Eci.localeOf(want)?.first == AutoLang.iso3(tag)) want else 0
	}

	// ---- auto detección de idioma --------------------------------------

	/** Si el motor dice los emojis por su nombre. Apagado se
	 *  saltan como siempre y los lee el lector si sabe. */
	var processEmojis: Boolean
		get() = prefs.getBoolean(KEY_EMOJIS, false)
		set(value) = prefs.edit().putBoolean(KEY_EMOJIS, value).apply()

	/** Si los símbolos Unicode que no son emoji (flechas, formas,
	 *  matemáticas, monedas) se dicen por su nombre. Apagado se
	 *  quedan como siempre: el motor los ignora. */
	var readUnicodeSymbols: Boolean
		get() = prefs.getBoolean(KEY_UNICODE_SYMBOLS, false)
		set(value) = prefs.edit().putBoolean(KEY_UNICODE_SYMBOLS, value).apply()

	/** Si la puntuación la dice el sintetizador. Apagado la deja
	 *  al lector. */
	var punctEnabled: Boolean
		get() = prefs.getBoolean(KEY_PUNCT, false)
		set(value) = prefs.edit().putBoolean(KEY_PUNCT, value).apply()

	/** Cuánta puntuación decir: 0 toda, 1 esencial, 2 ninguna. */
	var punctLevel: Int
		get() = prefs.getInt(KEY_PUNCT_LEVEL, 0).coerceIn(0, 2)
		set(value) = prefs.edit().putInt(KEY_PUNCT_LEVEL, value.coerceIn(0, 2)).apply()

	/** Si se omite lo que va entre paréntesis. Apagado se dice. */
	var skipParentheses: Boolean
		get() = prefs.getBoolean(KEY_SKIP_PARENS, false)
		set(value) = prefs.edit().putBoolean(KEY_SKIP_PARENS, value).apply()

	/** Si se fuerza cómo se pronuncian los números (por
	 *  dígito, por dúo... hasta 10, o todo corrido). Apagado el
	 *  motor decide como siempre. */
	var numberForceEnabled: Boolean
		get() = prefs.getBoolean(KEY_NUMBER_FORCE, false)
		set(value) = prefs.edit().putBoolean(KEY_NUMBER_FORCE, value).apply()

	/** Cómo se agrupan los números cuando el forzado está en
	 *  vigor: de 1 (por dígito) a 10, o 0 (corrido: todo
	 *  pegado en un solo número). */
	var numberGroup: Int
		get() = prefs.getInt(KEY_NUMBER_GROUP, 1).let {
			if (it == Numbers.CORRIDO) Numbers.CORRIDO else it.coerceIn(Numbers.MIN_GROUP, Numbers.MAX_GROUP)
		}
		set(value) = prefs.edit().putInt(
			KEY_NUMBER_GROUP,
			if (value == Numbers.CORRIDO) Numbers.CORRIDO else value.coerceIn(Numbers.MIN_GROUP, Numbers.MAX_GROUP)
		).apply()

	/** Si la voz se alterna con otra cada tantas palabras. */
	var mixEnabled: Boolean
		get() = prefs.getBoolean(KEY_MIX_ENABLED, false)
		set(value) = prefs.edit().putBoolean(KEY_MIX_ENABLED, value).apply()

	/** La otra voz de la mezcla, como índice de preset. */
	var mixPartner: Int
		get() = prefs.getInt(KEY_MIX_PARTNER, 1).coerceIn(0, Eci.PRESET_NAMES.lastIndex)
		set(value) = prefs.edit().putInt(KEY_MIX_PARTNER, value.coerceIn(0, Eci.PRESET_NAMES.lastIndex)).apply()

	/** Vuelve toda la categoría de voz a sus valores iniciales:
	 *  voces sin tocar, velocidad y volumen del sistema, mezcla y cuerpo. */
	fun resetVoiceCategory() {
		write {
			for (v in Eci.PRESET_NAMES.indices) {
				for (p in PER_VOICE) remove("voice${v}_param$p")
			}
			putInt(KEY_VOICE, 0)
				.putInt(KEY_DEFAULT_LANGUAGE, 0)
				.putInt(KEY_SPEED, Eci.DEFAULT_SPEED)
				.putBoolean(KEY_USE_SYSTEM_SPEED, true)
				.putBoolean(KEY_USE_SYSTEM_VOLUME, true)
				.putInt(KEY_VOLUME, 100)
				.putInt(KEY_SAMPLE_RATE, DEFAULT_SAMPLE_RATE)
				.putBoolean(KEY_MIX_ENABLED, false)
				.putInt(KEY_MIX_PARTNER, 1)
				.putInt(KEY_MIX_INTERVAL, DEFAULT_MIX_WORDS)
				.putBoolean(KEY_BODY, false)
				.putBoolean(KEY_HD_ENHANCE, false)
		}
	}

	/** Vuelve toda la categoría de lectura a sus valores iniciales. */
	fun resetReadingCategory() {
		write {
			putInt(KEY_PAUSES, Pauses.KEEP)
				.putInt(KEY_PAUSE_DURATION_MS, DEFAULT_PAUSE_MS)
				.putBoolean(KEY_ABBREVIATIONS, false)
				.putBoolean(KEY_IGNORE_VOICE_TAGS, false)
				.putBoolean(KEY_AUTO_DETECT, false)
				.putInt(KEY_AUTO_VARIANT_EN, 0)
				.putInt(KEY_AUTO_VARIANT_ES, 0)
				.putInt(KEY_AUTO_VARIANT_FR, 0)
				.putBoolean(KEY_EMOJIS, false)
				.putBoolean(KEY_UNICODE_SYMBOLS, false)
				.putBoolean(KEY_PUNCT, false)
				.putInt(KEY_PUNCT_LEVEL, 0)
				.putBoolean(KEY_SKIP_PARENS, false)
				.putBoolean(KEY_NUMBER_FORCE, false)
				.putInt(KEY_NUMBER_GROUP, 1)
		}
	}

	/** Vuelve toda la categoría de efectos a sus valores iniciales:
	 *  todo apagado y niveles de fábrica. */
	fun resetFxCategory() {
		val edit = prefs.edit()
		for (b in 0 until 10) edit.putInt("$KEY_EQ_BAND$b", 0)
		edit.putBoolean(KEY_EQ_ENABLED, false)
			.putInt(KEY_REVERB_LEVEL, 25)
			.putInt(KEY_REVERB_SIZE, 50)
			.putInt(KEY_TAPE, 0)
			.putInt(KEY_SUB_BASS, 0)
			.putInt(KEY_CRUSHER_BITS, 6)
			.putInt(KEY_DEESSER_DEPTH, 100)
			.putBoolean(KEY_REVERB_ENABLED, false)
			.putInt(KEY_BASS_DB, 0)
			.putInt(KEY_PRESENCE_DB, 0)
			.putInt(KEY_ECHO_LEVEL, 30)
			.putInt(KEY_ECHO_TIME, 220)
			.putBoolean(KEY_ECHO_ENABLED, false)
			.putBoolean(KEY_COMPRESSOR, false)
			.putInt(KEY_BOOST, 100)
			.putBoolean(KEY_GATE, false)
			.putInt(KEY_TREMOLO, 0)
			.putInt(KEY_TREMOLO_RATE, 55)
			.putBoolean(KEY_CHORUS, false)
			.putBoolean(KEY_CRUSHER, false)
			.putBoolean(KEY_FLANGER, false)
			.putBoolean(KEY_PHASER, false)
			.putInt(KEY_VIBRATO, 0)
			.putBoolean(KEY_TELEPHONE, false)
			.putBoolean(KEY_NORMALIZER, false)
			.putBoolean(KEY_HIGHPASS, false)
			.putBoolean(KEY_DRIVE, false)
			.putInt(KEY_EXCITER, 0)
			.putBoolean(KEY_WARMTH, false)
			.putBoolean(KEY_DEESSER, false)
			.putInt(KEY_RINGMOD, 0)
			.putInt(KEY_FUZZ, 0)
			.putInt(KEY_WAH, 0)
			.putInt(KEY_MAXIMIZER, 0)
			.putString(KEY_FX_PROFILE, null)
			.apply()
	}

	/** Cada cuántas palabras cambia la voz: de 5 a 1000.
	 *  Los turnos caen siempre en límites de palabra, así que
	 *  ninguna palabra se parte entre dos voces. */
	var mixInterval: Int
		get() = prefs.getInt(KEY_MIX_INTERVAL, DEFAULT_MIX_WORDS).coerceIn(MIN_MIX_WORDS, MAX_MIX_WORDS)
		set(value) = prefs.edit().putInt(KEY_MIX_INTERVAL, value.coerceIn(MIN_MIX_WORDS, MAX_MIX_WORDS)).apply()

	/** Convierte el intervalo viejo (caracteres, 50 a 1000) a
	 *  palabras (5 a 1000), una sola vez. Una palabra media
	 *  con su espacio son ~6 caracteres. */
	private fun carryOverMixInterval() {
		if (prefs.getBoolean(KEY_MIX_MIGRATED, false)) return
		val edit = prefs.edit()
		if (prefs.contains(KEY_MIX_INTERVAL) && !prefs.contains(KEY_MIX_MIGRATED)) {
			val old = try {
				prefs.all[KEY_MIX_INTERVAL] as? Int ?: DEFAULT_MIX_INTERVAL_CHARS
			} catch (_: Exception) { DEFAULT_MIX_INTERVAL_CHARS }
			// Solo migra si huele al rango viejo por caracteres:
			// por encima de lo que en palabras tendría sentido.
			if (old > MAX_MIX_WORDS || old >= 50) {
				edit.putInt(KEY_MIX_INTERVAL, (old / 6).coerceIn(MIN_MIX_WORDS, MAX_MIX_WORDS))
			} else {
				edit.putInt(KEY_MIX_INTERVAL, old.coerceIn(MIN_MIX_WORDS, MAX_MIX_WORDS))
			}
		}
		edit.putBoolean(KEY_MIX_MIGRATED, true)
			.apply()
	}

	private fun carryOverDictionaryLibrary() {
		if (prefs.contains(KEY_DICTIONARY_LIBRARY)) return
		val path = dictionaryPath ?: return
		val file = java.io.File(path)
		if (!file.exists()) return
		val language = if (prefs.contains(KEY_DICTIONARY_LANGUAGE)) {
			prefs.getInt(KEY_DICTIONARY_LANGUAGE, resolvedLanguage())
		} else {
			resolvedLanguage()
		}
		val name = dictionaryName?.substringBefore(',')?.trim().orEmpty()
			.ifEmpty { file.name }
		val entry = DictionaryEntry(
			id = java.util.UUID.randomUUID().toString(),
			name = name,
			language = language,
			path = path,
			active = true
		)
		dictionaries = listOf(entry)
		write { putInt(KEY_DICTIONARY_LANGUAGE, language) }
	}

	/** Cuerpo de voz: realce notable siempre activo (graves +5 dB,
	 *  agudos +2 dB, aire propio) para que la voz tenga presencia
	 *  sin encender ningún efecto. Apagado por defecto: el motor
	 *  tal como es, y quien quiera lo enciende. */
	var bodyEnabled: Boolean
		get() = prefs.getBoolean(KEY_BODY, false)
		set(value) = prefs.edit().putBoolean(KEY_BODY, value).apply()

	// ---- copia de seguridad ----------------------------------------

	/** Todo lo guardado, para la copia de seguridad. Las claves de
	 *  versiones anteriores que ya no existen se quedan fuera. */
	fun dumpPrefs(): Map<String, Any?> =
		prefs.all.filterKeys { it != LEGACY_LOW_LATENCY }

	/** Repone lo guardado por [dumpPrefs], tal cual. */
	fun restorePrefs(values: Map<String, Any?>) {
		val editor = prefs.edit().clear()
		for ((key, value) in values) {
			if (key == LEGACY_LOW_LATENCY) continue
			when (value) {
				is Int -> editor.putInt(key, value)
				is Long -> editor.putLong(key, value)
				is Float -> editor.putFloat(key, value)
				is Boolean -> editor.putBoolean(key, value)
				is String -> editor.putString(key, value)
				is Set<*> -> {
					@Suppress("UNCHECKED_CAST")
					editor.putStringSet(key, (value as Set<String>).toSet())
				}
			}
		}
		// 4.1: aquí sí se espera. Es una reposición completa de lo
		// que el motor va a leer, y que se quede a medias en el
		// disco por un proceso matado sería volver al problema que
		// esto arregla.
		editor.commit()
	}

	// ---- idioma de la aplicación -------------------------------------

	/** Etiqueta BCP-47 del idioma de la app ("es", "en"...).
	 *  Vacío significa seguir el idioma del sistema. */
	var appLocale: String
		get() = prefs.getString(KEY_APP_LOCALE, "") ?: ""
		set(value) = prefs.edit().putString(KEY_APP_LOCALE, value).apply()

	// ---- un ajuste propio de cada voz ---------------------------------

	fun shapeValue(voice: Int, param: Int): Int? {
		if (param == Eci.VOICE_SPEED) return speed
		val key = keyOf(voice, param)
		return if (prefs.contains(key)) Eci.clampVoice(param, prefs.getInt(key, 0)) else null
	}

	fun setShapeValue(voice: Int, param: Int, value: Int) {
		if (param == Eci.VOICE_SPEED) {
			speed = value
			return
		}
		write { putInt(keyOf(voice, param), Eci.clampVoice(param, value)) }
	}

	/** Olvida el valor elegido de un parámetro: vuelve al del preset. */
	fun clearShapeValue(voice: Int, param: Int) {
		if (param == Eci.VOICE_SPEED) {
			useSystemSpeed = true
			return
		}
		write { remove(keyOf(voice, param)) }
	}

	/** Lo que se ha elegido para [voice], por el número que toma
	 *  eciSetVoiceParam. Lo que no está aquí queda como lo tiene el
	 *  preset. */
	fun shape(voice: Int): Map<Int, Int> {
		val out = LinkedHashMap<Int, Int>()
		for (param in PER_VOICE) {
			val key = keyOf(voice, param)
			if (prefs.contains(key)) out[param] = Eci.clampVoice(param, prefs.getInt(key, 0))
		}
		out[Eci.VOICE_SPEED] = speed
		return out
	}

	fun writeShape(voice: Int, values: Map<Int, Int>) {
		write {
			for (param in PER_VOICE) {
				remove(keyOf(voice, param))
				values[param]?.let { putInt(keyOf(voice, param), Eci.clampVoice(param, it)) }
			}
		}
		values[Eci.VOICE_SPEED]?.let { speed = it }
	}

	/** Si [voice] se ha cambiado de lo que traía. */
	fun shapeIsCustom(voice: Int): Boolean = PER_VOICE.any { prefs.contains(keyOf(voice, it)) }

	/** Olvida los cambios de una voz. Las demás voces se quedan
	 *  intactas, y la velocidad también, que es de quien escucha. */
	fun clearShape(voice: Int) {
		write {
			for (param in PER_VOICE) remove(keyOf(voice, param))
		}
	}

	private fun keyOf(voice: Int, param: Int) = "voice${voice}_param$param"

	/**
	 * 5.4.3: claves de versiones anteriores que ya no significan nada.
	 * Se borran del archivo al abrir para que no vuelvan a viajar en
	 * una copia de seguridad nueva ni engorden el almacenamiento.
	 */
	private fun dropLegacyKeys() {
		if (prefs.contains(LEGACY_LOW_LATENCY)) {
			prefs.edit().remove(LEGACY_LOW_LATENCY).apply()
		}
	}

	/** Ajustes escritos cuando había un solo conjunto para cada voz.
	 *  Van al idioma que estuviera en vigor en ese momento, que es
	 *  donde se escucharon. */
	private fun carryOverTheOldKeys() {
		val old = Eci.NUM_VOICE_PARAMS.let { 0 until it }.map { "voice_param_$it" }
		if (old.none { prefs.contains(it) }) return
		val edit = prefs.edit()
		val was = prefs.getInt(KEY_VOICE, 0).coerceIn(0, Eci.PRESET_NAMES.lastIndex)
		for (param in 0 until Eci.NUM_VOICE_PARAMS) {
			val from = "voice_param_$param"
			if (!prefs.contains(from)) continue
			val value = prefs.getInt(from, 0)
			if (param == Eci.VOICE_SPEED) {
				edit.putInt(KEY_SPEED, Eci.clampVoice(param, value))
			} else {
				edit.putInt(keyOf(was, param), Eci.clampVoice(param, value))
			}
			edit.remove(from)
		}
		edit.apply()
	}

	// ---- diccionario ----------------------------------------------------

	@kotlinx.serialization.Serializable
	data class DictionaryEntry(
		val id: String,
		val name: String,
		val language: Int,
		val path: String,
		val active: Boolean = false
	)

	var dictionaries: List<DictionaryEntry>
		get() {
			val raw = prefs.getString(KEY_DICTIONARY_LIBRARY, null) ?: return emptyList()
			return runCatching {
				VOICE_JSON.decodeFromString(
					kotlinx.serialization.builtins.ListSerializer(DictionaryEntry.serializer()),
					raw
				)
			}.getOrDefault(emptyList())
				.filter { it.id.isNotBlank() && it.path.isNotBlank() }
				.distinctBy { it.id }
		}
		set(value) {
			val clean = value.filter { it.id.isNotBlank() && it.path.isNotBlank() }
				.distinctBy { it.id }
			val raw = runCatching {
				VOICE_JSON.encodeToString(
					kotlinx.serialization.builtins.ListSerializer(DictionaryEntry.serializer()),
					clean
				)
			}.getOrNull() ?: return
			prefs.edit().putString(KEY_DICTIONARY_LIBRARY, raw).apply()
		}

	var activeDictionaryLanguage: Int
		get() = prefs.getInt(KEY_DICTIONARY_LANGUAGE, resolvedLanguage())
		set(value) = prefs.edit().putInt(KEY_DICTIONARY_LANGUAGE, value).apply()

	/** La ruta de la copia guardada del diccionario activo. */
	var dictionaryPath: String?
		get() = prefs.getString(KEY_DICTIONARY_PATH, null)
		set(value) = prefs.edit().putString(KEY_DICTIONARY_PATH, value).apply()

	/** El nombre que mostrar para el diccionario elegido. */
	var dictionaryName: String?
		get() = prefs.getString(KEY_DICTIONARY_NAME, null)
		set(value) = prefs.edit().putString(KEY_DICTIONARY_NAME, value).apply()

	fun dictionaryForLanguage(language: Int): DictionaryEntry? {
		val current = dictionaries
		current.firstOrNull { it.language == language && it.active }?.let { return it }
		val path = dictionaryPath ?: return null
		val file = java.io.File(path)
		if (!file.exists()) return null
		val legacyLanguage = activeDictionaryLanguage
		if (legacyLanguage != language) return null
		return DictionaryEntry(
			id = "legacy",
			name = dictionaryName?.substringBefore(',')?.trim().orEmpty().ifEmpty { file.name },
			language = language,
			path = path,
			active = true
		)
	}

	fun setDictionary(path: String?, name: String?) {
		if (path == null) {
			write { remove(KEY_DICTIONARY_PATH).remove(KEY_DICTIONARY_NAME) }
			return
		}
		val file = java.io.File(path)
		val cleanName = name?.substringBefore(',')?.trim().orEmpty().ifEmpty { file.name }
		val language = dictionaries.firstOrNull { it.path == path }?.language
			?: activeDictionaryLanguage
		val previous = dictionaries
		val id = previous.firstOrNull { it.path == path }?.id
			?: java.util.UUID.randomUUID().toString()
		val updated = previous.map {
			if (it.language == language) it.copy(active = false) else it
		}.toMutableList()
		updated.removeAll { it.path == path }
		updated.add(DictionaryEntry(id, cleanName, language, path, true))
		dictionaries = updated
		write {
			putString(KEY_DICTIONARY_PATH, path)
				.putString(KEY_DICTIONARY_NAME, cleanName)
				.putInt(KEY_DICTIONARY_LANGUAGE, language)
		}
	}

	fun addDictionary(
		name: String,
		path: String,
		language: Int,
		active: Boolean = true
	): DictionaryEntry {
		val clean = name.trim().take(60).ifEmpty { java.io.File(path).name }
		val entry = DictionaryEntry(
			id = java.util.UUID.randomUUID().toString(),
			name = clean,
			language = language,
			path = path,
			active = active
		)
		var updated = dictionaries
		if (active) {
			updated = updated.map {
				if (it.language == language) it.copy(active = false) else it
			}
		}
		updated = updated + entry
		dictionaries = updated
		if (active) {
			write {
				putString(KEY_DICTIONARY_PATH, path)
					.putString(KEY_DICTIONARY_NAME, clean)
					.putInt(KEY_DICTIONARY_LANGUAGE, language)
			}
		}
		return entry
	}

	fun setActiveDictionary(id: String): DictionaryEntry? {
		val target = dictionaries.firstOrNull { it.id == id } ?: return null
		val updated = dictionaries.map {
			if (it.language == target.language) {
				it.copy(active = it.id == target.id)
			} else {
				it
			}
		}
		dictionaries = updated
		write {
			putString(KEY_DICTIONARY_PATH, target.path)
				.putString(KEY_DICTIONARY_NAME, target.name)
				.putInt(KEY_DICTIONARY_LANGUAGE, target.language)
		}
		return target.copy(active = true)
	}

	fun renameDictionary(id: String, name: String): Boolean {
		val clean = name.trim().take(60)
		if (clean.isEmpty()) return false
		val target = dictionaries.firstOrNull { it.id == id } ?: return false
		dictionaries = dictionaries.map {
			if (it.id == id) it.copy(name = clean) else it
		}
		if (target.active) dictionaryName = clean
		return true
	}

	fun removeDictionaryEntry(id: String, deleteFile: Boolean = true): DictionaryEntry? {
		val target = dictionaries.firstOrNull { it.id == id } ?: return null
		if (deleteFile) runCatching { java.io.File(target.path).delete() }
		val left = dictionaries.filter { it.id != id }
		if (!target.active) {
			dictionaries = left
			return target
		}
		val next = left.lastOrNull { it.language == target.language }
		if (next != null) {
			setActiveDictionary(next.id)
		} else {
			dictionaries = left
			write { remove(KEY_DICTIONARY_PATH).remove(KEY_DICTIONARY_NAME) }
		}
		return target
	}

	/** Palabras propias del usuario (del editor integrado),
	 *  guardadas como pares palabra=pronunciación. Se enseñan
	 *  al motor junto con el archivo.
	 *
	 *  4.4: esto se releía entero **en cada emisión**. [get] parte el
	 *  texto de los ajustes por líneas, saca un `Map` nuevo y lo
	 *  compara después con el anterior entero, porque
	 *  [EvvTtsService.readDictionary] decide si hay que volver a
	 *  preparar el diccionario comparando `words`. Con unas miles de
	 *  palabras propias eso es una adjudicación de miles de cadenas y
	 *  una comparación estructural completa, en el hilo de síntesis,
	 *  antes de la primera muestra y sin que nada haya cambiado.
	 *
	 *  Aquí se parte una vez por cada texto distinto. La clave es el
	 *  texto crudo, así que si el usuario edita una palabra el valor
	 *  cambia y la caché deja de valer sola: no hay un contador que
	 *  pueda quedarse corto.
	 */
	var dictionaryWords: Map<String, String>
		get() {
			val raw = prefs.getString(KEY_DICTIONARY_WORDS, null) ?: return emptyMap()
			dictionaryWordsCache?.let { (cachedRaw, cached) ->
				if (cachedRaw == raw) return cached
			}
			val parsed = try {
				raw.lines().mapNotNull { line ->
					val idx = line.indexOf('=')
					if (idx > 0) line.substring(0, idx) to line.substring(idx + 1) else null
				}.toMap()
			} catch (_: Exception) { emptyMap() }
			dictionaryWordsCache = raw to parsed
			return parsed
		}
		set(value) {
			dictionaryWordsCache = null
			val raw = value.entries.joinToString("\n") { "${it.key}=${it.value}" }
			write { putString(KEY_DICTIONARY_WORDS, raw) }
		}

	/** 4.4: el último texto de [KEY_DICTIONARY_WORDS] ya partido, y su
	 *  mapa. Vive aquí, no en [readDictionary], porque [dictionaryWords]
	 *  lo consulta desde el hilo de síntesis y desde el principal. */
	@Volatile
	private var dictionaryWordsCache: Pair<String, Map<String, String>>? = null

	companion object {
		val VOICE_JSON = kotlinx.serialization.json.Json {
			ignoreUnknownKeys = true
		}

		const val KEY_VOICE = "voice_preset"
		const val KEY_SAMPLE_RATE = "sample_rate_hz"
		const val KEY_DEFAULT_LANGUAGE = "default_language"
		const val KEY_ABBREVIATIONS = "abbreviations"
		/** 4.3: ignorar los códigos de voz (`` `v ``) del texto y de
		 *  los diccionarios. Apagado es lo de fábrica. */
		const val KEY_IGNORE_VOICE_TAGS = "ignore_voice_tags"
		/** Clave del antiguo modo de ultrabaja latencia. Solo se usa
		 *  para dejarla fuera de las copias de seguridad y no
		 *  restaurarla: el modo ya no existe. */
		const val LEGACY_LOW_LATENCY = "low_latency"
		const val KEY_AUTO_DETECT = "auto_detect_language"
		const val KEY_AUTO_VARIANT_EN = "auto_variant_en"
		const val KEY_AUTO_VARIANT_ES = "auto_variant_es"
		const val KEY_AUTO_VARIANT_FR = "auto_variant_fr"
		const val KEY_SPEED = "speed"
		const val KEY_USE_SYSTEM_SPEED = "use_system_speed"
		const val KEY_USE_SYSTEM_VOLUME = "use_system_volume"
		const val KEY_PAUSES = "pauses"
		const val KEY_PAUSE_DURATION_MS = "pause_duration_ms"
		const val KEY_VOLUME = "volume"
		const val KEY_EQ_ENABLED = "eq_enabled"
		const val KEY_EQ_BAND = "eq_band_"
		const val KEY_REVERB_ENABLED = "reverb_enabled"
		const val KEY_REVERB_LEVEL = "reverb_level"
		const val KEY_BASS_DB = "bass_db"
		const val KEY_PRESENCE_DB = "presence_db"
		const val KEY_ECHO_ENABLED = "echo_enabled"
		const val KEY_ECHO_LEVEL = "echo_level"
		const val KEY_ECHO_TIME = "echo_time"
		const val KEY_COMPRESSOR = "compressor"
		const val KEY_BOOST = "boost"
		const val KEY_GATE = "gate"
		const val KEY_TREMOLO = "tremolo"
		const val KEY_TREMOLO_RATE = "tremolo_rate"
		const val KEY_CHORUS = "chorus"
		const val KEY_CRUSHER = "crusher"
		const val KEY_FLANGER = "flanger"
		const val KEY_DEESSER = "deesser"
		/** 4.3: los cinco niveles nuevos de efectos. */
		const val KEY_TAPE = "tape"
		const val KEY_SUB_BASS = "sub_bass"
		const val KEY_REVERB_SIZE = "reverb_size"
		const val KEY_CRUSHER_BITS = "crusher_bits"
		const val KEY_DEESSER_DEPTH = "deesser_depth"
		const val KEY_EXCITER = "exciter"
		const val KEY_WARMTH = "warmth"
		const val KEY_HD_ENHANCE = "hd_enhance"
		const val KEY_FX_PROFILE = "fx_profile"
		const val KEY_FX_PROFILES = "fx_profiles"
		const val KEY_VOICE_PROFILE = "voice_profile"
		const val KEY_VOICE_PROFILES = "voice_profiles"
		const val KEY_APP_LOCALE = "app_locale"
		const val KEY_EMOJIS = "process_emojis"
		const val KEY_UNICODE_SYMBOLS = "unicode_symbols"
		const val KEY_PUNCT = "punct_enabled"
		const val KEY_PUNCT_LEVEL = "punct_level"
		const val KEY_SKIP_PARENS = "skip_parens"
		const val KEY_NUMBER_FORCE = "number_force"
		const val KEY_NUMBER_GROUP = "number_group"
		const val KEY_DICT_ORDER = "dict_order"
		const val KEY_MIX_ENABLED = "mix_enabled"
		const val KEY_MIX_PARTNER = "mix_partner"
		const val KEY_MIX_INTERVAL = "mix_interval"
		const val KEY_MIX_MIGRATED = "mix_words_migrated"
		const val MIN_MIX_WORDS = 5
		const val MAX_MIX_WORDS = 1000
		const val DEFAULT_MIX_WORDS = 20
		const val DEFAULT_MIX_INTERVAL_CHARS = 200
		const val KEY_PHASER = "phaser"
		const val KEY_VIBRATO = "vibrato"
		const val KEY_RINGMOD = "ringmod"
		const val KEY_FUZZ = "fuzz"
		const val KEY_WAH = "wah"
		const val KEY_MAXIMIZER = "maximizer"
		const val KEY_TELEPHONE = "telephone"
		const val KEY_NORMALIZER = "normalizer"
		const val KEY_HIGHPASS = "highpass"
		const val KEY_DRIVE = "drive"
		const val KEY_BODY = "body"
		const val KEY_DICTIONARY_PATH = "dictionary_path"
		const val KEY_DICTIONARY_NAME = "dictionary_name"
		const val KEY_DICTIONARY_WORDS = "dictionary_words"
		const val KEY_DICTIONARY_LIBRARY = "dictionary_library"
		const val KEY_DICTIONARY_LANGUAGE = "dictionary_language"
		const val DEFAULT_SAMPLE_RATE = 11025
		const val DEFAULT_PAUSE_MS = 80

/** Los ocho parámetros en el orden que la pantalla de ajustes los muestra. */
	val SHAPE = listOf(
		Eci.VOICE_GENDER,
		Eci.VOICE_SPEED,
		Eci.VOICE_PITCH_BASELINE,
		Eci.VOICE_PITCH_FLUCTUATION,
		Eci.VOICE_HEAD_SIZE,
		Eci.VOICE_ROUGHNESS,
		Eci.VOICE_BREATHINESS,
		Eci.VOICE_VOLUME
	)

	/** Los siete parámetros que pertenecen a una voz. La
	 *  velocidad es de quien escucha. */
	val PER_VOICE = SHAPE.filter { it != Eci.VOICE_SPEED }

	/** Los siete que son un deslizador. El género es una opción de dos. */
	val SLIDERS = SHAPE.filter { it != Eci.VOICE_GENDER }

}
}
