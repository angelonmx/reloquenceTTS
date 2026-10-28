package com.angelonmx.reloquence

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetEncoder
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

/** Los números que toma la interfaz ECI publicada y las pocas tablas que
 *  los convierten en algo que Android entiende. */
object Eci {

	const val PARAM_SYNTH_MODE = 0
	const val PARAM_INPUT_TYPE = 1
	const val PARAM_TEXT_MODE = 2
	const val PARAM_DICTIONARY = 3
	const val PARAM_SAMPLE_RATE = 5
	const val PARAM_REAL_WORLD_UNITS = 8
	const val PARAM_LANGUAGE_DIALECT = 9
	const val PARAM_NUMBER_MODE = 10

	/**
	 * 4.2: cómo se lee el texto en sí. El motor tiene cuatro modos
	 * (`ev_paramRange[2]` es 0..3) y el que trae de fábrica no es
	 * siempre el de "léelo como texto": hay un modo que deletrea.
	 *
	 * Esto no lo fijaba nadie, y es lo que hace que la puntuación
	 * appeared por sorpresa: un `` `v `` (cambio de voz) mueve el
	 * estado del motor, y a partir de ahí un "1." suelto o un punto
	 * aislado se leían letra a letra o como signo. Fijándolo en
	 * cero en cada emisión, un código de voz ya no puede dejarlo
	 * deletreando: da igual lo que venga antes.
	 */
	const val TEXT_MODE_PLAIN = 0

	/**
	 * 4.2: cómo se leen los números (`ev_paramRange[10]` es 0..1).
	 * Cero es "como número", que es lo natural. Lo mismo que arriba:
	 * se afirma en cada emisión para que nada lo deje en modo
	 * telephone, que es como el motor deletrea los dígitos de uno en
	 * uno.
	 */
	const val NUMBER_MODE_PLAIN = 0

	const val VOICE_GENDER = 0
	const val VOICE_HEAD_SIZE = 1
	const val VOICE_PITCH_BASELINE = 2
	const val VOICE_PITCH_FLUCTUATION = 3
	const val VOICE_ROUGHNESS = 4
	const val VOICE_BREATHINESS = 5
	const val VOICE_SPEED = 6
	const val VOICE_VOLUME = 7

	/** La voz 0 es la que se está hablando; 1 a 8 son los presets del idioma
	 *  y son de solo lectura; 9 a 16 son del llamante para editar. */
	const val VOICE_CURRENT = 0
	const val FIRST_PRESET = 1
	const val LAST_PRESET = 8
	const val SCRATCH_VOICE = 9

	/**
	 * 5.4.2: el número de preset del motor para [index].
	 *
	 * La app numera las voces desde cero (`VoicePlan.Plan.voice`,
	 * `Settings.voice`, [PRESET_NAMES]: Reed es 0) y el motor desde
	 * uno (Reed es 1). Pasar el índice tal cual a `eciCopyVoice` o a
	 * un código `` `vN `` elige otra voz: Shelley acababa sonando
	 * como Reed y Reed no se elegía nunca. La conversión vive aquí
	 * para que no haya dos formas de hacerla.
	 */
	fun enginePreset(index: Int): Int =
		(index + FIRST_PRESET).coerceIn(FIRST_PRESET, LAST_PRESET)

	/** El bit que dice que el texto entregado es UTF-16 en lugar de bytes.
	 *  Viaja en la palabra de idioma, no en un ajuste propio. */
	const val UNICODE_CODE_SET = 0x800

	const val NUM_VOICE_PARAMS = 8

	/** La app usa un solo diccionario, que vive en este volumen. */
	const val DICT_MAIN = 0

	val DICT_VOLUMES = listOf(DICT_MAIN)

	/**
	 * La velocidad a la que cada voz se habla a menos que alguien
	 * haya elegido otra.
	 *
	 * Seis de los ocho presets salen a 50 y Glen y Sandy salen a 70,
	 * que es una vez y media más rápido. Eso es datos de IBM, no un
	 * defecto, pero hace que la velocidad sea una propiedad de la voz,
	 * así que elegir Glen aceleraba todo sin que nadie lo pidiera.
	 * La velocidad es de quien escucha.
	 */
	const val DEFAULT_SPEED = 50

