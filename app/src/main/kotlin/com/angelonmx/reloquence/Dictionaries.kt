package com.angelonmx.reloquence

import timber.log.Timber
import java.io.File

/**
 * El diccionario de pronunciación que la gente escribe para este motor.
 *
 * El motor tiene un cargador propio, y no acepta estos archivos:
 * eciLoadDict contesta eciDictAccessError para cada uno de ellos,
 * porque lo que lee es la forma guardada propia de IBM y lo que la
 * gente comparte es texto. Así que el texto se lee aquí y al motor
 * se le enseña palabra por palabra, que es la misma interfaz que
 * hubiera usado el cargador y no depende de ponerse de acuerdo
 * sobre un formato de archivo.
 *
 * Hay dos formatos, y ambos son texto con una entrada por línea:
 *
 * - Eloquence (.cfd): la forma propia del diccionario de usuario,
 *   tal como la escribe Eloquence. Cada línea envuelve ocho campos
 *   con un delimitador que no aparece en la palabra ni en lo que
 *   se dice, punto, coma o admiración por ese orden:
 *
 *     .gmail.llámeil.*.*.*.0.0.
 *     ,a. c,antes de cristo,0x0a,*,*,0,0,
 *
 *   Los campos son palabra, pronunciación (reescritura como
 *   "llámeil" o alfabeto del motor como "`[Ekspoz1e]", que el
 *   motor acepta ambas), atributos ("*" o bits como 0x0a),
 *   origen ("*" o un nombre), "*", y dos números. Al enseñar
 *   solo importan palabra y pronunciación: los atributos son
 *   concordancias del original que teachWord no toma.
 * - Reloquence (.reloqdic): cuerpo tabulado ("palabra\tsay") con
 *   una cabecera "# reloqdic v1" que lo identifica. Al leer se
 *   acepta además "clave=valor", "clave=>valor" y "clave:valor",
 *   comentarios (";", "#", "//"), secciones ("[sección]"),
 *   comillas alrededor de la clave y líneas .cfd pegadas, para
 *   que entre lo más limpio posible lo que la gente ya tiene.
 *
 * Los .cfd originales de Eloquence son UTF-16 con BOM y fin de
 * línea CRLF (ELOQMAIN.CFD: FE FF, 4461 líneas así), y así se
 * escriben de vuelta. Al leer se detecta UTF-8 o UTF-16 con o
 * sin BOM, y Latin-1 como último recurso, que es el propio juego
 * de caracteres del motor (un acento e es un byte).
 *
 * 4.1, dos cosas que hacían que un diccionario grande no entrara
 * ni la mitad de lo que trae, y que se decían como "el motor no
 * cambia algunas palabras":
 *
 * - **Los blancos de las claves.** Las claves de un .cfd de
 *   Eloquence traen las horas como "10:00 a. m." con un espacio
 *   inseparable (U+00A0) entre la "a." y la "m.". El texto que
 *   entrega Android trae un espacio corriente. El patrón casaba
 *   con la clave y luego la regla no se encontraba con ninguna
 *   forma, así que la palabra se devolvía sin cambiar: mil
 *   claves de un diccionario de horas eran mil palabras mudas.
 *   Ahora toda clave se guarda con sus blancos reducidos a un
 *   espacio sencillo ([normKey]) y el patrón casa con cualquier
 *   blanco, y "a. m." y "a.m." son la misma regla.
 * - **El tope de mil frases.** Antes la alternancia se cortaba a
 *   las mil claves de frase más largas, y en un diccionario de
 *   horas eso se comía exactamente las que sirven (todas miden lo
 *   mismo) y dejaba las oraciones de Telegram, que son las más
 *   largas. No hay tope ahora: las claves de frase van en cubos
 *   por largo, que es lo que hace que una alternancia de cuatro
 *   mil claves no tarde segundos ([SubCache]).
 */
object Dictionaries {

	/** Lee las líneas de [archivo] detectando su codificación:
	 *  UTF-8 o UTF-16 con o sin BOM (los .cfd originales de
	 *  Eloquence suelen ser Unicode UTF-16BE), y Latin-1 como
	 *  último recurso, que es el juego del motor. */
	fun readLines(file: File): List<String> = try {
		val bytes = file.readBytes()
		val text = when {
			bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
				String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
			bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
				String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
			bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
				String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
			isUtf8(bytes) -> String(bytes, Charsets.UTF_8)
			else -> utf16WithoutBom(bytes) ?: String(bytes, Charsets.ISO_8859_1)
		}
		text.lines()
	} catch (unreadable: Exception) {
		Timber.e(unreadable, "no se puede leer ${file.name}")
		emptyList()
	}

	/** UTF-16 sin BOM, por la cantidad de ceros: en ASCII los
	 *  ceros van en posiciones impares (LE) o pares (BE). Sin
	 *  ceros suficientes no es UTF-16 y vuelve nada. */
	private fun utf16WithoutBom(bytes: ByteArray): String? {
		if (bytes.size < 100) return null
		var nul = 0
		var oddNul = 0
		var evenNul = 0
		for (i in bytes.indices) {
			if (bytes[i] == 0.toByte()) {
				nul++
				if (i % 2 == 1) oddNul++ else evenNul++
			}
		}
		if (nul * 10 < bytes.size * 3) return null
		return if (oddNul >= evenNul) String(bytes, Charsets.UTF_16LE)
		else String(bytes, Charsets.UTF_16BE)
	}

	private fun isUtf8(bytes: ByteArray): Boolean = try {
		java.nio.charset.Charset.forName("UTF-8").newDecoder()
			.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
			.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
			.decode(java.nio.ByteBuffer.wrap(bytes))
		true
	} catch (_: Exception) {
		false
	}