	const val SPEED_MAX = 250
	const val PERCENT_MAX = 100
	const val VOLUME_MAX = 100

	/** Lo que cada uno de los ocho aceptará. El género es una elección
	 *  de dos, la velocidad llega a 250, y el resto a cien. */
	fun voiceRange(param: Int): IntRange = when (param) {
		VOICE_GENDER -> 0..1
		VOICE_SPEED -> 0..SPEED_MAX
		VOICE_VOLUME -> 0..VOLUME_MAX
		else -> 0..PERCENT_MAX
	}

	fun clampVoice(param: Int, value: Int): Int {
		val range = voiceRange(param)
		return value.coerceIn(range.first, range.last)
	}

	/** Los ocho parámetros en el orden que la pantalla de ajustes los muestra. */
	val SHAPE = listOf(
		VOICE_GENDER,
		VOICE_SPEED,
		VOICE_PITCH_BASELINE,
		VOICE_PITCH_FLUCTUATION,
		VOICE_HEAD_SIZE,
		VOICE_ROUGHNESS,
		VOICE_BREATHINESS,
		VOICE_VOLUME
	)

	/** Los siete que pertenecen a una voz. La velocidad es del oyente. */
	val PER_VOICE = SHAPE.filter { it != VOICE_SPEED }

	/** Los siete que son un deslizador. El género es una opción de dos. */
	val SLIDERS = SHAPE.filter { it != VOICE_GENDER }

	/**
	 * Convierte un valor del motor a porcentaje (0-100) para la UI.
	 * Seis de los siete deslizadores usan el rango nativo del motor (0-100),
	 * la velocidad (0-250) se escala a quintas partes.
	 */
	fun toPercent(param: Int, value: Int): Int {
		val top = voiceRange(param).last
		if (top <= 0) return 0
		return Math.round(value * 100.0 / top).toInt().coerceIn(0, PERCENT_MAX)
	}

	/** Convierte un porcentaje (0-100) al valor nativo del motor. */
	fun fromPercent(param: Int, percent: Int): Int {
		val top = voiceRange(param).last
		return Math.round(percent.coerceIn(0, PERCENT_MAX) * top / 100.0).toInt()
	}

	/** Lo que el índice de eciSampleRate significa en hercios, en el
	 *  orden que el motor los numera. Los primeros cuatro son la
	 *  numeración de IBM y el resto son de openevv. */
	val SAMPLE_RATES = intArrayOf(8000, 11025, 22050, 16000, 32000, 44100, 48000)

	fun sampleRateHz(index: Int): Int =
		SAMPLE_RATES.getOrElse(index) { SAMPLE_RATES[1] }

	fun sampleRateIndex(hz: Int): Int {
		val at = SAMPLE_RATES.indexOf(hz)
		return if (at >= 0) at else 1
	}

	/** Los nombres que los ocho presets han llevado desde que Eloquence
	 *  salió al mercado. El motor los numera del 1 al 8 en este orden. */
	val PRESET_NAMES = arrayOf(
		"Reed", "Shelley", "Bobby", "Rocko", "Glen", "Sandy", "Grandma", "Grandpa"
	)

	/** Una palabra de idioma es la familia en la mitad superior, el
	 *  juego de caracteres en el tercer byte y el dialecto en el bajo. */
	fun family(language: Int): Int = language and 0xFFFF0000.toInt()

	fun baseLanguage(language: Int): Int = language and UNICODE_CODE_SET.inv()