	/** Sustituye en [text] cada clave de [entries] por su
	 *  pronunciación. Mayúsculas solo cuando importan: una clave
	 *  toda en mayúsculas ("ESA", "BMW", "HD") solo entra si el
	 *  texto también viene en mayúsculas, que es como Eloquence
	 *  la guarda (num1=1) y lo que evita que "esa" se deletree
	 *  como E S A. Lo demás sigue sin importar mayúsculas
	 *  ("YouTube" entra aunque diga "youtube"). Solo palabras
	 *  completas, y los números no rompen horas ni decimales:
	 *  "00" no entra en "1:00" ni "1.000" dentro de "11.000".
	 *
	 *  4.1: las claves se buscan ya normalizadas ([normKey]), así
	 *  que un espacio inseparable en la clave casa con un espacio
	 *  corriente en el texto y al revés.
	 *
	 *  Si la pronunciación trae anotaciones propias (`` `vb ``,
	 *  `` `vs ``...), detrás se deja [reset], que devuelve la voz
	 *  a la de la emisión: sin eso el tono se queda pegado en lo
	 *  que sigue, porque el motor lo aplica desde ahí en adelante.
	 *  Sin anotaciones no se añade nada. */
	/**
	 * 4.3: olvida la alternancia de frases ya armada.
	 *
	 * La llaman el servicio de voz y la vista previa cuando cambian
	 * los ajustes o el diccionario, para que la siguiente sustitucion
	 * se arme con lo de ahora. Sin esto, importar una copia dejaba
	 * la lista buena pero la alternancia con el reinicio de voz
	 * anterior, y la prosodia no cuadraba hasta que algo mas
	 * cambiaba.
	 */
	fun invalidateSubCache() = SubCache.invalidate()

	fun substitute(text: String, entries: List<Pair<String, String>>, reset: String = ""): String {
		if (text.isEmpty() || entries.isEmpty()) return text
		val cached = SubCache.get(entries, reset) ?: return text
		// 4.3: una entrada con el mismo texto y distinto reinicio se
		// sustituye distinto, asi que la cache se deshace sola cuando
		// el codigo de reinicio que llega no es el que tiene. Es lo
		// que pasa tras importar una copia o reiniciar el motor: la
		// voz es otra y la alternancia se quedo armada con el
		// reinicio de antes.

		// 4.1: frases primero (largas, pocas), luego palabras por
		// tokens (lineal). Antes una sola alternancia gigante con
		// miles de claves mezclaba todo y tardaba segundos; ahora
		// las frases van en cubos por largo, que es lo mismo de
		// rápido sin el tope que se comía media entrada.
		var out = substitutePhrases(text, cached, reset)
		if (!cached.hasWords) return out
		// Claves de pura palabra van por tokens: lineal en textos
		// larguísimos en vez de una alternancia gigante.
		return TOKENS.replace(out) { m ->			val rule = cached.byLower[normKey(m.value)] ?: return@replace m.value
			// Solo reglas de una palabra entran por aquí; las frases
			// ya se resolvieron arriba.
			if (rule.trimmed.any { !it.isLetterOrDigit() && it != '_' }) return@replace m.value
			if (!ruleAccept(rule, m.value, m.range.first, m.range.last + 1, out)) return@replace m.value
			withReset(rule.say, reset)
		}
	}

	/**
	 * Una clave de diccionario tal como se va a buscar y como se
	 * guarda: sin blancos en las bordes y con las corridas de
	 * blanco reducidas a un espacio sencillo, en minúsculas y con
	 * las vocales acentuadas plegadas.
	 *
	 * 4.1: esto es lo que arregla las horas. Las claves de un .cfd
	 * de Eloquence traen "10:00 a. m." con espacio inseparable
	 * (U+00A0) y el texto de Android trae un espacio corriente;
	 * sin reducirlo, la clave casaba con el patrón y luego no se
	 * encontraba con ninguna forma, así que la palabra se
	 * devolvía tal cual. Con la clave ya normalizada, "a. m." y
	 * "a.m." son la misma regla, y el patrón casa con cualquiera
	 * de las dos escrituras.
	 */
	fun normKey(key: String): String {
		var out: StringBuilder? = null
		var pending = false
		for (i in key.indices) {
			val c = key[i]
			if (c.isWhitespace() || c in EXOTIC_BLANKS) {
				if (out == null) {
					out = StringBuilder(key.length)
					out.append(key, 0, i)
				}
				pending = true
			} else {
				if (pending) {
					out?.append(' ')
					pending = false
				}
				out?.append(c)
			}
		}
		if (out == null) return foldVowels(key.lowercase(java.util.Locale.ROOT))
		if (pending) out.append(' ')
		return foldVowels(out.toString().lowercase(java.util.Locale.ROOT))
	}

	/**
	 * La misma clave sin el blanco de una abreviatura: "10:00 a. m."
	 * y "10:00 a.m." son la misma regla y el patrón casa con las
	 * dos, así que la busca tiene que encontrarla con las dos. El
	 * índice guarda la forma canónica y también esta, que es como
	 * se llega a la misma regla desde el texto compacto.
	 */
	fun abbrevKey(key: String): String {
		val norm = normKey(key)
		if (' ' !in norm) return norm
		val out = StringBuilder(norm.length)
		for (at in norm.indices) {
			val c = norm[at]
			if (c == ' ' && at > 0 && norm[at - 1] in ABBREV_BEFORE) continue
			out.append(c)
		}
		return out.toString()
	}

	/**
	 * Una clave tal como se guarda, sin bajar la caja: es la que se
	 * enseña al motor y la que la pantalla de ajustes muestra, así
	 * que tiene que seguir siendo la que escribió quien hizo el
	 * archivo. Lo único que se toca son los blancos.
	 */
	fun normWord(word: String): String = normBlanksOnly(word)

	private fun normBlanksOnly(text: String): String {
		if (text.isEmpty()) return text
		var out: StringBuilder? = null
		var pending = false
		for (i in text.indices) {
			val c = text[i]
			if (c.isWhitespace() || c in EXOTIC_BLANKS) {
				if (out == null) {
					out = StringBuilder(text.length)
					out.append(text, 0, i)
				}
				pending = true
			} else {
				if (pending) {
					out?.append(' ')
					pending = false
				}
				out?.append(c)
			}
		}
		if (out == null) return text
		if (pending) out.append(' ')
		return out.toString()
	}

	/** Los blancos que no son un espacio y se leen como uno: el
	 *  inseparable, el fino, el estrecho inseparable, el de CJK y
	 *  el de ancho fijo. */
	private const val EXOTIC_BLANKS = "\u00A0\u2007\u202F\u3000\u2009"

	/**
	 * Las frases de [text], de la más larga a la más corta y sin
	 * solaparse: donde dos casan, manda la más larga, que es lo
	 * que hace una alternancia con las alternativas ordenadas de
	 * más larga a más corta.
	 *
	 * 4.1: antes era una alternancia de todas las claves de frase
	 * con un tope de mil, y el tope se llevaba por delante las
	 * claves útiles (en un diccionario de horas, todas las claves
	 * de frase miden lo mismo, así que se las comía enteras). Aquí
	 * no hay tope: [SubCache] las parte en cubos por largo, cada
	 * uno con su alternancia, y todos se recorren. Un tope de
	 * seguridad queda para el caso absurdo de un archivo con
	 * millones de entradas, y entonces lo que se deja fuera es lo
	 * más corto, que es lo menos probable que se diga.
	 */
	private fun substitutePhrases(text: String, cached: Hit, reset: String): String {
		val buckets = cached.buckets
		if (buckets.isEmpty()) return text
		// El ajuste de la ventana que se está procesando: una clave
		// más larga que la ventana no puede casar, y eso lo mira
		// [PhraseBucket.mayAppearIn] con el largo más corto.
		val found = ArrayList<HitRange>()
		for (bucket in buckets) {
			if (!bucket.mayAppearIn(text)) continue
			for (m in bucket.regex.findAll(text)) {
				found.add(HitRange(m.range.first, m.range.last + 1, m.value))
			}
		}
		if (found.isEmpty()) return text
		// Primero por dónde empieza, y a igual comienzo la más
		// larga: así la larga se pone y la corta se salta por
		// solapada, que es lo mismo que gana la larga.
		found.sortWith(compareBy({ it.start }, { -(it.end - it.start) }))
		val out = StringBuilder(text.length + 64)
		var at = 0
		var used = 0
		for (h in found) {
			if (h.start < at) continue
			val say = ruleFor(cached, h.matched, h.start, h.end, text) ?: continue
			out.append(text, at, h.start).append(say)
			at = h.end
			if (++used >= MAX_PHRASES) break
		}
		if (used == 0) return text
		out.append(text, at, text.length)
		return out.toString()
	}

	/** La regla que corresponde a lo que casó, o nada si ninguna. */
	private fun ruleFor(cached: Hit, matched: String, start: Int, end: Int, text: String): String? {
		// 4.1: dos formas de buscar la misma regla, porque el patrón
		// es más flexible que la clave: "10:00 a. m." del diccionario
		// casa con "10:00 a.m." del texto, y con la forma canónica
		// sola no se encontraba y la hora se devolvía sin cambiar.
		val rule = cached.byLower[normKey(matched)]
			?: cached.byLower[abbrevKey(matched)]
			?: cached.byExact[matched]
			?: return null
		if (ruleAccept(rule, matched, start, end, text)) return withReset(rule.say, cached.reset)
		// La forma exacta, por si la alternancia casó una escritura
		// que la regla no acepta (mayúsculas, números pegados).
		val exact = cached.byExact[matched] ?: return null
		if (!ruleAccept(exact, matched, start, end, text)) return null
		return withReset(exact.say, cached.reset)
	}

	/** La pronunciación con el reset detrás si trae anotaciones. */
	private fun withReset(say: String, reset: String): String =
		if (reset.isNotEmpty() && '`' in say) "$say $reset" else say

	private class HitRange(val start: Int, val end: Int, val matched: String)

	/** Una clave toda en mayúsculas ("ESA", "BMW", "SL") solo vale
	 *  en mayúsculas: así "esa" no se deletrea como E S A. Dos
	 *  letras bastan ("SO", "SU"), una no ("y", "a"). */
	fun isAllCapsKey(trimmed: String): Boolean {
		if (trimmed.length < 2) return false
		var hasLetter = false
		for (c in trimmed) {
			if (c.isLetter()) {
				hasLetter = true
				if (c != c.uppercaseChar()) return false
			}
		}
		return hasLetter
	}

	/** Si [key] conviene no enseñarla al motor y dejarla solo a la
	 *  sustitución en texto: mayúsculas estrictas (el motor compara
	 *  sin caja y rompería "esa") y números puros ("00" rompería
	 *  "1:00"). La sustitución ya los protege. */
	fun skipTeach(key: String): Boolean {
		val t = key.trim()
		if (t.isEmpty()) return true
		if (isAllCapsKey(t)) return true
		if (t.all { it.isDigit() }) return true
		return false
	}

	/**
	 * Si [key] es una entrada de deletreo: una sola letra, en
	 * minúsculas, tal como los diccionarios de letras traen
	 * ("b" = "be", "v" = "uve", "y" = "i").
	 *
	 * 4.1: sin esto, un diccionario de letras se come el texto
	 * entero, porque "y" pasa a ser "i" en cada "y" del español y
	 * "b" pasa a ser "be" en cada palabra que la lleve. Solo
	 * cuentan cuando la letra del texto viene en mayúscula, que es
	 * donde de verdad se está deletreando. Quien quiera la entrada
	 * siempre la escribe con mayúscula en el diccionario.
	 */
	fun isSpellingLetter(key: String): Boolean {
		if (key.length != 1) return false
		val c = key[0]
		if (!c.isLetter()) return false
		return c == c.lowercaseChar()
	}

	private data class Rule(
		val raw: String,
		val trimmed: String,
		val say: String,
		val caseSensitive: Boolean,
		val hasDigit: Boolean,
		val leadSpace: Boolean,
		val trailSpace: Boolean,
		/**
		 * 4.1: la clave es una letra suelta y en minúsculas, que es
		 * una entrada de deletreo ("b" = "be", "v" = "uve", "y" =
		 * "i"). Aplicada a todo lo que pasa por delante convierte la
		 * conjunción "y" en "i" y cada palabra con "b" o "v" en
		 * "be" o "uve", que es como un diccionario de letras se
		 * come el texto entero y hace que la voz suene que no se
		 * respetan los ajustes. Solo entra si la letra del texto
		 * viene en mayúscula, que es donde de verdad se está
		 * deletreando.
		 */
		val needsUpper: Boolean = false
	)