	/** Los idiomas compilados en esta versión (ver `languages` en
	 *  app/build.gradle.kts). El japonés es el 0x80000 de IBM.
	 *
	 *  El polaco no está y su módulo tampoco: en el motor está al
	 *  95 % en italiano, así que aquí tampoco. */
	private val LOCALES = mapOf(
		0x00010000 to Triple("eng", "USA", ""),
		0x00010001 to Triple("eng", "GBR", ""),
		0x00020000 to Triple("spa", "ESP", ""),
		// 5.0: 419 es la región M.49 de Latinoamérica. Antes decía
		// "MEX", que es un país: el módulo es el latinoamericano
		// genérico y `onGetLanguage` acababa diciendo México.
		0x00020001 to Triple("spa", "419", ""),
		0x00030000 to Triple("fra", "FRA", ""),
		0x00030001 to Triple("fra", "CAN", ""),
		0x00040000 to Triple("deu", "DEU", ""),
		0x00050000 to Triple("ita", "ITA", ""),
		0x00080000 to Triple("jpn", "JPN", "")
	)

	/**
	 * 4.3: los idiomas cuyo texto hay que entregar en otro juego de
	 * caracteres.
	 *
	 * El motor lee un byte cada vez, y para los ocho idiomas de IBM
	 * ese byte es el carácter, porque todas sus letras están en el
	 * juego occidental de Windows. El japonés no: su motor no habla
	 * UTF-8, y lo que produce el romanizador es Shift-JIS.
	 *
	 * El JNI lo daba por hecho en un comentario ("Kotlin has already
	 * turned it into that set") y no lo hacía nadie, que es por lo que
	 * el japonés se quitó de la lista de idiomas en lugar de
	 * anunciarse como dudoso. Medido con el motor en la máquina:
	 *
	 *     こんにちは  en UTF-8     ->      0 muestras
	 *     こんにちは  en Shift-JIS ->  15.345 muestras
	 *
	 * El UCS-2 también lo acepta el motor, pero Shift-JIS se gana
	 * por lo mismo que ya se usa en el resto de la app: no depende
	 * de que el aparato traiga el juego de caracteres.
	 */
	private val SHIFT_JIS = setOf(0x00080000)

	/** Si el motor de este idioma lee un juego de caracteres propio.
	 *
	 * Lo que decide es cuál de los dos hay que usar, y está en
	 * [encode]. Va aparte porque [EvvEngine.speak] lo pregunta por
	 * cada trozo y no tiene que pagar el precio de codificar para
	 * averiguarlo. */
	fun needsItsOwnCodeSet(language: Int): Boolean =
		language and UNICODE_CODE_SET.inv() in SHIFT_JIS

	/** El texto de este idioma, en los bytes que su motor lee. */
	fun encode(text: String, language: Int): ByteArray {
		if (!needsItsOwnCodeSet(language)) {
			// El resto de idiomas leen el byte tal cual, y el texto de
			// Android es UTF-8.
			return text.toByteArray(Charsets.UTF_8)
		}
		return SHIFT_JIS_ENCODER.encode(latinise(text))
	}

	/**
	 * Pasa a lo que Shift-JIS tiene donde antes, y quita lo que no
	 * tiene de ninguna manera.
	 *
	 * Se cambia en vez de borrar porque la puntuación tipográfica
	 * tiene un equivalente ASCII que sí se dice: borrarla deja
	 * frases sin su interrogación, que es peor que leerla con
	 * ASCII. Lo que no tiene equivalente se quita, y [EmojiSpeech]
	 * ya ha convertido los emojis en palabras antes de llegar aquí.
	 */
	private fun latinise(text: String): String {
		if (text.all { it.code < 0x80 }) return text
		val out = StringBuilder(text.length)
		for (ch in text) {
			// El cero termina el texto para el motor, y un cero en
			// medio es la forma más corta de callarlo entero. Este sí
			// hay que quitarlo siempre.
			if (ch.code == 0) { out.append(' '); continue }
			// La puntuación occidental se cambia por su equivalente
			// ASCII, que es lo que el motor sabe decir con un nombre.
			// ¿Qué tal? leída como "?qu??" no es una pregunta, es una
			// hilera de interrogantes.
			ASCII_EQUIVALENT[ch]?.let { out.append(it); continue }
			// Las letras con acento o con diéresis se quedan sin él:
			// no son cosas japonesas, y el motor leería cada una como
			// un símbolo. Se dice "que" y no "?qu?".
			// Solo se apartan la multiplicación y la división, que no
			// son letras y NFD no las simplifica. El rango entero no,
			// que dentro de él están justamente las acentuadas.
			if (ch.code in 0xC0..0xFF && ch != '×' && ch != '÷') {
				val plain = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
					.filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
				if (plain.isNotEmpty() && plain.all { it.code < 0x80 }) {
					out.append(plain)
					continue
				}
			}
			// Y aquí está lo que no se puede borrar: los kana y los
			// kanji. La primera versión de esto tenía
			// `else -> if (ch.code < 0x80)`, que se llevaba por delante
			// todo el japonés y dejaba la frase vacía: el motor
			// recibía 0 bytes y callaba.
			out.append(ch)
		}
		return out.toString()
	}