	private fun ruleAccept(rule: Rule, matched: String, start: Int, end: Int, text: String): Boolean {
		if (rule.caseSensitive && matched != rule.trimmed) return false
		if (rule.needsUpper && !matched.any { it.isUpperCase() }) return false
		if (rule.leadSpace) {
			if (start > 0 && !text[start - 1].isWhitespace()) return false
		}
		if (rule.trailSpace) {
			if (end < text.length && !text[end].isWhitespace()) return false
		}
		if (rule.hasDigit) {
			val prev = if (start > 0) text[start - 1] else ' '
			val next = if (end < text.length) text[end] else ' '
			if (prev.isDigit() || prev == ':' || prev == '.' || prev == ',' || prev == '/') return false
			if (next.isDigit() || next == ':' || next == '.' || next == ',' || next == '/') return false
		}
		return true
	}

	private val TOKENS = Regex("[\\p{L}\\p{N}_]+")

	/** Una corrida de blancos para casar y normalizar: espacio,
	 *  tabulación, saltos de línea, insecable y espacio estrecho.
	 *  Todo lo que [EXOTIC_BLANKS] y `isWhitespace` cubren. */
	private const val WS_CLASS = "[ \\t\\n\\r\\u00A0\\u2007\\u202F\\u2009\\u3000]+"
	/** El mismo blanco, pero de cero en uno: el de una abreviatura,
	 *  donde "a. m." y "a.m." son la misma cosa. */
	private const val WS_OPTIONAL = "[ \\t\\n\\r\\u00A0\\u2007\\u202F\\u2009\\u3000]*"

	/** Pliega vocales acentuadas a su base (á→a, ü→u...): "cómo"
	 *  casa con "como" y al revés, en claves y en texto. La ñ se
	 *  queda como está (año no es ano) y la caja la cubre la
	 *  búsqueda insensible. Camino rápido para ASCII puro. */
	private fun foldVowels(raw: String): String {
		var out: StringBuilder? = null
		for (i in raw.indices) {
			val rep = when (raw[i]) {
				'á', 'à', 'ä', 'â' -> 'a'
				'é', 'è', 'ë', 'ê' -> 'e'
				'í', 'ì', 'ï', 'î' -> 'i'
				'ó', 'ò', 'ö', 'ô' -> 'o'
				'ú', 'ù', 'ü', 'û' -> 'u'
				else -> null
			}
			if (rep != null) {
				if (out == null) {
					out = StringBuilder(raw.length)
					out.append(raw, 0, i)
				}
				out.append(rep)
			} else if (out != null) {
				out.append(raw[i])
			}
		}
		return out?.toString() ?: raw
	}

	/**
	 * Una clave ya plegada como fragmento que casa con blancos
	 *  corridos y con cada vocal en cualquiera de sus acentos.
	 *
	 * 4.1: el blanco de una abreviatura es opcional, y con eso
	 * "10:00 a. m." (que es como los diccionarios de Eloquence
	 * escriben la hora, con espacio o con espacio inseparable)
	 * casa con "10:00 a.m.", que es como la escribe la gente. El
	 * blanco entre dos palabras de verdad sigue siendo obligatorio:
	 * "antes de cristo" no casa con "antesdecristo", que es donde
	 * esto se volvería un problema.
	 */
	private fun flexibleFragment(folded: String): String {
		val out = StringBuilder(folded.length + 16)
		for (at in folded.indices) {
			val c = folded[at]
			when (c) {
				' ' -> {
					val before = if (at > 0) folded[at - 1] else ' '
					out.append(if (before in ABBREV_BEFORE) WS_OPTIONAL else WS_CLASS)
				}
				'a' -> out.append("[aáàäâ]")
				'e' -> out.append("[eéèëê]")
				'i' -> out.append("[iíìïî]")
				'o' -> out.append("[oóòöô]")
				'u' -> out.append("[uúùüû]")
				else -> out.append(Regex.escape(c.toString()))
			}
		}
		return out.toString()
	}

	/** Lo que deja el blanco de una abreviatura ser opcional: el
	 *  punto (o la coma) de la abreviatura, detrás del cual el
	 *  blanco no separa nada. */
	private const val ABBREV_BEFORE = ".,'’"

	/** Si alguna pronunciación cambia de voz (`` `v `` más dígito),
	 *  que no se deshace con restaurar parámetros: pide
	 *  re-aplicar la voz entera en la siguiente emisión. */
	private val VOICE_SELECT = Regex("`v\\d")

	fun hasVoiceSelect(entries: List<Pair<String, String>>): Boolean =
		entries.any { (_, say) -> VOICE_SELECT.containsMatchIn(say) }

	/** Lo mismo pero en texto ya armado (códigos escritos a mano
	 *  en lo que se lee, no solo en el diccionario). */
	fun hasVoiceSelectText(text: String): Boolean = VOICE_SELECT.containsMatchIn(text)

	private data class Hit(
		val buckets: List<PhraseBucket>,
		val byLower: Map<String, Rule>,
		val byExact: Map<String, Rule>,
		val hasWords: Boolean,
		val reset: String
	)

	/**
	 * 4.1: un cubo de claves de frase todas del mismo largo, con
	 * su alternancia. El largo es el número de caracteres de la
	 * clave ya normalizada.
	 *
	 * que puede casar. [minLength] es el más corto, que sale
	 * quitando un carácter por cada blanco que [flexibleFragment]
	 * dejó opcional: "10:00 a. m." mide once pero casa con
	 * "10:00 a.m.", que mide diez. Comparar solo con el largo
	 * mayor descartaba el cubo entero en una ventana corta, que es
	 * justo donde la hora se dice.
	 */
	private class PhraseBucket(
		val length: Int,
		private val minLength: Int,
		private val firsts: BooleanArray?,
		val regex: Regex
	) {
		/**
		 * Si el texto tiene algún carácter con el que puede empezar
		 * una clave de este cubo. El filtro es de un solo carácter
		 * y en minúsculas, así que es barato y quita el 95 por
		 * ciento de los cubos: el de las claves que empiezan por
		 * "2" no se mira en un texto que no tiene un 2.
		 *
		 * Con una clave que empiece por algo fuera de ASCII el
		 * filtro no se hace ([firsts] nulo) y el cubo se mira
		 * siempre: es un caso raro y no vale la pena.
		 */
		fun mayAppearIn(text: String): Boolean {
			if (minLength > text.length) return false
			val set = firsts ?: return true
			for (c in text) {
				val k = c.lowercaseChar()
				if (k.code <= 255 && set[k.code]) return true
			}
			return false
		}
	}