	/**
	 * 5.0: la etiqueta BCP-47 de cada módulo del motor.
	 *
	 * [LOCALES] sigue en ISO-3 a propósito: Android pregunta el
	 * idioma con ISO-639-2 (`TextToSpeech.setLanguage` manda
	 * `getISO3Language()` y `onGetLanguage` contesta en ese mismo
	 * registro), y [AutoLang] y los ajustes comparan contra él. Lo
	 * que Android **enseña** y lo que eligen los lectores de
	 * pantalla es otra cosa: un `Locale` de verdad, con su etiqueta
	 * BCP-47. «spa-MEX» no era una etiqueta, era el nombre del
	 * módulo puesto donde va una; de ahí que el japonés saliera como
	 * «jpn-JPN» y que los selectores de voz de terceros no lo
	 * reconocieran.
	 *
	 * `es-419` es la región M.49 de Latinoamérica, que es lo que el
	 * módulo `esus` cubre de verdad. Antes decía `MEX`, que es un
	 * país y no el dialecto.
	 */
	private val TAGS = mapOf(
		0x00010000 to "en-US",
		0x00010001 to "en-GB",
		0x00020000 to "es-ES",
		0x00020001 to "es-419",
		0x00030000 to "fr-FR",
		0x00030001 to "fr-CA",
		0x00040000 to "de-DE",
		0x00050000 to "it-IT",
		0x00080000 to "ja-JP"
	)

	/** El módulo de español latinoamericano. */
	const val SPANISH_LATAM = 0x00020001

	/**
	 * 5.2: la etiqueta que se retiró.
	 *
	 * Hasta 5.1 se anunciaba una segunda voz `es-MX` sobre el mismo
	 * módulo `esus`, con un perfil de prosodia propio. No era un
	 * módulo del motor: `esus` es el español latinoamericano de IBM
	 * y no hay datos mexicanos que compilar. En 5.2 se quitó, y el
	 * módulo se anuncia una sola vez, como `es-419`, que es la
	 * región M.49 que de verdad cubre.
	 *
	 * La constante se queda para **migrar lo que quedó guardado**:
	 * un nombre de voz `es-MX-<preset>` o una etiqueta suelta
	 * `es-MX` se leen como `es-419` (ver [migrateVoiceName] y
	 * [migrateTag]). Quien tenía la variante elegida no se queda
	 * sin voz al actualizar.
	 */
	const val RETIRED_MEXICAN_TAG = "es-MX"

	/** La etiqueta BCP-47 del módulo, o nada si no está compilado. */
	fun bcp47Of(language: Int): String? = TAGS[baseLanguage(language)]

	/** El `Locale` que Android entiende para este módulo. */
	fun androidLocaleOf(language: Int): java.util.Locale? =
		bcp47Of(language)?.let { java.util.Locale.forLanguageTag(it) }

	/**
	 * 5.2: las etiquetas que se anuncian para [language], una por
	 * módulo.
	 *
	 * El español latinoamericano anunciaba dos hasta 5.1 (`es-419` y
	 * `es-MX`); desde 5.2 anuncia solo la del módulo. La variante
	 * mexicana no era un módulo distinto y su perfil de prosodia se
	 * retiró con ella.
	 */
	fun tagsOf(language: Int): List<String> {
		val own = TAGS[baseLanguage(language)] ?: return emptyList()
		return listOf(own)
	}