	private object SubCache {
		private var entries: List<Pair<String, String>>? = null
		private var buckets: List<PhraseBucket> = emptyList()
		private var byLower: Map<String, Rule> = emptyMap()
		private var byExact: Map<String, Rule> = emptyMap()
		private var hasWords = false
		private var reset: String = ""

		/**
		 * 4.3: olvida lo que tiene.
		 *
		 * La caché compara la lista por referencia, así que ya se
		 * rehace sola en cuanto la lista cambia. Esto es para el
		 * otro caso: que la lista no cambie de referencia y lo que
		 * cambie sea el codigo de reinicio de la voz que viaja con
		 * la sustitucion, que es lo que pasa tras importar una copia
		 * o reiniciar el motor. Sin esto la alternancia queda armada
		 * con el reinicio viejo y la primera frase sale con la
		 * prosodia de la anterior.
		 */
		@Synchronized
		fun invalidate() {
			entries = null
			buckets = emptyList()
			byLower = emptyMap()
			byExact = emptyMap()
			hasWords = false
			reset = ""
		}

		@Synchronized
		fun get(want: List<Pair<String, String>>, resetCode: String = ""): Hit? {
			if (want === entries && (buckets.isNotEmpty() || hasWords)) {
				if (resetCode == reset) return Hit(buckets, byLower, byExact, hasWords, reset)
			}
			if (want === entries && byLower.isEmpty() && resetCode == reset) return null
			// Identidad y no igualdad: comparar miles de pares en
			// cada emisión costaba de balde; la lista solo cambia
			// de referencia cuando cambia su contenido.
			if (want !== entries) {
				val lower = LinkedHashMap<String, Rule>()
				val aliases = LinkedHashMap<String, Rule>()
				val exact = LinkedHashMap<String, Rule>()
				for ((key, value) in want) {
					if (key.isEmpty()) continue
					val clean = key.trim()
					if (clean.isEmpty()) continue
					if (clean.length > MAX_KEY || value.length > MAX_SAY) continue
					// Plegada, sin blancos raros y en minúsculas: "cómo"
					// y "como" son la misma regla (gana la primera) y
					// casan con el texto venga con acento o sin él, y
					// con el blanco que traiga (4.1).
					val low = normKey(clean)
					if (low.isEmpty() || lower.containsKey(low)) continue
					val rule = Rule(
						raw = key,
						trimmed = clean,
						say = value,
						caseSensitive = isAllCapsKey(clean),
						hasDigit = clean.any { it.isDigit() },
						leadSpace = key.first().isWhitespace(),
						trailSpace = key.last().isWhitespace(),
						needsUpper = isSpellingLetter(clean)
					)
					lower[low] = rule
					exact.putIfAbsent(clean, rule)
					// 4.1: la misma regla, indexada también por su forma
					// sin el blanco de la abreviatura, para que se
					// encuentre desde el texto compacto ("a.m."). Van
					// aparte porque los cubos se arman con [lower], que
					// no trae los alias, así que el patrón no se duplica.
					val short = abbrevKey(clean)
					if (short != low) aliases.putIfAbsent(short, rule)
				}
				if (lower.isEmpty()) {
					entries = want
					buckets = emptyList()
					byLower = emptyMap()
					byExact = emptyMap()
					hasWords = false
					reset = resetCode
					return null
				}
				// 4.1: sin tope, en cubos por largo. Una alternancia
				// de cuatro mil claves mezcladas tardaba segundos; por
				// cubos, cada uno con su alternancia corta, es lineal y
				// entra todo el archivo.
				val phraseKeys = lower.keys.filter { isPhrase(it) }
				byLower = if (aliases.isEmpty()) lower else LinkedHashMap(lower).also { it.putAll(aliases) }
				byExact = exact
				hasWords = lower.keys.any { k -> k.all { c -> c.isLetterOrDigit() || c == '_' } }
				buckets = buildBuckets(phraseKeys)
				entries = want
				reset = resetCode
			} else if (resetCode != reset) {
				reset = resetCode
			}
			if (buckets.isEmpty() && !hasWords) return null
			return Hit(buckets, byLower, byExact, hasWords, reset)
		}

		/** Una clave con algo que no sea letra, dígito o guion
		 *  bajo es una frase: la que el camino por tokens no
		 *  puede resolver ("10:00 a. m.", "km/h", ".gmail.com"). */
		private fun isPhrase(folded: String): Boolean =
			folded.any { c -> !c.isLetterOrDigit() && c != '_' }

		/**
		 * Los cubos, del largo mayor al menor, para que la frase más
		 * larga se compruebe antes y gane donde dos casan.
		 *
		 * Una clave más larga que [MAX_PHRASE] no entra en patrón
		 * (sigue enseñándose al motor y sigue en el mapa): es un
		 * diálogo entero metido como clave, y un patrón de esa
		 * anchura no se compila en tiempo razonable. El largo
		 * de una ventana es [TextPieces.WINDOW_CHARS], así que
		 * por encima de eso tampoco habría dónde casarla.
		 *
		 * Si hay más de [MAX_BUCKETS] largos distintos (un archivo
		 * con miles de claves de frase de tamaños diferentes), los
		 * cortos se juntan en un cubo solo: se pierde el
		 * orden por largo entre ellos, pero el "la más larga
		 * gana" de [substitutePhrases] sigue decidiendo por
		 * posición, así que el resultado no cambia.
		 */
		private fun buildBuckets(keys: List<String>): List<PhraseBucket> {
			if (keys.isEmpty()) return emptyList()
			val usable = keys.filter { it.length in 1..MAX_PHRASE }
			if (usable.isEmpty()) return emptyList()
			val byLength = LinkedHashMap<Int, MutableList<String>>()
			for (k in usable) byLength.getOrPut(k.length) { ArrayList() }.add(k)
			val lengths = byLength.keys.sortedDescending()
			val out = ArrayList<PhraseBucket>(lengths.size)
			val keep = if (lengths.size <= MAX_BUCKETS) lengths else lengths.take(MAX_BUCKETS)
			for (len in keep) {
				val group = byLength[len] ?: continue
				out.add(bucket(len, group))
			}
			// Los que se quedan fuera, de cualquier forma.
			val dropped = if (lengths.size <= MAX_BUCKETS) {
				emptyList()
			} else {
				lengths.drop(MAX_BUCKETS).flatMap { byLength[it].orEmpty() }
			}
			if (dropped.isNotEmpty()) out.add(bucket(0, dropped))
			return out
		}