	/**
	 * 5.2: una etiqueta retirada se lee como la del módulo.
	 *
	 * Esto es la mitad de la migración: lo que quedó guardado como
	 * `es-MX` (una etiqueta suelta, no un idioma ECI) se convierte
	 * en `es-419` al leerlo, sin tocar el resto de etiquetas.
	 */
	fun migrateTag(tag: String?): String? {
		if (tag == null) return null
		return if (tag.equals(RETIRED_MEXICAN_TAG, ignoreCase = true)) {
			bcp47Of(SPANISH_LATAM)
		} else {
			tag
		}
	}

	/**
	 * 5.2: la otra mitad de la migración, en el límite del nombre
	 * de voz.
	 *
	 * Las versiones anteriores anunciaban nombres como
	 * `es-MX-Reed`, y un lector de pantalla puede tenerlos
	 * guardados. Se leen como `es-419-Reed`, que es el mismo módulo
	 * y el mismo preset. Los nombres que no son de la variante
	 * retirada se dejan tal cual.
	 */
	fun migrateVoiceName(name: String?): String? {
		if (name == null) return null
		val prefix = "$RETIRED_MEXICAN_TAG-"
		if (!name.startsWith(prefix, ignoreCase = true)) return name
		val own = bcp47Of(SPANISH_LATAM) ?: return name
		// Se conserva el guion que separa etiqueta y preset:
		// `es-MX-Reed` -> `es-419-Reed`.
		return own + name.substring(RETIRED_MEXICAN_TAG.length)
	}

	/**
	 * El país ISO-3 de una etiqueta anunciada. Android pregunta el
	 * país en ISO-3 (`setLanguage` manda `getISO3Country()`), y la
	 * región de la etiqueta no siempre es ese código: `419` no es un
	 * país sino una región M.49, que se queda tal cual.
	 *
	 * `es-MX` ya no está: se retiró en 5.2 y [migrateTag] lo lleva
	 * a `es-419`.
	 */
	private val TAG_COUNTRY = mapOf(
		"en-US" to "USA", "en-GB" to "GBR",
		"es-ES" to "ESP", "es-419" to "419",
		"fr-FR" to "FRA", "fr-CA" to "CAN",
		"de-DE" to "DEU", "it-IT" to "ITA", "ja-JP" to "JPN"
	)

	fun tagCountry(tag: String): String? = TAG_COUNTRY[tag]

	/** Lo que se escribe con una tecla y el motor solo lee en ASCII. */
	private val ASCII_EQUIVALENT = mapOf(
		'‘' to '\'', '’' to '\'', '‚' to '\'', '‛' to '\'',
		'“' to '"', '”' to '"', '„' to '"', '‟' to '"',
		'«' to '"', '»' to '"',
		'–' to '-', '—' to '-', '‒' to '-', '―' to '-',
		'…' to '.', ' ' to ' ', ' ' to ' ',
		'¿' to '?', '¡' to '!',
		'′' to '\'', '″' to '"', '‑' to '-'
	)

	/**
	 * El codificador, uno solo y no uno por frase.
	 *
	 * Crearlo cuesta, y `speak` se llama por cada trozo de cada
	 * frase, así que uno por llamada sería una adjudicación por
	 * trozo. No es un singleton por hilo: [CharsetEncoder] no es
	 * seguro de usar entre hilos a la vez, y aquí se usa desde el
	 * hilo de síntesis y del principal a la vez.
	 */
	private val SHIFT_JIS_ENCODER = ThreadSafeEncoder()

	/**
	 * Un codificador con candado.
	 *
	 * [CharsetEncoder] guarda estado entre llamadas, así que dos
	 * hilos codificando a la vez se pisan. El candado dura lo que la
	 * codificación de una frase, que son microsegundos, y no se nota;
	 * lo que no se puede es que se pisen.
	 *
	 * Se pasa por [ByteBuffer] y no por un flujo porque la forma de
	 * tres argumentos de `encode` --la que vacía el estado-- es la de
	 * ByteBuffer, y vaciar el estado es justo lo que se necesita aquí.
	 */
	private class ThreadSafeEncoder {
		private val lock = Any()
		// IGNORE y no REPLACE: un carácter que no cabe se queda sin
		// sitio, y un sustituto sería un signo de interrogación, que
		// el motor sí leería y diría. Entre "nada" y "¿qué?" es
		// mejor nada, y [EmojiSpeech] ya ha cambiado los emojis por
		// palabras mucho antes de aquí.
		private val encoder: CharsetEncoder = Charset.forName("Shift_JIS").newEncoder()
			.onMalformedInput(CodingErrorAction.IGNORE)
			.onUnmappableCharacter(CodingErrorAction.IGNORE)

		fun encode(text: String): ByteArray = synchronized(lock) {
			val source = CharBuffer.wrap(text)
			// Shift-JIS son dos bytes por carácter como mucho, más el
			// resto del búfer por si el codificador va por trozos.
			var target = ByteBuffer.allocate(text.length * 2 + 16)
			while (true) {
				val outcome = encoder.encode(source, target, true)
				if (outcome.isOverflow) {
					target = ByteBuffer.allocate(target.capacity() * 2)
					continue
				}
				break
			}
			val out = ByteArray(target.position())
			target.flip()
			target.get(out)
			out
		}
	}

	/** El idioma y país ISO-3 a los que responde un idioma de motor,
	 *  que es lo que Android pregunta. */
	fun localeOf(language: Int): Triple<String, String, String>? =
		LOCALES[baseLanguage(language)]

	/**
	 * 4.4: el nombre que se enseña de cada idioma, como recurso.
	 *
	 * Antes salía de `Locale.getDisplayName`, que devuelve el nombre
	 * de la región tal como el sistema lo tiene. Para el español eso
	 * son "español (España)" y "español (México)", y lo segundo es
	 * lo que estaba mal: el módulo `esus` no es de México, es el
	 * español latinoamericano de IBM, que cubre todo lo que no es
	 * España. Lo que además sale depende del idioma del aparato: en
	 * inglés pone "Spanish (Spain)" y en alemán "Spanisch (Spanien)",
	 * así que el mismo motor se llamaba de tres maneras.
	 *
	 * El nombre del motor es el del módulo, no el de un país, así que
	 * va en los recursos y sale siempre igual: **Español (España)** y
	 * **Español (Latinoamérica)**.
	 *
	 * Lo que [displayName] devuelve sin recursos ([nameRes] == 0) es
	 * la marca, que no es traducible, y lo usa quien no tiene contexto.
	 */
	fun nameRes(language: Int): Int =
		NAME_RES[baseLanguage(language)] ?: 0

	fun displayName(language: Int): String {
		val fixed = FIXED_NAMES[baseLanguage(language)]
		if (fixed != null) return fixed
		val locale = androidLocaleOf(language) ?: return "0x%08x".format(language)
		return locale.getDisplayName(java.util.Locale.getDefault())
	}

	/**
	 * Los dos nombres de español que el usuario pidió, y que no son
	 * los que decía el sistema. Están en [nameRes] para traducirlos,
	 * y aquí para quien no tiene contexto.
	 */
	private val FIXED_NAMES = mapOf(
		0x00020000 to "Español (España)",
		0x00020001 to "Español (Latinoamérica)"
	)

	/** Nombre del idioma para mostrar. */
	fun languageName(language: Int): String {
		val locale = androidLocaleOf(language) ?: return "???"
		return locale.displayLanguage
	}

	/**
	 * 4.4: los dos nombres de español como recurso, para que la
	 * pantalla los traduzca.
	 *
	 * Las claves son los idiomas base, con el bit de juego de
	 * caracteres apartado, porque así es como los guarda
	 * [LOCALES].
	 *
	 * 5.2: ya no hay un tercer nombre para `es-MX`: esa variante se
	 * retiró y el módulo `esus` se llama solo «Español
	 * (Latinoamérica)».
	 */
	private val NAME_RES = mapOf(
		0x00020000 to R.string.lang_es_es,
		0x00020001 to R.string.lang_es_latam
	)

}