		private fun bucket(length: Int, keys: List<String>): PhraseBucket {
			val body = keys.joinToString("|") { flexibleFragment(it) }
			val regex = Regex(
				"(?<![\\p{L}\\p{N}_])(?:$body)(?![\\p{L}\\p{N}_])",
				RegexOption.IGNORE_CASE
			)
			return PhraseBucket(length, shortestOf(keys), firstsOf(keys), regex)
		}

		/** El largo más corto que puede casar un cubo: el de la
		 *  clave con menos blancos opcionales. Un blanco es
		 *  opcional detrás de un punto, coma o apóstrofo (ver
		 *  [flexibleFragment]). */
		private fun shortestOf(keys: List<String>): Int =
			keys.minOf { key ->
				var short = key.length
				for (at in key.indices) {
					if (key[at] != ' ') continue
					val before = if (at > 0) key[at - 1] else ' '
					if (before in ABBREV_BEFORE) short--
				}
				short
			}

		/** Con qué caracteres puede empezar una clave de este cubo,
		 *  en minúsculas y con lo que esté fuera de ASCII dizendo
		 *  "no se filtra" (nulo). */
		private fun firstsOf(keys: List<String>): BooleanArray? {
			val out = BooleanArray(256)
			var any = false
			for (k in keys) {
				val c = k.first().lowercaseChar()
				if (c.code > 255) return null
				out[c.code] = true
				any = true
			}
			return if (any) out else null
		}
	}

	/** Enseña una entrada tal cual, una sola forma. Rápida para
	 *  archivos grandes: la sustitución en texto ya cubre las
	 *  mayúsculas, así que el motor no necesita las cuatro formas.
	 *  Mayúsculas estrictas y números puros no se enseñan: el motor
	 *  compara sin caja y rompería "esa" y "1:00"; la sustitución
	 *  los lleva con protección. Contesta si entró (o si se dejó a la
	 *  sustitución). */
	fun teachSingle(engine: EvvEngine, volume: Int, key: String, say: String): Boolean {
		if (key.isEmpty()) return false
		if (skipTeach(key)) return true
		return engine.teachWord(volume, normWord(key.trim()), say) == 0
	}

	/** Enseña una entrada en todas sus formas de mayúsculas
	 *  (original, minúsculas, MAYÚSCULAS y Capitalizada) para que
	 *  el reemplazo no dependa de cómo venga escrita la palabra.
	 *  Una oración (con espacios) entra una sola vez en su forma
	 *  exacta: cuatro copias de una frase larga no aportan nada y
	 *  la sustitución en texto ya ignora la caja. Contesta si al
	 *  menos una entró. */
	fun teachEntry(engine: EvvEngine, volume: Int, key: String, say: String): Boolean {
		if (key.any { it.isWhitespace() }) {
			val word = normWord(key)
			return word.isNotEmpty() && engine.teachWord(volume, word, say) == 0
		}
		val forms = linkedSetOf(
			key,
			key.lowercase(),
			key.uppercase(),
			key.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
		)
		var ok = false
		for (form in forms) {
			if (form.isNotEmpty() && engine.teachWord(volume, form, say) == 0) ok = true
		}
		return ok
	}

	/** Cuántas entradas ofrece un archivo, para decirlo en la pantalla
	 *  de ajustes sin un motor a la mano. */
	fun count(file: File): Int = entries(file).size

	/** Todas las entradas de [archivo] como pares
	 *  palabra-pronunciación, en el orden del archivo. Un .cfd se
	 *  lee con su forma propia primero y lo demás después; un
	 *  .reloqdic al revés, para que una línea .cfd pegada en él
	 *  también entre. La palabra sale con sus blancos reducidos
	 *  ([normWord]), que es lo que la hace comparable con el texto
	 *  y lo que se enseña al motor. */
	fun entries(file: File): List<Pair<String, String>> {
		val lines = readLines(file)
		return if (file.extension.equals("cfd", ignoreCase = true)) {
			lines.mapNotNull { parseCfdLine(it)?.let { e -> normWord(e.word) to e.say } ?: parseLine(it) }
		} else {
			lines.mapNotNull { parseLine(it) ?: parseCfdLine(it)?.let { e -> normWord(e.word) to e.say } }
		}
	}

	/** Todas las entradas .cfd de [archivo] con sus campos propios,
	 *  para escribirlas de vuelta tal como vinieron. Lo que no es
	 *  .cfd entra con los campos de origen. */
	fun cfdEntries(file: File): List<CfdEntry> =
		readLines(file).mapNotNull { parseCfdLine(it) ?: parseLine(it)?.let { (k, s) -> CfdEntry(k, s) } }

	/** Una entrada .cfd: palabra, pronunciación y los campos que
	 *  Eloquence guarda junto a ellas. */
	data class CfdEntry(
		val word: String,
		val say: String,
		val flags: String = "*",
		val extra: String = "*",
		val star: String = "*",
		val num1: String = "0",
		val num2: String = "0"
	)

	/** Tamaños sanos para una entrada: caben oraciones larguísimas
	 *  (miles de palabras si hace falta). El motor y el puente no
	 *  tienen tope propio (reservan según lo que llega) y la
	 *  sustitución en texto casa la oración entera antes que cada
	 *  palabra suelta, así que una palabra se oye igual sola que
	 *  dentro de su oración. Fuera de esto es basura de formato. */
	const val MAX_KEY = 20000
	const val MAX_SAY = 20000

	/** Tope de seguridad de cubos por largo. Un archivo con más de
	 *  estos largos distintos de clave de frase junta los cortos en
	 *  uno; con 4300 entradas de un diccionario real son unas
	 *  sesenta, así que el tope no se toca nunca en la práctica. */
	const val MAX_BUCKETS = 256

	/** Una clave de frase más larga que esto no entra en patrón.
	 *  [TextPieces.WINDOW_CHARS] es el texto más grande que se
	 *  procesa de una vez, así que por encima tampoco habría dónde
	 *  casarla. */
	const val MAX_PHRASE = 20000

	/** Cuántas frases se sustituyen en una ventana antes de dejar
	 *  el resto como está: un corte de seguridad para un archivo
	 *  deforme, no un límite de verdad. */
	const val MAX_PHRASES = 4000

	/** Una línea .cfd como entrada, o nada si no tiene la forma
	 *  propia: delimitador de puntuación al principio y al final,
	 *  nueve campos al partir por él (vacío, siete con contenido,
	 *  vacío), palabra y pronunciación no vacías. Un "*" como
	 *  pronunciación es no decir nada y tampoco entra. */
	fun parseCfdLine(line: String): CfdEntry? {
		val clean = line.trim()
		if (clean.length < 2) return null
		val d = clean[0]
		// El delimitador es puntuación, nunca el principio de una
		// palabra: así una línea tabulada normal no cuela aquí.
		if (d.isLetterOrDigit() || d.isWhitespace()) return null
		if (clean[clean.length - 1] != d) return null
		val parts = clean.split(d)
		if (parts.size != 9 || parts[0].isNotEmpty() || parts[8].isNotEmpty()) return null
		val word = parts[1]
		val say = parts[2]
		if (word.isEmpty() || say.isEmpty() || say == "*") return null
		if (word.length > MAX_KEY || say.length > MAX_SAY) return null
		return CfdEntry(word, say, parts[3], parts[4], parts[5], parts[6], parts[7])
	}

	/** El delimitador .cfd para [word] a [say]: el primero de
	 *  punto, coma y admiración que no aparezca en ninguno de los
	 *  dos, que es como Eloquence lo elige. */
	fun cfdDelimFor(word: String, say: String): Char =
		charArrayOf('.', ',', '!').firstOrNull { it !in word && it !in say } ?: '.'

	/** Una entrada como línea .cfd, con su delimitador propio. */
	fun cfdLineOf(e: CfdEntry): String {
		val d = cfdDelimFor(e.word, e.say)
		return "$d${e.word}$d${e.say}$d${e.flags}$d${e.extra}$d${e.star}$d${e.num1}$d${e.num2}$d"
	}

	/** Escribe [entries] en [salida] como .cfd: BOM UTF-16BE y CRLF,
	 *  como los originales. Una entrada que trae los tres
	 *  delimitadores (. , !) no tiene línea que la represente y se
	 *  salta: una línea rota rompería la reimportación entera, y en
	 *  la app la entrada se conserva. Contesta si tuvo éxito. */
	fun writeCfdTo(salida: java.io.OutputStream, entries: List<CfdEntry>): Boolean = runCatching {
		salida.write(0xFE)
		salida.write(0xFF)
		salida.bufferedWriter(Charsets.UTF_16BE).use { out ->
			for (e in entries) {
				val d = cfdDelimFor(e.word, e.say)
				if (d in e.word || d in e.say) {
					Timber.w("entrada sin delimitador .cfd, se salta al exportar: ${e.word.take(40)}")
					continue
				}
				out.write(cfdLineOf(e))
				out.write("\r\n")
			}
		}
		true
	}.getOrDefault(false)

	/** Escribe [entries] en [salida] como .reloqdic: cabecera y
	 *  cuerpo tabulado en Latin-1, que es el juego del motor.
	 *  Contesta si tuvo éxito. */
	fun writeReloqdicTo(salida: java.io.OutputStream, entries: List<Pair<String, String>>): Boolean = runCatching {
		salida.bufferedWriter(Charsets.ISO_8859_1).use { out ->
			out.write("# reloqdic v1")
			out.newLine()
			for ((key, say) in entries) {
				out.write("$key\t$say")
				out.newLine()
			}
		}
		true
	}.getOrDefault(false)

	/** Escribe [entries] en [archivo] en el formato pedido:
	 *  "cfd" (forma propia de Eloquence) o "reloqdic" (cabecera y
	 *  tabulado). Contesta si tuvo éxito. */
	fun writeEntries(file: File, entries: List<Pair<String, String>>, format: String): Boolean =
		if (format == "cfd") writeCfd(file, entries.map { (k, s) -> CfdEntry(k, s) })
		else runCatching {
			file.outputStream().use { writeReloqdicTo(it, entries) }
			true
		}.getOrDefault(false)

	/** Escribe [entries] en [archivo] como .cfd. Contesta si tuvo
	 *  éxito. */
	fun writeCfd(file: File, entries: List<CfdEntry>): Boolean = runCatching {
		file.outputStream().use { writeCfdTo(it, entries) }
		true
	}.getOrDefault(false)

	/**
	 * 4.2: escribe en [file] el .cfd que resulta de dejar una sola
	 * entrada por palabra, con la pronunciación que quedó en [say].
	 *
	 * Los campos propios de cada línea (flags, origen, los dos
	 * números) se conservan tomándolos de [original], que es el
	 * archivo tal como entró: por eso el resultado sigue siendo un
	 * .cfd de Eloquence y no un .cfd rehecho. El orden es el del
	 * original, y las palabras que solo viven en [say] (editadas a
	 * mano) van al final, en orden alfabético.
	 *
	 * Esto es lo que hace que resolver las repetidas una a una
	 * sirva de algo: antes se decidía y luego se movía el archivo
	 * viejo tal cual, así que la decisión se quedaba en el aire y
	 * lo que se compartía después salía con las repetidas puestas.
	 */
	fun writeCfdResolved(
		file: File,
		original: List<CfdEntry>,
		say: Map<String, String>
	): Boolean {
		val out = ArrayList<CfdEntry>(say.size)
		val seen = HashSet<String>()
		for (e in original) {
			val key = normWord(e.word)
			val chosen = say[key] ?: continue
			if (!seen.add(key)) continue
			out.add(e.copy(word = key, say = chosen))
		}
		for (key in say.keys.sorted()) {
			if (seen.add(key)) out.add(CfdEntry(key, say.getValue(key)))
		}
		return writeCfd(file, out)
	}

	/** El formato que [archivo] dice ser por su extensión,
	 *  y por su contenido cuando no dice nada: "reloqdic"
	 *  (cabecera y tabulado) o "cfd" (forma propia de Eloquence,
	 *  como ELOQMAIN.CFD: UTF-16 con BOM, ocho campos por
	 *  línea con punto, coma o admiración). */
	fun formatOf(file: File): String {
		if (file.extension.equals("reloqdic", ignoreCase = true)) return "reloqdic"
		if (file.extension.equals("cfd", ignoreCase = true)) return "cfd"
		return sniffFormat(file)
	}

	/** Adivina el formato por el contenido: basta una línea .cfd
	 *  para decir "cfd". Los originales de Eloquence (.CFD en
	 *  mayúsculas, .txt renombrados, sin extensión) entran así. */
	fun sniffFormat(file: File): String {
		var checked = 0
		for (line in readLines(file)) {
			val clean = line.trim()
			if (clean.isEmpty()) continue
			if (clean.startsWith("# reloqdic")) return "reloqdic"
			if (parseCfdLine(line) != null) return "cfd"
			if (++checked >= 50) break
		}
		return "reloqdic"
	}

	/** Cambia la entrada con clave [oldKey] por [newKey] a [newSay].
	 *  En un .cfd los campos propios se conservan.
	 *  Contesta si la encontró. */
	fun updateEntry(file: File, oldKey: String, newKey: String, newSay: String): Boolean {
		if (formatOf(file) == "cfd") {
			val current = cfdEntries(file)
			if (current.none { it.word == oldKey }) return false
			val updated = current.map { if (it.word == oldKey) it.copy(word = newKey, say = newSay) else it }
			return writeCfd(file, updated)
		}
		val current = entries(file)
		if (current.none { it.first == oldKey }) return false
		val updated = current.map { if (it.first == oldKey) newKey to newSay else it }
		return writeEntries(file, updated, formatOf(file))
	}

	/** Quita la entrada con clave [key]. Contesta si la encontró. */
	fun removeEntry(file: File, key: String): Boolean {
		if (formatOf(file) == "cfd") {
			val current = cfdEntries(file)
			if (current.none { it.word == key }) return false
			return writeCfd(file, current.filter { it.word != key })
		}
		val current = entries(file)
		if (current.none { it.first == key }) return false
		val updated = current.filter { it.first != key }
		return writeEntries(file, updated, formatOf(file))
	}

	/** Una línea tabulada como par clave-pronunciación, o nada si
	 *  la línea es un comentario, una sección, está vacía o no
	 *  tiene forma de entrada. Es el cuerpo del .reloqdic:
	 *  "palabra\tsay", y además "palabra=say", "palabra=>say",
	 *  dos puntos ("palabra:say") y comillas alrededor de la
	 *  clave. Las líneas .cfd no entran aquí: las lee
	 *  [parseCfdLine].
	 *  Para que entre lo más limpio posible, la clave tiene que
	 *  llevar al menos una letra: así no entran números sueltos,
	 *  restos de numeración ni basura de formato. */
	fun parseLine(line: String): Pair<String, String>? {
		var clean = line.trim()
		if (clean.isEmpty()) return null
		if (clean.startsWith(";") || clean.startsWith("#")) return null
		if (clean.startsWith("//")) return null
		if (clean.startsWith("[") && clean.endsWith("]")) return null
		// Fuera marcadores de lista: "12.", "12)", "-", "*", "•".
		clean = clean.replaceFirst(Regex("""^(\d+[.)]|[-*•])\s+"""), "")
		if (clean.isEmpty()) return null
		// Separadores por orden de preferencia. Los dos puntos solo
		// cuando no hay otro separador y hay uno solo, para no
		// romper horas como 2:30.
		val tab = clean.indexOf('\t')
		if (tab > 0) return splitPair(clean.substring(0, tab), clean.substring(tab + 1))
		val arrow = clean.indexOf("=>")
		if (arrow > 0) return splitPair(clean.substring(0, arrow), clean.substring(arrow + 2))
		val eq = clean.indexOf('=')
		if (eq > 0) return splitPair(clean.substring(0, eq), clean.substring(eq + 1))
		if (clean.count { it == ':' } == 1) {
			val at = clean.indexOf(':')
			if (at > 0) return splitPair(clean.substring(0, at), clean.substring(at + 1))
		}
		return null
	}

	private fun splitPair(rawKey: String, rawSay: String): Pair<String, String>? {
		val key = normWord(rawKey.trim().trim('"', '\''))
		var say = rawSay.trim().trim('"', '\'')
		if (key.isEmpty() || say.isEmpty()) return null
		if (key.length > MAX_KEY || say.length > MAX_SAY) return null
		// La clave tiene que tener al menos una letra.
		if (!key.any { it.isLetter() }) return null
		// Fuera comentarios al final de la pronunciación.
		say = say.split(Regex("""\s+([;#]|//)"""), limit = 2)[0].trim()
		if (say.isEmpty()) return null
		return key to say
	}

	/** Pares casi repetidos (parecidos sin ser iguales), para
	 *  avisar al importar sin borrar nada solo: "año/ano" no se
	 *  fusiona. 4.0: Jaro-Winkler de Apache Commons Text, por
	 *  cubetas (2 primeras letras + largo) para no comparar
	 *  millones de pares en diccionarios grandes. */
	fun nearDuplicates(entries: List<Pair<String, String>>, maxPairs: Int = 6): List<Pair<String, String>> {
		if (entries.size < 2) return emptyList()
		val sim = org.apache.commons.text.similarity.JaroWinklerSimilarity()
		val norm = entries.map { it.first.trim().lowercase(java.util.Locale.ROOT) }
		val buckets = LinkedHashMap<String, MutableList<Int>>()
		for (i in norm.indices) {
			val k = norm[i]
			if (k.length < 3) continue
			val bucket = k.take(2) + (k.length / 3)
			buckets.getOrPut(bucket) { ArrayList() }.add(i)
		}
		val out = ArrayList<Pair<String, String>>()
		var compared = 0
		for (bucket in buckets.values) {
			if (bucket.size < 2) continue
			for (a in bucket.indices) {
				for (b in a + 1 until bucket.size) {
					if (++compared > 200000) return out
					val ka = norm[bucket[a]]
					val kb = norm[bucket[b]]
					if (ka == kb || kotlin.math.abs(ka.length - kb.length) > 2) continue
					val score = runCatching { sim.apply(ka, kb) }.getOrNull() ?: continue
					if (score >= 0.88) {
						out.add(entries[bucket[a]].first to entries[bucket[b]].first)
						if (out.size >= maxPairs) return out
					}
				}
			}
		}
		return out
	}
}
