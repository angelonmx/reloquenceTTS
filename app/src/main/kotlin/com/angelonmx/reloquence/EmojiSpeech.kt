package com.angelonmx.reloquence

/** Emojis dichos en voz alta: cada uno por su nombre en el
 *  idioma de la emisión, en todos los idiomas del motor
 *  (español, inglés, francés, alemán, italiano, chino, portugués,
 *  japonés, finés, coreano, cantonés, neerlandés, noruego, sueco,
 *  danés y polaco).
 *
 *  El motor no conoce emojis y WesternText los vuelve espacios,
 *  así que sin esto se traga cada uno en silencio. Con el
 *  interruptor apagado sigue así (los lee el lector si sabe) y
 *  con él encendido cada emoji común entra como palabras.
 *
 *  Secuencias: el tono de piel se ignora, el pegamento de ancho
 *  cero separa en palabras, la variación se tira, la tecla
 *  (1 + adorno) deja su base y la bandera sale con su país.
 *
 *  Cobertura total sin dependencias, en tres capas: lo común va
 *  con nombre natural en ocho idiomas; cada bandera dice su país
 *  en los dieciséis; y el resto sale del nombre oficial Unicode
 *  ("woman technologist", "mate"), traducido por palabras a diez
 *  idiomas (en chino, japonés, coreano y cantonés, sin espacios
 *  donde cortar, queda el inglés, que al menos se oye).
 *  Nada se queda en silencio por raro que sea.
 */
object EmojiSpeech {

	/** [text] con cada emoji común por su nombre. [lang] es la
	 *  etiqueta ISO-3 del motor ("spa", "eng", "fra"...). Sin
	 *  emojis vuelve lo mismo. */
	fun describeLang(text: String, lang: String = "spa"): String {
		val normalized = normalizeLang(lang)
		if (text.isEmpty()) return text
		var out: StringBuilder? = null
		var i = 0
		while (i < text.length) {
			val cp = text.codePointAt(i)
			val size = Character.charCount(cp)
			// 5.0: CLDR primero, y por secuencias enteras. Así una
			// familia con pegamento o una tecla se dicen una vez y no
			// pieza por pieza. Las banderas siguen por su camino, que
			// arma el nombre con el país en los dieciséis idiomas.
			val cldr = if (!isEmojiRange(cp) || cp == 0xFE0F || cp == 0xFE0E ||
				cp == 0x20E3 || cp in 0x1F3FB..0x1F3FF || cp == 0x200D ||
				cp in 0x1F1E6..0x1F1FF || cp == 0x1F3F4
			) null else CldrData.emojiAt(text, i, normalized)
			if (cldr != null) {
				if (out == null) out = StringBuilder(text.length + 32).append(text, 0, i)
				out.append(' ').append(cldr.name).append(' ')
				i = cldr.end
				continue
			}
			when {
				cp == 0xFE0F || cp == 0xFE0E || cp == 0x20E3 -> {
					// Variación y adorno de tecla: fuera.
					if (out == null) out = StringBuilder(text.length + 32).append(text, 0, i)
				}
				cp in 0x1F3FB..0x1F3FF -> {
					// Tono de piel: fuera.
					if (out == null) out = StringBuilder(text.length + 32).append(text, 0, i)
				}
				cp == 0x200D -> {
					// Pegamento: separa en palabras.
					if (out == null) out = StringBuilder(text.length + 32).append(text, 0, i)
					out.append(' ')
				}
				cp in 0x1F1E6..0x1F1FF -> {
					// Bandera: dos letras regionales, un nombre con país.
					if (out == null) out = StringBuilder(text.length + 32).append(text, 0, i)
					var code = regionalLetter(cp)
					val next = if (i + size < text.length) text.codePointAt(i + size) else -1
					if (next in 0x1F1E6..0x1F1FF) {
						code += regionalLetter(next)
						i += Character.charCount(next)
					}
					out.append(flagLangName(code, normalized))
				}
				cp == 0x1F3F4 -> {
					// Bandera con etiquetas (Inglaterra, Escocia, Gales).
					if (out == null) out = StringBuilder(text.length + 32).append(text, 0, i)
					var j = i + size
					val tag = StringBuilder()
					while (j < text.length) {
						val t = text.codePointAt(j)
						val ts = Character.charCount(t)
						if (t == 0xE007F) {
							j += ts
							break
						}
						if (t !in 0xE0020..0xE007E) break
						if (t in 0xE0061..0xE007A) tag.append((t - 0xE0061 + 'a'.code).toChar())
						j += ts
					}
					i = j - size
					val nation = NATIONS[tag.toString()]?.let { table ->
						table[normalized] ?: table["cmn"]?.takeIf { normalized == "yue" }
						?: table["eng"]
					}
					out.append(
						if (nation != null) {
							if (flagSuffix(normalized)) {
								val word = FLAG_WORD[normalized] ?: "flag"
								if (normalized == "kor") " $nation $word " else " $nation$word "
							} else {
								val lead = FLAG_LEAD[normalized] ?: FLAG_LEAD["eng"]!!
								" $lead $nation "
							}
						} else {
							" ${FLAG_WORD[normalized] ?: "flag"} "
						}
					)
				}
				else -> {
					val name = emojiName(cp, normalized)
					if (name == null) {
						out?.append(text, i, i + size)
					} else {
						if (out == null) out = StringBuilder(text.length + 32).append(text, 0, i)
						out.append(' ').append(name).append(' ')
					}
				}
			}
			i += size
		}
		val built = out ?: return text
		return SPACES.replace(built, " ")
	}

	private val SPACES = Regex(" {2,}")
	private val TRAILING_KIND = Regex("\\s+(sign|symbol)$")

	/** Compatibilidad: [spanish] elige español o inglés. */
	fun describe(text: String, spanish: Boolean): String =
		describeLang(text, if (spanish) "spa" else "eng")

	private fun normalizeLang(lang: String): String = when (lang) {
		"spa", "es" -> "spa"
		"eng", "en" -> "eng"
		"fra", "fr" -> "fra"
		"deu", "de" -> "deu"
		"ita", "it" -> "ita"
		"cmn", "zh" -> "cmn"
		"yue" -> "yue"
		"por", "pt" -> "por"
		"jpn", "ja" -> "jpn"
		"fin", "fi" -> "fin"
		"kor", "ko" -> "kor"
		"nld", "nl" -> "nld"
		"nor", "nb", "nn", "no" -> "nor"
		"swe", "sv" -> "swe"
		"dan", "da" -> "dan"
		"pol", "pl" -> "pol"
		"tur", "tr" -> "tur"
		"rus", "ru" -> "rus"
		else -> "eng"
	}

	private fun emojiName(cp: Int, lang: String): String? {
		val table = OTHER_NAMES[cp]
		if (table != null) {
			table[lang]?.let { return it }
			// El cantonés escrito comparte lo esencial con el
			// mandarín: antes que inglés, chino.
			if (lang == "yue") table["cmn"]?.let { return it }
			if (lang == "eng") table["eng"]?.let { return it }
		}
		val pair = NAMES[cp]
		if (pair != null) {
			return when (lang) {
				"spa" -> pair.first
				"eng" -> pair.second
				else -> translateWords(pair.second, lang)
			}
		}
		return derivedName(cp, lang)
	}

	private fun regionalLetter(cp: Int): String =
		(cp - 0x1F1E6 + 'A'.code).toChar().toString()

	private val FLAG_LEAD = mapOf(
		"spa" to "bandera de", "eng" to "flag of", "fra" to "drapeau de",
		"deu" to "Flagge von", "ita" to "bandiera di", "jpn" to "の旗",
		"pol" to "flaga", "por" to "bandeira de", "cmn" to "国旗",
		"yue" to "國旗", "kor" to "국기", "fin" to "lippu",
		"nld" to "vlag van", "nor" to "flagg", "swe" to "flagga",
		"dan" to "flag"
	)

	/** La palabra "bandera" sola, para la bandera desconocida. */
	private val FLAG_WORD = mapOf(
		"spa" to "bandera", "eng" to "flag", "fra" to "drapeau",
		"deu" to "Flagge", "ita" to "bandiera", "por" to "bandeira",
		"pol" to "flaga", "nld" to "vlag", "swe" to "flagga",
		"nor" to "flagg", "dan" to "flag", "fin" to "lippu",
		"jpn" to "旗", "cmn" to "国旗", "yue" to "國旗", "kor" to "국기"
	)

	/** Idiomas donde el país va delante ("Japón" + "の旗"). */
	private fun flagSuffix(lang: String): Boolean = when (lang) {
		"jpn", "cmn", "yue", "kor", "fin", "swe", "nor", "dan" -> true
		else -> false
	}

	private fun flagName(code: String, spanish: Boolean): String =
		flagLangName(code, if (spanish) "spa" else "eng")

	private fun flagLangName(code: String, lang: String): String {
		val name = OTHER_FLAGS[code]?.get(lang)
			?: OTHER_FLAGS[code]?.get("cmn")?.takeIf { lang == "yue" }
			?: FLAGS[code]?.let { if (lang == "spa") it.first else it.second }
		val word = FLAG_WORD[lang] ?: "flag"
		if (name != null) {
			return if (flagSuffix(lang)) {
				if (lang == "kor") " $name $word " else " $name$word "
			} else {
				val lead = FLAG_LEAD[lang] ?: "flag of"
				" $lead $name "
			}
		}
		return " $word "
	}

	/**
	 * 4.4: lo derivado del nombre oficial Unicode ("woman
	 * technologist", "mate"): en inglés tal cual limpio y en los demás
	 * idiomas con traducción por palabras (en español, además, "cara"
	 * delante si termina en face). Fuera de los rangos de emoji vuelve
	 * nada. Lo ya visto se recuerda, porque el texto repite emojis.
	 *
	 * El recuerdo era un `HashMap` y lo tocan dos hilos.
	 * [describeLang] se llama desde el hilo de síntesis, en cada
	 * emisión, y desde la pantalla de ajustes, en el principal, para
	 * la vista previa. Los dos meten claves al mismo tiempo, y un
	 * `HashMap` al que se le cambia la estructura desde dos hilos no
	 * lanza nada: se queda en bucle sobre una tabla que el otro está
	 * moviendo, y el hilo se queda ahí para siempre. Sin excepción que
	 * lo delate, que es la forma más difícil de depurar.
	 *
	 * Un `ConcurrentHashMap` resuelve el acceso. Lo que no admite es un
	 * valor nulo, así que "no hay nombre" se guarda como cadena vacía,
	 * que es lo que ya se hacía, y [derivedName] lo devuelve a nulo al
	 * leer.
	 */
	private val derivedCache = java.util.concurrent.ConcurrentHashMap<String, String>()

	private fun derivedName(cp: Int, lang: String): String? {
		if (!isEmojiRange(cp)) return null
		// Espacios y controles con nombre (U+3000) no se dicen.
		if (Character.isWhitespace(cp) || Character.isISOControl(cp)) return null
		val key = "$cp|$lang"
		derivedCache[key]?.let { return it.ifEmpty { null } }
		val raw = runCatching { Character.getName(cp) }.getOrNull()
			?.lowercase(java.util.Locale.ROOT)
			?.replace(TRAILING_KIND, "")
			?.replace('_', ' ')?.trim()
		val out = if (raw.isNullOrEmpty()) null
		else if (lang == "spa") spanishName(raw)
		else translateWords(raw, lang)
		derivedCache[key] = out ?: ""
		return out
	}

	private fun isEmojiRange(cp: Int): Boolean = cp in 0x20A0..0x20CF ||
		cp in 0x2100..0x214F || cp in 0x2190..0x21FF ||
		cp in 0x2300..0x23FF || cp in 0x2460..0x24FF ||
		cp in 0x25A0..0x25FF || cp in 0x2600..0x27BF ||
		cp in 0x2900..0x297F || cp in 0x2B00..0x2BFF ||
		cp in 0x3000..0x303F || cp in 0x3200..0x32FF ||
		cp in 0x1F000..0x1F0FF || cp in 0x1F100..0x1F2FF ||
		cp in 0x1F300..0x1F6FF || cp in 0x1F800..0x1F8FF ||
		cp in 0x1F900..0x1F9FF || cp in 0x1FA00..0x1FAFF

	private fun spanishName(en: String): String {
		val words = en.split(' ')
		val body = if (words.size > 1 && words.last() == "face") words.dropLast(1) else words
		val prefix = if (body.size != words.size) "cara " else ""
		return prefix + body.map { ES_WORDS[it] ?: it }.joinToString(" ")
	}

	/** Traduce un nombre inglés palabra por palabra con la tabla
	 *  de [lang]. Sin tabla (chino, japonés, coreano, cantonés)
	 *  vuelve el inglés: al menos se oye. */
	private fun translateWords(en: String, lang: String): String {
		val table = if (lang == "spa") ES_WORDS else WORDS[lang] ?: return en
		if (table.isEmpty()) return en
		return en.split(' ').map { table[it] ?: it }.joinToString(" ")
	}

	private val NAMES: Map<Int, Pair<String, String>> = mapOf(
		0x1F600 to ("cara sonriente" to "grinning face"),
		0x1F601 to ("cara sonriente con ojos" to "beaming face"),
		0x1F602 to ("cara llorando de risa" to "face with tears of joy"),
		0x1F923 to ("cara revolcándose de risa" to "rolling on the floor laughing"),
		0x1F60A to ("cara sonrojada" to "smiling face with smiling eyes"),
		0x1F60D to ("cara con ojos de corazón" to "smiling face with heart-eyes"),
		0x1F618 to ("cara lanzando un beso" to "face blowing a kiss"),
		0x1F60E to ("cara con gafas de sol" to "smiling face with sunglasses"),
		0x1F973 to ("cara de fiesta" to "partying face"),
		0x1F62D to ("cara llorando fuerte" to "loudly crying face"),
		0x1F622 to ("cara llorando" to "crying face"),
		0x1F621 to ("cara enojada" to "pouting face"),
		0x1F914 to ("cara pensativa" to "thinking face"),
		0x1F644 to ("cara con ojos en blanco" to "face with rolling eyes"),
		0x1F634 to ("cara dormida" to "sleeping face"),
		0x1F92F to ("cabeza explotando" to "exploding head"),
		0x1F976 to ("cara con frío" to "cold face"),
		0x1F975 to ("cara con calor" to "hot face"),
		0x1F920 to ("cara de vaquero" to "cowboy hat face"),
		0x1F47B to ("fantasma" to "ghost"),
		0x1F480 to ("calavera" to "skull"),
		0x1F916 to ("robot" to "robot"),
		0x1F63A to ("gato sonriente" to "grinning cat"),
		0x1F648 to ("mono que no ve" to "see-no-evil monkey"),
		0x1F44D to ("pulgar arriba" to "thumbs up"),
		0x1F44E to ("pulgar abajo" to "thumbs down"),
		0x1F44F to ("aplausos" to "clapping hands"),
		0x1F64F to ("manos en oración" to "folded hands"),
		0x270C to ("victoria" to "victory hand"),
		0x1F91D to ("apretón de manos" to "handshake"),
		0x1F44C to ("gesto de OK" to "OK hand"),
		0x270A to ("puño" to "raised fist"),
		0x1F44B to ("mano saludando" to "waving hand"),
		0x1F44A to ("puño golpeando" to "oncoming fist"),
		0x1F4AA to ("músculo" to "flexed biceps"),
		0x1F590 to ("mano abierta" to "raised hand with fingers splayed"),
		0x270B to ("mano" to "raised hand"),
		0x1F446 to ("apuntando arriba" to "backhand index pointing up"),
		0x1F447 to ("apuntando abajo" to "backhand index pointing down"),
		0x2764 to ("corazón" to "red heart"),
		0x1F494 to ("corazón roto" to "broken heart"),
		0x1F495 to ("dos corazones" to "two hearts"),
		0x1F496 to ("corazón brillante" to "sparkling heart"),
		0x1F499 to ("corazón azul" to "blue heart"),
		0x1F49A to ("corazón verde" to "green heart"),
		0x1F49B to ("corazón amarillo" to "yellow heart"),
		0x1F9E1 to ("corazón naranja" to "orange heart"),
		0x1F49C to ("corazón morado" to "purple heart"),
		0x1F5A4 to ("corazón negro" to "black heart"),
		0x1F90D to ("corazón blanco" to "white heart"),
		0x2B50 to ("estrella" to "star"),
		0x1F31F to ("estrella brillante" to "glowing star"),
		0x1F525 to ("fuego" to "fire"),
		0x1F389 to ("fiesta" to "party popper"),
		0x1F382 to ("pastel" to "birthday cake"),
		0x1F381 to ("regalo" to "wrapped gift"),
		0x26BD to ("fútbol" to "soccer ball"),
		0x2705 to ("palomita" to "check mark button"),
		0x274C to ("cruz" to "cross mark"),
		0x2753 to ("signo de pregunta" to "question mark"),
		0x2757 to ("signo de exclamación" to "exclamation mark"),
		0x1F4A1 to ("foco" to "light bulb"),
		0x1F4F1 to ("teléfono" to "mobile phone"),
		0x1F4BB to ("computadora" to "laptop"),
		0x1F697 to ("carro" to "automobile"),
		0x2708 to ("avión" to "airplane"),
		0x1F3E0 to ("casa" to "house"),
		0x1F436 to ("perro" to "dog face"),
		0x1F431 to ("gato" to "cat face"),
		0x1F339 to ("rosa" to "rose"),
		0x1F31E to ("sol" to "sun with face"),
		0x1F319 to ("luna" to "crescent moon"),
		0x2600 to ("sol" to "black sun with rays"),
		0x1F327 to ("lluvia" to "cloud with rain"),
		0x2744 to ("copo de nieve" to "snowflake"),
		0x26A1 to ("rayo" to "high voltage"),
		0x1F3B5 to ("nota musical" to "musical note"),
		0x1F3B6 to ("notas musicales" to "musical notes"),
		0x1F4DE to ("auricular" to "telephone receiver"),
		0x2709 to ("sobre" to "envelope"),
		0x1F4C5 to ("calendario" to "calendar"),
		0x23F0 to ("despertador" to "alarm clock"),
		0x1F4B0 to ("dinero" to "money bag"),
		0x1F511 to ("llave" to "key"),
		0x1F6AB to ("prohibido" to "prohibited"),
		0x267B to ("reciclaje" to "recycling symbol"),
		0x262E to ("paz" to "peace symbol"),
		0x1F64B to ("persona levantando la mano" to "person raising hand"),
		0x1F4AF to ("cien puntos" to "hundred points"),
		0x1F440 to ("ojos" to "eyes"),
		0x1F9E0 to ("cerebro" to "brain"),
		0x1F476 to ("bebé" to "baby"),
		0x1F388 to ("globo" to "balloon"),
		0x2615 to ("café" to "hot beverage"),
		0x1F37A to ("cerveza" to "beer mug"),
		0x1F3C6 to ("trofeo" to "trophy"),
		0x1F947 to ("medalla de oro" to "first place medal"),
		0x1F4A4 to ("dormido" to "zzz"),
		0x1F4A9 to ("caca" to "pile of poo"),
		0x1F451 to ("corona" to "crown"),
		0x1F48D to ("anillo" to "ring"),
		0x1F457 to ("vestido" to "dress"),
		0x1F460 to ("zapato de tacón" to "high-heeled shoe"),
		0x1F3A7 to ("audífonos" to "headphone"),
		0x1F4F7 to ("cámara" to "camera"),
		0x1F3AE to ("videojuego" to "video game"),
		0x1F970 to ("cara sonriente con corazones" to "smiling face with hearts"),
		0x1F97A to ("cara de empollón" to "nerd face"),
		0x1F921 to ("cara de payaso" to "clown face"),
		0x1F47D to ("extraterrestre" to "alien"),
		0x1F332 to ("árbol" to "evergreen tree"),
		0x1F33B to ("girasol" to "sunflower"),
		0x1F3C0 to ("baloncesto" to "basketball"),
		0x1F68C to ("autobús" to "bus"),
		0x1F686 to ("tren" to "train"),
		0x1F6B2 to ("bicicleta" to "bicycle"),
		0x1F3E5 to ("escuela" to "school"),
		0x1F3E8 to ("hotel" to "hotel"),
		0x1F52A to ("cuchillo" to "kitchen knife"),
		0x1F528 to ("martillo" to "hammer"),
		0x1F4DA to ("libros" to "books"),
		0x1F4F0 to ("periódico" to "newspaper"),
		0x1F50D to ("lupa" to "magnifying glass"),
		0x1F4E7 to ("correo" to "e-mail"),
		0x1F4B8 to ("dinero volando" to "money with wings"),
		0x1F6D2 to ("carrito de compras" to "shopping cart"),
		0x23F3 to ("reloj de arena" to "hourglass"),
		0x1F514 to ("campana" to "bell"),
		0x1F308 to ("arcoíris" to "rainbow"),
		0x2602 to ("sombrilla" to "umbrella"),
		0x1F30A to ("ola" to "water wave"),
		0x1F32A to ("nube" to "cloud"),
		0x1F603 to ("cara sonriente con boca abierta" to "grinning face with big eyes"),
		0x1F604 to ("cara sonriente con ojos sonrientes" to "grinning face with smiling eyes"),
		0x1F605 to ("cara sonriente con sudor frío" to "grinning face with sweat"),
		0x1F606 to ("cara riendo entrecerrando los ojos" to "grinning squinting face"),
		0x1F607 to ("cara de ángel" to "smiling face with halo"),
		0x1F608 to ("cara con cuernos" to "smiling face with horns"),
		0x1F609 to ("cara guiñando un ojo" to "winking face"),
		0x1F60B to ("cara saboreando" to "face savoring food"),
		0x1F60F to ("cara con sonrisa torcida" to "smirking face"),
		0x1F610 to ("cara neutral" to "neutral face"),
		0x1F611 to ("cara sin expresión" to "expressionless face"),
		0x1F612 to ("cara de fastidio" to "unamused face"),
		0x1F613 to ("cara con sudor" to "downcast face with sweat"),
		0x1F614 to ("cara pensativa triste" to "pensive face"),
		0x1F615 to ("cara confundida" to "confused face"),
		0x1F617 to ("cara dando un beso" to "kissing face"),
		0x1F619 to ("cara besando con ojos sonrientes" to "kissing face with smiling eyes"),
		0x1F61B to ("cara con lengua fuera" to "face with tongue"),
		0x1F61C to ("cara guiñando con lengua" to "winking face with tongue"),
		0x1F61E to ("cara decepcionada" to "disappointed face"),
		0x1F61F to ("cara preocupada" to "worried face"),
		0x1F620 to ("cara muy enojada" to "angry face"),
		0x1F624 to ("cara echando humo" to "face with steam from nose"),
		0x1F627 to ("cara angustiada" to "anguished face"),
		0x1F628 to ("cara temerosa" to "fearful face"),
		0x1F629 to ("cara agotada" to "weary face"),
		0x1F62A to ("cara con sueño" to "sleepy face"),
		0x1F62B to ("cara cansada" to "tired face"),
		0x1F62C to ("cara haciendo mueca" to "grimacing face"),
		0x1F62E to ("cara boquiabierta" to "face with open mouth"),
		0x1F631 to ("cara gritando" to "face screaming in fear"),
		0x1F632 to ("cara asombrada" to "astonished face"),
		0x1F633 to ("cara sonrojada apenada" to "flushed face"),
		0x1F635 to ("cara mareada" to "knocked-out face"),
		0x1F637 to ("cara con mascarilla" to "face with medical mask"),
		0x1F638 to ("gato sonriendo" to "grinning cat with smiling eyes"),
		0x1F639 to ("gato llorando de risa" to "cat with tears of joy"),
		0x1F63B to ("gato con ojos de corazón" to "smiling cat with heart-eyes"),
		0x1F63F to ("gato gritando" to "weary cat"),
		0x1F641 to ("cara algo triste" to "slightly frowning face"),
		0x1F642 to ("cara levemente sonriente" to "slightly smiling face"),
		0x1F643 to ("cara al revés" to "upside-down face"),
		0x1F64C to ("manos levantadas" to "raising hands"),
		0x1F64D to ("persona triste" to "person frowning"),
		0x1F64E to ("persona enojada" to "person pouting"),
		0x1F645 to ("gesto de no" to "person gesturing no"),
		0x1F646 to ("gesto de sí" to "person gesturing ok"),
		0x1F647 to ("persona haciendo reverencia" to "person bowing"),
		0x1F649 to ("mono que no oye" to "hear-no-evil monkey"),
		0x1F64A to ("mono que no habla" to "speak-no-evil monkey"),
		0x1F595 to ("dedo medio" to "middle finger"),
		0x1F596 to ("saludo vulcano" to "vulcan salute"),
		0x1F918 to ("cuernos del rock" to "sign of the horns"),
		0x1F919 to ("llámame" to "call me hand"),
		0x1F91A to ("dorso de la mano" to "raised back of hand"),
		0x1F91E to ("dedos cruzados" to "crossed fingers"),
		0x1F91F to ("te quiero" to "love-you gesture"),
		0x1F932 to ("apretón de manos" to "handshake"),
		0x1F933 to ("selfi" to "selfie"),
		0x1F442 to ("oído" to "ear"),
		0x1F443 to ("nariz" to "nose"),
		0x1F444 to ("boca" to "mouth"),
		0x1F445 to ("lengua" to "tongue"),
		0x1F493 to ("corazón latiendo" to "beating heart"),
		0x1F498 to ("corazón creciendo" to "growing heart"),
		0x1F49E to ("corazones girando" to "revolving hearts"),
		0x1F49F to ("adorno de corazón" to "heart decoration"),
		0x1F90E to ("corazón marrón" to "brown heart"),
		0x1F435 to ("cara de mono" to "monkey face"),
		0x1F434 to ("caballo" to "horse face"),
		0x1F430 to ("conejo" to "rabbit face"),
		0x1F438 to ("rana" to "frog"),
		0x1F433 to ("ballena" to "spouting whale"),
		0x1F42C to ("delfín" to "dolphin"),
		0x1F419 to ("pulpo" to "octopus"),
		0x1F41D to ("abeja" to "honeybee"),
		0x1F98B to ("mariposa" to "butterfly"),
		0x1F40D to ("serpiente" to "snake"),
		0x1F981 to ("león" to "lion"),
		0x1F42F to ("tigre" to "tiger face"),
		0x1F43A to ("lobo" to "wolf"),
		0x1F43B to ("oso" to "bear"),
		0x1F43C to ("panda" to "panda"),
		0x1F427 to ("pingüino" to "penguin"),
		0x1F426 to ("pájaro" to "bird"),
		0x1F414 to ("pollo" to "chicken"),
		0x1F437 to ("cerdo" to "pig face"),
		0x1F42E to ("vaca" to "cow face"),
		0x1F347 to ("uvas" to "grapes"),
		0x1F349 to ("sandía" to "watermelon"),
		0x1F34A to ("naranja" to "tangerine"),
		0x1F34B to ("limón" to "lemon"),
		0x1F34D to ("piña" to "pineapple"),
		0x1F351 to ("durazno" to "peach"),
		0x1F352 to ("cerezas" to "cherries"),
		0x1F353 to ("fresa" to "strawberry"),
		0x1F35E to ("pan" to "bread"),
		0x1F950 to ("cruasán" to "croissant"),
		0x1F357 to ("pollo asado" to "poultry leg"),
		0x1F359 to ("arroz" to "cooked rice"),
		0x1F35C to ("fideos" to "steaming bowl"),
		0x1F363 to ("sushi" to "sushi"),
		0x1F369 to ("dona" to "doughnut"),
		0x1F36A to ("galleta" to "cookie"),
		0x1F36C to ("dulce" to "candy"),
		0x1F36D to ("paleta" to "lollipop"),
		0x1F370 to ("pastel de fresa" to "shortcake"),
		0x1F375 to ("té" to "teacup without handle"),
		0x1F377 to ("vino" to "wine glass"),
		0x1F378 to ("cóctel" to "cocktail glass"),
		0x1F37B to ("cervezas brindando" to "clinking beer mugs"),
		0x1F37F to ("palomitas" to "popcorn"),
		0x1F35F to ("papas fritas" to "french fries"),
		0x1F32D to ("hot dog" to "hot dog"),
		0x1F32E to ("taco" to "taco"),
		0x1F957 to ("ensalada" to "green salad"),
		0x1F373 to ("cocina" to "cooking"),
		0x1F374 to ("tenedor y cuchillo" to "fork and knife"),
		0x26BE to ("béisbol" to "baseball"),
		0x1F3C8 to ("fútbol americano" to "american football"),
		0x1F3BE to ("tenis" to "tennis"),
		0x1F3D0 to ("voleibol" to "volleyball"),
		0x1F3B1 to ("billar" to "pool 8 ball"),
		0x1F3B3 to ("boliche" to "bowling"),
		0x1F3AF to ("dardos" to "direct hit"),
		0x1F3B2 to ("dado" to "game die"),
		0x1F0CF to ("comodín" to "joker"),
		0x1F3AA to ("circo" to "circus tent"),
		0x1F3AD to ("teatro" to "performing arts"),
		0x1F39F to ("boletos" to "admission tickets"),
		0x1F3C5 to ("medalla deportiva" to "sports medal"),
		0x1F3CA to ("nadador" to "person swimming"),
		0x1F3C4 to ("surf" to "surfing"),
		0x1F6B6 to ("caminando" to "person walking"),
		0x1F3C3 to ("corriendo" to "person running"),
		0x1F483 to ("bailando" to "woman dancing"),
		0x1F46F to ("bailarines" to "dancers"),
		0x1F695 to ("taxi" to "taxi"),
		0x1F691 to ("ambulancia" to "ambulance"),
		0x1F692 to ("bomberos" to "fire engine"),
		0x1F693 to ("patrulla" to "police car"),
		0x1F6A2 to ("barco" to "ship"),
		0x1F680 to ("cohete" to "rocket"),
		0x1F681 to ("helicóptero" to "helicopter"),
		0x26F5 to ("velero" to "sailboat"),
		0x1F3D4 to ("montaña nevada" to "snow-capped mountain"),
		0x1F3D6 to ("playa" to "beach with umbrella"),
		0x1F3D5 to ("campamento" to "camping"),
		0x1F3DE to ("parque" to "national park"),
		0x1F3DF to ("estadio" to "stadium"),
		0x1F3F0 to ("castillo" to "european castle"),
		0x1F3EA to ("tienda" to "convenience store"),
		0x1F3ED to ("fábrica" to "factory"),
		0x1F3DB to ("banco" to "bank"),
		0x1F3E9 to ("hotel del amor" to "love hotel"),
		0x1F508 to ("bocina baja" to "speaker low volume"),
		0x1F509 to ("bocina media" to "speaker medium volume"),
		0x1F50A to ("bocina alta" to "speaker high volume"),
		0x1F507 to ("silencio" to "muted speaker"),
		0x1F50B to ("batería" to "battery"),
		0x1F50C to ("enchufe" to "electric plug"),
		0x1F512 to ("candado" to "locked"),
		0x1F513 to ("abierto" to "unlocked"),
		0x1F52C to ("microscopio" to "microscope"),
		0x1F52D to ("telescopio" to "telescope"),
		0x1F52E to ("bola de cristal" to "crystal ball"),
		0x1F489 to ("jeringa" to "syringe"),
		0x1F48A to ("pastilla" to "pill"),
		0x1F4AC to ("globo de diálogo" to "speech balloon"),
		0x1F4AD to ("globo de pensamiento" to "thought balloon"),
		0x1F4B1 to ("cambio de moneda" to "currency exchange"),
		0x1F4B3 to ("tarjeta" to "credit card"),
		0x1F4B9 to ("gráfica subiendo" to "chart increasing"),
		0x1F4BC to ("portafolio" to "briefcase"),
		0x1F4C1 to ("carpeta" to "file folder"),
		0x1F4C3 to ("documento" to "page with curl"),
		0x1F4CB to ("portapapeles" to "clipboard"),
		0x1F4CC to ("chincheta" to "pushpin"),
		0x1F4CE to ("clip" to "paperclip"),
		0x1F4D3 to ("cuaderno" to "notebook"),
		0x1F4D5 to ("libro cerrado" to "closed book"),
		0x1F4D6 to ("libro abierto" to "open book"),
		0x1F4DC to ("pergamino" to "scroll"),
		0x1F4DD to ("nota" to "memo"),
		0x1F4E0 to ("fax" to "fax machine"),
		0x1F4E1 to ("satélite" to "satellite antenna"),
		0x1F4E2 to ("altavoz de anuncio" to "loudspeaker"),
		0x1F4E3 to ("megáfono" to "megaphone"),
		0x1F4E6 to ("paquete" to "package"),
		0x1F4F2 to ("teléfono con flecha" to "mobile phone with arrow"),
		0x1F4F3 to ("modo vibración" to "vibration mode"),
		0x1F4F4 to ("teléfono apagado" to "mobile phone off"),
		0x1F4F6 to ("señal" to "antenna bars"),
		0x1F4F8 to ("cámara con flash" to "camera with flash"),
		0x1F4F9 to ("videocámara" to "video camera"),
		0x1F4FA to ("televisión" to "television"),
		0x1F4FB to ("radio" to "radio"),
		0x1F500 to ("aleatorio" to "shuffle tracks button"),
		0x1F501 to ("repetir" to "repeat button"),
		0x1F503 to ("recargar" to "clockwise vertical arrows"),
		0x1F4F5 to ("sin teléfono" to "no mobile phones"),
		0x23E9 to ("adelante" to "fast-forward button"),
		0x23EA to ("atrás" to "fast reverse button"),
		0x23EB to ("siguiente pista" to "next track button"),
		0x23EC to ("pista anterior" to "last track button"),
		0x23F1 to ("cronómetro" to "stopwatch"),
		0x23F2 to ("temporizador" to "timer clock"),
		0x23F8 to ("pausa" to "pause button"),
		0x23F9 to ("detener" to "stop button"),
		0x23FA to ("grabar" to "record button"),
		0x25B6 to ("reproducir" to "play button"),
		0x1F534 to ("círculo rojo" to "red circle"),
		0x1F535 to ("círculo azul" to "blue circle"),
		0x1F7E0 to ("círculo naranja" to "orange circle"),
		0x1F7E1 to ("círculo amarillo" to "yellow circle"),
		0x1F7E2 to ("círculo verde" to "green circle"),
		0x1F7E3 to ("círculo morado" to "purple circle"),
		0x1F7E4 to ("círculo marrón" to "brown circle"),
		0x1F9D0 to ("cara con monóculo" to "face with monocle"),
		0x1F9D1 to ("persona" to "person"),
		0x1F9D2 to ("niño" to "child"),
		0x1F9D3 to ("adulto mayor" to "older person"),
		0x1F474 to ("hombre mayor" to "old man"),
		0x1F475 to ("mujer mayor" to "old woman"),
		0x1F467 to ("niña" to "girl"),
		0x1F466 to ("niño" to "boy"),
		0x1F468 to ("hombre" to "man"),
		0x1F469 to ("mujer" to "woman"),
		0x1F471 to ("persona rubia" to "person blond hair"),
		0x1F472 to ("hombre con gorra" to "man with chinese cap"),
		0x1F473 to ("hombre con turbante" to "person wearing turban"),
		0x1F477 to ("obrero" to "construction worker"),
		0x1F478 to ("princesa" to "princess"),
		0x1F479 to ("ogro" to "ogre"),
		0x1F47A to ("duende" to "goblin"),
		0x1F47C to ("bebé ángel" to "baby angel"),
		0x1F47E to ("huellas" to "paw prints"),
		0x1F47F to ("cara enojada con cuernos" to "angry face with horns"),
		0x1F481 to ("persona con información" to "person tipping hand"),
		0x1F482 to ("guardia" to "guardsman"),
		0x1F484 to ("labial" to "lipstick"),
		0x1F485 to ("uñas" to "nail polish"),
		0x1F486 to ("masaje facial" to "person getting massage"),
		0x1F487 to ("corte de pelo" to "person getting haircut"),
		0x1F488 to ("peluquería" to "barber pole"),
		0x1F48B to ("beso" to "kiss mark"),
		0x1F48C to ("carta de amor" to "love letter"),
		0x1F48E to ("joya" to "gem stone"),
		0x1F48F to ("pareja con corazón" to "kiss"),
		0x1F490 to ("ramo" to "bouquet"),
		0x1F491 to ("pareja" to "couple with heart"),
		0x1F492 to ("boda" to "wedding")
	)

	/** Nombres naturales en ocho idiomas (francés, alemán,
	 *  italiano, japonés, polaco, portugués, chino y coreano)
	 *  para los emojis más comunes. Sin entrada aquí el nombre
	 *  base se traduce por palabras (diez idiomas) o queda en
	 *  inglés, así nada queda en silencio. */
	private val OTHER_NAMES: Map<Int, Map<String, String>> = mapOf(
		0x1F600 to mapOf("fra" to "visage souriant", "deu" to "grinsendes Gesicht", "ita" to "faccina sorridente", "jpn" to "笑顔", "pol" to "uśmiechnięta twarz", "por" to "rosto sorridente", "cmn" to "笑脸", "kor" to "웃는 얼굴"),
		0x1F602 to mapOf("fra" to "visage qui pleure de joie", "deu" to "Freudentränen", "ita" to "faccina con lacrime di gioia", "jpn" to "喜びの涙", "pol" to "twarz płacząca ze śmiechu", "por" to "rosto chorando de rir", "cmn" to "笑哭", "kor" to "웃음 눈물"),
		0x1F60D to mapOf("fra" to "visage avec yeux en cœur", "deu" to "verliebtes Gesicht", "ita" to "faccina con occhi a cuore", "jpn" to "ハートの目", "pol" to "twarz z sercami w oczach", "por" to "rosto com olhos de coração", "cmn" to "爱心眼", "kor" to "하트 눈"),
		0x1F622 to mapOf("fra" to "visage qui pleure", "deu" to "weinendes Gesicht", "ita" to "faccina che piange", "jpn" to "泣き顔", "pol" to "płacząca twarz", "por" to "rosto chorando", "cmn" to "哭脸", "kor" to "우는 얼굴"),
		0x1F621 to mapOf("fra" to "visage en colère", "deu" to "wütendes Gesicht", "ita" to "faccina arrabbiata", "jpn" to "怒った顔", "pol" to "zła twarz", "por" to "rosto irritado", "cmn" to "生气", "kor" to "화난 얼굴"),
		0x1F44D to mapOf("fra" to "pouce levé", "deu" to "Daumen hoch", "ita" to "pollice su", "jpn" to "いいね", "pol" to "kciuk w górę", "por" to "polegar para cima", "cmn" to "点赞", "kor" to "좋아요"),
		0x1F44E to mapOf("fra" to "pouce baissé", "deu" to "Daumen runter", "ita" to "pollice giù", "jpn" to "だめ", "pol" to "kciuk w dół", "por" to "polegar para baixo", "cmn" to "差评", "kor" to "싫어요"),
		0x1F44F to mapOf("fra" to "applaudissements", "deu" to "klatschende Hände", "ita" to "mani che applaudono", "jpn" to "拍手", "pol" to "oklaski", "por" to "aplausos", "cmn" to "鼓掌", "kor" to "박수"),
		0x2764 to mapOf("fra" to "cœur rouge", "deu" to "rotes Herz", "ita" to "cuore rosso", "jpn" to "赤いハート", "pol" to "czerwone serce", "por" to "coração vermelho", "cmn" to "红心", "kor" to "빨간 하트"),
		0x1F494 to mapOf("fra" to "cœur brisé", "deu" to "gebrochenes Herz", "ita" to "cuore spezzato", "jpn" to "壊れたハート", "pol" to "złamane serce", "por" to "coração partido", "cmn" to "心碎", "kor" to "깨진 하트"),
		0x2B50 to mapOf("fra" to "étoile", "deu" to "Stern", "ita" to "stella", "jpn" to "星", "pol" to "gwiazdka", "por" to "estrela", "cmn" to "星星", "kor" to "별"),
		0x1F525 to mapOf("fra" to "feu", "deu" to "Feuer", "ita" to "fuoco", "jpn" to "火", "pol" to "ogień", "por" to "fogo", "cmn" to "火", "kor" to "불"),
		0x1F389 to mapOf("fra" to "cotillons", "deu" to "Partyknaller", "ita" to "coriandoli", "jpn" to "クラッカー", "pol" to "konfetti", "por" to "festa", "cmn" to "庆祝", "kor" to "파티"),
		0x2705 to mapOf("fra" to "coche", "deu" to "Häkchen", "ita" to "spunta", "jpn" to "チェック", "pol" to "ptaszek", "por" to "marca de seleção", "cmn" to "对勾", "kor" to "체크"),
		0x274C to mapOf("fra" to "croix", "deu" to "Kreuz", "ita" to "croce", "jpn" to "バツ", "pol" to "krzyżyk", "por" to "xis", "cmn" to "叉号", "kor" to "엑스"),
		0x1F4A1 to mapOf("fra" to "ampoule", "deu" to "Glühbirne", "ita" to "lampadina", "jpn" to "電球", "pol" to "żarówka", "por" to "lâmpada", "cmn" to "灯泡", "kor" to "전구"),
		0x1F4F1 to mapOf("fra" to "téléphone portable", "deu" to "Handy", "ita" to "telefono", "jpn" to "携帯電話", "pol" to "telefon", "por" to "celular", "cmn" to "手机", "kor" to "휴대폰"),
		0x1F697 to mapOf("fra" to "voiture", "deu" to "Auto", "ita" to "auto", "jpn" to "車", "pol" to "samochód", "por" to "carro", "cmn" to "汽车", "kor" to "자동차"),
		0x2708 to mapOf("fra" to "avion", "deu" to "Flugzeug", "ita" to "aereo", "jpn" to "飛行機", "pol" to "samolot", "por" to "avião", "cmn" to "飞机", "kor" to "비행기"),
		0x1F3E0 to mapOf("fra" to "maison", "deu" to "Haus", "ita" to "casa", "jpn" to "家", "pol" to "dom", "por" to "casa", "cmn" to "房子", "kor" to "집"),
		0x1F436 to mapOf("fra" to "chien", "deu" to "Hund", "ita" to "cane", "jpn" to "犬", "pol" to "pies", "por" to "cachorro", "cmn" to "狗", "kor" to "강아지"),
		0x1F431 to mapOf("fra" to "chat", "deu" to "Katze", "ita" to "gatto", "jpn" to "猫", "pol" to "kot", "por" to "gato", "cmn" to "猫", "kor" to "고양이"),
		0x2615 to mapOf("fra" to "café chaud", "deu" to "Kaffee", "ita" to "caffè", "jpn" to "コーヒー", "pol" to "kawa", "por" to "café", "cmn" to "咖啡", "kor" to "커피"),
		0x1F3B5 to mapOf("fra" to "note de musique", "deu" to "Musiknote", "ita" to "nota musicale", "jpn" to "音符", "pol" to "nutka", "por" to "nota musical", "cmn" to "音符", "kor" to "음표"),
		0x1F3C6 to mapOf("fra" to "trophée", "deu" to "Pokal", "ita" to "trofeo", "jpn" to "トロフィー", "pol" to "trofeum", "por" to "troféu", "cmn" to "奖杯", "kor" to "트로피"),
		0x1F4B0 to mapOf("fra" to "sac d argent", "deu" to "Geldsack", "ita" to "soldi", "jpn" to "お金", "pol" to "pieniądze", "por" to "dinheiro", "cmn" to "钱袋", "kor" to "돈"),
		0x1F511 to mapOf("fra" to "clé", "deu" to "Schlüssel", "ita" to "chiave", "jpn" to "鍵", "pol" to "klucz", "por" to "chave", "cmn" to "钥匙", "kor" to "열쇠"),
		0x1F6AB to mapOf("fra" to "interdit", "deu" to "verboten", "ita" to "vietato", "jpn" to "禁止", "pol" to "zakaz", "por" to "proibido", "cmn" to "禁止", "kor" to "금지"),
		0x1F914 to mapOf("fra" to "visage réfléchi", "deu" to "nachdenkliches Gesicht", "ita" to "faccina pensierosa", "jpn" to "考える顔", "pol" to "zamyślona twarz", "por" to "rosto pensativo", "cmn" to "思考", "kor" to "생각"),
		0x1F920 to mapOf("fra" to "visage de cowboy", "deu" to "Cowboy-Gesicht", "ita" to "faccina da cowboy", "jpn" to "カウボーイ", "pol" to "twarz kowboja", "por" to "rosto de caubói", "cmn" to "牛仔", "kor" to "카우보이"),
		0x1F47B to mapOf("fra" to "fantôme", "deu" to "Geist", "ita" to "fantasma", "jpn" to "お化け", "pol" to "duch", "por" to "fantasma", "cmn" to "幽灵", "kor" to "유령"),
		0x1F480 to mapOf("fra" to "crâne", "deu" to "Totenkopf", "ita" to "teschio", "jpn" to "ドクロ", "pol" to "czaszka", "por" to "caveira", "cmn" to "骷髅", "kor" to "해골"),
		0x1F916 to mapOf("fra" to "robot", "deu" to "Roboter", "ita" to "robot", "jpn" to "ロボット", "pol" to "robot", "por" to "robô", "cmn" to "机器人", "kor" to "로봇"),
		0x1F308 to mapOf("fra" to "arc-en-ciel", "deu" to "Regenbogen", "ita" to "arcobaleno", "jpn" to "虹", "pol" to "tęcza", "por" to "arco-íris", "cmn" to "彩虹", "kor" to "무지개"),
		0x26A1 to mapOf("fra" to "haute tension", "deu" to "Hochspannung", "ita" to "alta tensione", "jpn" to "高電圧", "pol" to "wysokie napięcie", "por" to "alta voltagem", "cmn" to "高压电", "kor" to "고전압"),
		0x23F0 to mapOf("fra" to "réveil", "deu" to "Wecker", "ita" to "sveglia", "jpn" to "目覚まし", "pol" to "budzik", "por" to "despertador", "cmn" to "闹钟", "kor" to "알람"),
		0x1F3AE to mapOf("fra" to "jeu vidéo", "deu" to "Videospiel", "ita" to "videogioco", "jpn" to "ゲーム", "pol" to "gra wideo", "por" to "videogame", "cmn" to "游戏", "kor" to "게임"),
		0x1F923 to mapOf("cmn" to "笑翻", "jpn" to "大笑い", "kor" to "박장대소"),
		0x1F60A to mapOf("cmn" to "微笑", "jpn" to "微笑み", "kor" to "미소"),
		0x1F618 to mapOf("cmn" to "飞吻", "jpn" to "投げキス", "kor" to "키스"),
		0x1F60E to mapOf("cmn" to "墨镜笑脸", "jpn" to "サングラス", "kor" to "선글라스"),
		0x1F62D to mapOf("cmn" to "大哭", "jpn" to "大泣き", "kor" to "엉엉"),
		0x1F970 to mapOf("cmn" to "爱心笑脸", "jpn" to "ハートの笑顔", "kor" to "하트 미소"),
		0x1F921 to mapOf("cmn" to "小丑", "jpn" to "ピエロ", "kor" to "광대"),
		0x1F63A to mapOf("cmn" to "咧嘴猫", "jpn" to "笑う猫", "kor" to "웃는 고양이"),
		0x1F648 to mapOf("cmn" to "捂眼猴", "jpn" to "見ざる", "kor" to "못 본 원숭이"),
		0x1F64F to mapOf("cmn" to "祈祷", "jpn" to "祈り", "kor" to "기도"),
		0x1F91D to mapOf("cmn" to "握手", "jpn" to "握手", "kor" to "악수"),
		0x1F44C to mapOf("cmn" to "OK手势", "jpn" to "OK", "kor" to "오케이"),
		0x1F44B to mapOf("cmn" to "挥手", "jpn" to "手を振る", "kor" to "손 흔들기"),
		0x1F4AA to mapOf("cmn" to "肌肉", "jpn" to "筋肉", "kor" to "근육"),
		0x1F495 to mapOf("cmn" to "两颗心", "jpn" to "ダブルハート", "kor" to "두 하트"),
		0x1F496 to mapOf("cmn" to "闪亮的心", "jpn" to "キラキラハート", "kor" to "반짝이는 하트"),
		0x1F499 to mapOf("cmn" to "蓝心", "jpn" to "青ハート", "kor" to "파란 하트"),
		0x1F49C to mapOf("cmn" to "紫心", "jpn" to "紫ハート", "kor" to "보라 하트"),
		0x1F90D to mapOf("cmn" to "白心", "jpn" to "白ハート", "kor" to "흰 하트"),
		0x1F382 to mapOf("cmn" to "蛋糕", "jpn" to "ケーキ", "kor" to "케이크"),
		0x1F381 to mapOf("cmn" to "礼物", "jpn" to "プレゼント", "kor" to "선물"),
		0x26BD to mapOf("cmn" to "足球", "jpn" to "サッカー", "kor" to "축구"),
		0x2753 to mapOf("cmn" to "问号", "jpn" to "疑問符", "kor" to "물음표"),
		0x2757 to mapOf("cmn" to "感叹号", "jpn" to "感嘆符", "kor" to "느낌표"),
		0x1F4BB to mapOf("cmn" to "笔记本电脑", "jpn" to "パソコン", "kor" to "노트북"),
		0x1F37A to mapOf("cmn" to "啤酒", "jpn" to "ビール", "kor" to "맥주"),
		0x1F947 to mapOf("cmn" to "金牌", "jpn" to "金メダル", "kor" to "금메달"),
		0x1F451 to mapOf("cmn" to "王冠", "jpn" to "王冠", "kor" to "왕관"),
		0x1F388 to mapOf("cmn" to "气球", "jpn" to "風船", "kor" to "풍선"),
		0x1F34E to mapOf("cmn" to "苹果", "jpn" to "りんご", "kor" to "사과"),
		0x1F355 to mapOf("cmn" to "披萨", "jpn" to "ピザ", "kor" to "피자"),
		0x1F354 to mapOf("cmn" to "汉堡", "jpn" to "ハンバーガー", "kor" to "햄버거"),
		0x1F3C0 to mapOf("cmn" to "篮球", "jpn" to "バスケ", "kor" to "농구"),
		0x1F52A to mapOf("cmn" to "菜刀", "jpn" to "包丁", "kor" to "칼"),
		0x1F528 to mapOf("cmn" to "锤子", "jpn" to "金槌", "kor" to "망치"),
		0x1F4DA to mapOf("cmn" to "书籍", "jpn" to "本", "kor" to "책"),
		0x1F514 to mapOf("cmn" to "铃铛", "jpn" to "ベル", "kor" to "종"),
		0x1F3A4 to mapOf("cmn" to "麦克风", "jpn" to "マイク", "kor" to "마이크"),
		0x1F3B8 to mapOf("cmn" to "吉他", "jpn" to "ギター", "kor" to "기타"),
		0x1F30A to mapOf("cmn" to "海浪", "jpn" to "波", "kor" to "파도")
	)

	private val FLAGS: Map<String, Pair<String, String>> = mapOf(
		"MX" to ("México" to "Mexico"),
		"US" to ("Estados Unidos" to "United States"),
		"ES" to ("España" to "Spain"),
		"AR" to ("Argentina" to "Argentina"),
		"CO" to ("Colombia" to "Colombia"),
		"CL" to ("Chile" to "Chile"),
		"PE" to ("Perú" to "Peru"),
		"VE" to ("Venezuela" to "Venezuela"),
		"EC" to ("Ecuador" to "Ecuador"),
		"UY" to ("Uruguay" to "Uruguay"),
		"PY" to ("Paraguay" to "Paraguay"),
		"BO" to ("Bolivia" to "Bolivia"),
		"BR" to ("Brasil" to "Brazil"),
		"PT" to ("Portugal" to "Portugal"),
		"DO" to ("República Dominicana" to "Dominican Republic"),
		"CU" to ("Cuba" to "Cuba"),
		"PR" to ("Puerto Rico" to "Puerto Rico"),
		"GT" to ("Guatemala" to "Guatemala"),
		"HN" to ("Honduras" to "Honduras"),
		"NI" to ("Nicaragua" to "Nicaragua"),
		"CR" to ("Costa Rica" to "Costa Rica"),
		"PA" to ("Panamá" to "Panama"),
		"SV" to ("El Salvador" to "El Salvador"),
		"FR" to ("Francia" to "France"),
		"DE" to ("Alemania" to "Germany"),
		"IT" to ("Italia" to "Italy"),
		"GB" to ("Reino Unido" to "United Kingdom"),
		"IE" to ("Irlanda" to "Ireland"),
		"NL" to ("Países Bajos" to "Netherlands"),
		"BE" to ("Bélgica" to "Belgium"),
		"CH" to ("Suiza" to "Switzerland"),
		"AT" to ("Austria" to "Austria"),
		"SE" to ("Suecia" to "Sweden"),
		"NO" to ("Noruega" to "Norway"),
		"DK" to ("Dinamarca" to "Denmark"),
		"FI" to ("Finlandia" to "Finland"),
		"PL" to ("Polonia" to "Poland"),
		"GR" to ("Grecia" to "Greece"),
		"TR" to ("Turquía" to "Turkey"),
		"CA" to ("Canadá" to "Canada"),
		"AU" to ("Australia" to "Australia"),
		"NZ" to ("Nueva Zelanda" to "New Zealand"),
		"JP" to ("Japón" to "Japan"),
		"CN" to ("China" to "China"),
		"KR" to ("Corea del Sur" to "South Korea"),
		"IN" to ("India" to "India"),
		"RU" to ("Rusia" to "Russia"),
		"UA" to ("Ucrania" to "Ukraine"),
		"IL" to ("Israel" to "Israel")
	)

	/** El país de cada bandera en catorce idiomas más (español e
	 *  inglés ya están en FLAGS). Orden: fra, deu, ita, por, pol,
	 *  nld, swe, nor, dan, fin, cmn, yue, jpn, kor. */
	private val OTHER_FLAGS: Map<String, Map<String, String>> = mapOf(
		"MX" to mapOf("fra" to "Mexique", "deu" to "Mexiko", "ita" to "Messico", "por" to "México", "pol" to "Meksyk", "nld" to "Mexico", "swe" to "Mexiko", "nor" to "Mexico", "dan" to "Mexico", "fin" to "Meksiko", "cmn" to "墨西哥", "yue" to "墨西哥", "jpn" to "メキシコ", "kor" to "멕시코"),
		"US" to mapOf("fra" to "États-Unis", "deu" to "USA", "ita" to "Stati Uniti", "por" to "Estados Unidos", "pol" to "Stany Zjednoczone", "nld" to "Verenigde Staten", "swe" to "USA", "nor" to "USA", "dan" to "USA", "fin" to "Yhdysvallat", "cmn" to "美国", "yue" to "美國", "jpn" to "アメリカ", "kor" to "미국"),
		"ES" to mapOf("fra" to "Espagne", "deu" to "Spanien", "ita" to "Spagna", "por" to "Espanha", "pol" to "Hiszpania", "nld" to "Spanje", "swe" to "Spanien", "nor" to "Spania", "dan" to "Spanien", "fin" to "Espanja", "cmn" to "西班牙", "yue" to "西班牙", "jpn" to "スペイン", "kor" to "스페인"),
		"AR" to mapOf("fra" to "Argentine", "deu" to "Argentinien", "ita" to "Argentina", "por" to "Argentina", "pol" to "Argentyna", "nld" to "Argentinië", "swe" to "Argentina", "nor" to "Argentina", "dan" to "Argentina", "fin" to "Argentiina", "cmn" to "阿根廷", "yue" to "阿根廷", "jpn" to "アルゼンチン", "kor" to "아르헨티나"),
		"CO" to mapOf("fra" to "Colombie", "deu" to "Kolumbien", "ita" to "Colombia", "por" to "Colômbia", "pol" to "Kolumbia", "nld" to "Colombia", "swe" to "Colombia", "nor" to "Colombia", "dan" to "Colombia", "fin" to "Kolumbia", "cmn" to "哥伦比亚", "yue" to "哥倫比亞", "jpn" to "コロンビア", "kor" to "콜롬비아"),
		"CL" to mapOf("fra" to "Chili", "deu" to "Chile", "ita" to "Cile", "por" to "Chile", "pol" to "Chile", "nld" to "Chili", "swe" to "Chile", "nor" to "Chile", "dan" to "Chile", "fin" to "Chile", "cmn" to "智利", "yue" to "智利", "jpn" to "チリ", "kor" to "칠레"),
		"PE" to mapOf("fra" to "Pérou", "deu" to "Peru", "ita" to "Perù", "por" to "Peru", "pol" to "Peru", "nld" to "Peru", "swe" to "Peru", "nor" to "Peru", "dan" to "Peru", "fin" to "Peru", "cmn" to "秘鲁", "yue" to "秘魯", "jpn" to "ペルー", "kor" to "페루"),
		"VE" to mapOf("fra" to "Venezuela", "deu" to "Venezuela", "ita" to "Venezuela", "por" to "Venezuela", "pol" to "Wenezuela", "nld" to "Venezuela", "swe" to "Venezuela", "nor" to "Venezuela", "dan" to "Venezuela", "fin" to "Venezuela", "cmn" to "委内瑞拉", "yue" to "委內瑞拉", "jpn" to "ベネズエラ", "kor" to "베네수엘라"),
		"EC" to mapOf("fra" to "Équateur", "deu" to "Ecuador", "ita" to "Ecuador", "por" to "Equador", "pol" to "Ekwador", "nld" to "Ecuador", "swe" to "Ecuador", "nor" to "Ecuador", "dan" to "Ecuador", "fin" to "Ecuador", "cmn" to "厄瓜多尔", "yue" to "厄瓜多爾", "jpn" to "エクアドル", "kor" to "에콰도르"),
		"UY" to mapOf("fra" to "Uruguay", "deu" to "Uruguay", "ita" to "Uruguay", "por" to "Uruguai", "pol" to "Urugwaj", "nld" to "Uruguay", "swe" to "Uruguay", "nor" to "Uruguay", "dan" to "Uruguay", "fin" to "Uruguay", "cmn" to "乌拉圭", "yue" to "烏拉圭", "jpn" to "ウルグアイ", "kor" to "우루과이"),
		"PY" to mapOf("fra" to "Paraguay", "deu" to "Paraguay", "ita" to "Paraguay", "por" to "Paraguai", "pol" to "Paragwaj", "nld" to "Paraguay", "swe" to "Paraguay", "nor" to "Paraguay", "dan" to "Paraguay", "fin" to "Paraguay", "cmn" to "巴拉圭", "yue" to "巴拉圭", "jpn" to "パラグアイ", "kor" to "파라과이"),
		"BO" to mapOf("fra" to "Bolivie", "deu" to "Bolivien", "ita" to "Bolivia", "por" to "Bolívia", "pol" to "Boliwia", "nld" to "Bolivia", "swe" to "Bolivia", "nor" to "Bolivia", "dan" to "Bolivia", "fin" to "Bolivia", "cmn" to "玻利维亚", "yue" to "玻利維亞", "jpn" to "ボリビア", "kor" to "볼리비아"),
		"BR" to mapOf("fra" to "Brésil", "deu" to "Brasilien", "ita" to "Brasile", "por" to "Brasil", "pol" to "Brazylia", "nld" to "Brazilië", "swe" to "Brasilien", "nor" to "Brasil", "dan" to "Brasilien", "fin" to "Brasilia", "cmn" to "巴西", "yue" to "巴西", "jpn" to "ブラジル", "kor" to "브라질"),
		"PT" to mapOf("fra" to "Portugal", "deu" to "Portugal", "ita" to "Portogallo", "por" to "Portugal", "pol" to "Portugalia", "nld" to "Portugal", "swe" to "Portugal", "nor" to "Portugal", "dan" to "Portugal", "fin" to "Portugali", "cmn" to "葡萄牙", "yue" to "葡萄牙", "jpn" to "ポルトガル", "kor" to "포르투갈"),
		"DO" to mapOf("fra" to "République dominicaine", "deu" to "Dominikanische Republik", "ita" to "Repubblica Dominicana", "por" to "República Dominicana", "pol" to "Dominikana", "nld" to "Dominicaanse Republiek", "swe" to "Dominikanska republiken", "nor" to "Den dominikanske republikk", "dan" to "Dominikanske Republik", "fin" to "Dominikaaninen tasavalta", "cmn" to "多米尼加", "yue" to "多米尼加", "jpn" to "ドミニカ共和国", "kor" to "도미니카공화국"),
		"CU" to mapOf("fra" to "Cuba", "deu" to "Kuba", "ita" to "Cuba", "por" to "Cuba", "pol" to "Kuba", "nld" to "Cuba", "swe" to "Kuba", "nor" to "Cuba", "dan" to "Cuba", "fin" to "Kuuba", "cmn" to "古巴", "yue" to "古巴", "jpn" to "キューバ", "kor" to "쿠바"),
		"PR" to mapOf("fra" to "Porto Rico", "deu" to "Puerto Rico", "ita" to "Portorico", "por" to "Porto Rico", "pol" to "Portoryko", "nld" to "Puerto Rico", "swe" to "Puerto Rico", "nor" to "Puerto Rico", "dan" to "Puerto Rico", "fin" to "Puerto Rico", "cmn" to "波多黎各", "yue" to "波多黎各", "jpn" to "プエルトリコ", "kor" to "푸에르토리코"),
		"GT" to mapOf("fra" to "Guatemala", "deu" to "Guatemala", "ita" to "Guatemala", "por" to "Guatemala", "pol" to "Gwatemala", "nld" to "Guatemala", "swe" to "Guatemala", "nor" to "Guatemala", "dan" to "Guatemala", "fin" to "Guatemala", "cmn" to "危地马拉", "yue" to "危地馬拉", "jpn" to "グアテマラ", "kor" to "과테말라"),
		"HN" to mapOf("fra" to "Honduras", "deu" to "Honduras", "ita" to "Honduras", "por" to "Honduras", "pol" to "Honduras", "nld" to "Honduras", "swe" to "Honduras", "nor" to "Honduras", "dan" to "Honduras", "fin" to "Honduras", "cmn" to "洪都拉斯", "yue" to "洪都拉斯", "jpn" to "ホンジュラス", "kor" to "온두라스"),
		"NI" to mapOf("fra" to "Nicaragua", "deu" to "Nicaragua", "ita" to "Nicaragua", "por" to "Nicarágua", "pol" to "Nikaragua", "nld" to "Nicaragua", "swe" to "Nicaragua", "nor" to "Nicaragua", "dan" to "Nicaragua", "fin" to "Nicaragua", "cmn" to "尼加拉瓜", "yue" to "尼加拉瓜", "jpn" to "ニカラグア", "kor" to "니카라과"),
		"CR" to mapOf("fra" to "Costa Rica", "deu" to "Costa Rica", "ita" to "Costa Rica", "por" to "Costa Rica", "pol" to "Kostaryka", "nld" to "Costa Rica", "swe" to "Costa Rica", "nor" to "Costa Rica", "dan" to "Costa Rica", "fin" to "Costa Rica", "cmn" to "哥斯达黎加", "yue" to "哥斯達黎加", "jpn" to "コスタリカ", "kor" to "코스타리카"),
		"PA" to mapOf("fra" to "Panama", "deu" to "Panama", "ita" to "Panama", "por" to "Panamá", "pol" to "Panama", "nld" to "Panama", "swe" to "Panama", "nor" to "Panama", "dan" to "Panama", "fin" to "Panama", "cmn" to "巴拿马", "yue" to "巴拿馬", "jpn" to "パナマ", "kor" to "파나마"),
		"SV" to mapOf("fra" to "Salvador", "deu" to "El Salvador", "ita" to "El Salvador", "por" to "El Salvador", "pol" to "Salwador", "nld" to "El Salvador", "swe" to "El Salvador", "nor" to "El Salvador", "dan" to "El Salvador", "fin" to "El Salvador", "cmn" to "萨尔瓦多", "yue" to "薩爾瓦多", "jpn" to "エルサルバドル", "kor" to "엘살바도르"),
		"FR" to mapOf("fra" to "France", "deu" to "Frankreich", "ita" to "Francia", "por" to "França", "pol" to "Francja", "nld" to "Frankrijk", "swe" to "Frankrike", "nor" to "Frankrike", "dan" to "Frankrig", "fin" to "Ranska", "cmn" to "法国", "yue" to "法國", "jpn" to "フランス", "kor" to "프랑스"),
		"DE" to mapOf("fra" to "Allemagne", "deu" to "Deutschland", "ita" to "Germania", "por" to "Alemanha", "pol" to "Niemcy", "nld" to "Duitsland", "swe" to "Tyskland", "nor" to "Tyskland", "dan" to "Tyskland", "fin" to "Saksa", "cmn" to "德国", "yue" to "德國", "jpn" to "ドイツ", "kor" to "독일"),
		"IT" to mapOf("fra" to "Italie", "deu" to "Italien", "ita" to "Italia", "por" to "Itália", "pol" to "Włochy", "nld" to "Italië", "swe" to "Italien", "nor" to "Italia", "dan" to "Italien", "fin" to "Italia", "cmn" to "意大利", "yue" to "意大利", "jpn" to "イタリア", "kor" to "이탈리아"),
		"GB" to mapOf("fra" to "Royaume-Uni", "deu" to "Großbritannien", "ita" to "Regno Unito", "por" to "Reino Unido", "pol" to "Wielka Brytania", "nld" to "Verenigd Koninkrijk", "swe" to "Storbritannien", "nor" to "Storbritannia", "dan" to "Storbritannien", "fin" to "Iso-Britannia", "cmn" to "英国", "yue" to "英國", "jpn" to "イギリス", "kor" to "영국"),
		"IE" to mapOf("fra" to "Irlande", "deu" to "Irland", "ita" to "Irlanda", "por" to "Irlanda", "pol" to "Irlandia", "nld" to "Ierland", "swe" to "Irland", "nor" to "Irland", "dan" to "Irland", "fin" to "Irlanti", "cmn" to "爱尔兰", "yue" to "愛爾蘭", "jpn" to "アイルランド", "kor" to "아일랜드"),
		"NL" to mapOf("fra" to "Pays-Bas", "deu" to "Niederlande", "ita" to "Paesi Bassi", "por" to "Países Baixos", "pol" to "Holandia", "nld" to "Nederland", "swe" to "Nederländerna", "nor" to "Nederland", "dan" to "Holland", "fin" to "Alankomaat", "cmn" to "荷兰", "yue" to "荷蘭", "jpn" to "オランダ", "kor" to "네덜란드"),
		"BE" to mapOf("fra" to "Belgique", "deu" to "Belgien", "ita" to "Belgio", "por" to "Bélgica", "pol" to "Belgia", "nld" to "België", "swe" to "Belgien", "nor" to "Belgia", "dan" to "Belgien", "fin" to "Belgia", "cmn" to "比利时", "yue" to "比利時", "jpn" to "ベルギー", "kor" to "벨기에"),
		"CH" to mapOf("fra" to "Suisse", "deu" to "Schweiz", "ita" to "Svizzera", "por" to "Suíça", "pol" to "Szwajcaria", "nld" to "Zwitserland", "swe" to "Schweiz", "nor" to "Sveits", "dan" to "Schweiz", "fin" to "Sveitsi", "cmn" to "瑞士", "yue" to "瑞士", "jpn" to "スイス", "kor" to "스위스"),
		"AT" to mapOf("fra" to "Autriche", "deu" to "Österreich", "ita" to "Austria", "por" to "Áustria", "pol" to "Austria", "nld" to "Oostenrijk", "swe" to "Österrike", "nor" to "Østerrike", "dan" to "Østrig", "fin" to "Itävalta", "cmn" to "奥地利", "yue" to "奧地利", "jpn" to "オーストリア", "kor" to "오스트리아"),
		"SE" to mapOf("fra" to "Suède", "deu" to "Schweden", "ita" to "Svezia", "por" to "Suécia", "pol" to "Szwecja", "nld" to "Zweden", "swe" to "Sverige", "nor" to "Sverige", "dan" to "Sverige", "fin" to "Ruotsi", "cmn" to "瑞典", "yue" to "瑞典", "jpn" to "スウェーデン", "kor" to "스웨덴"),
		"NO" to mapOf("fra" to "Norvège", "deu" to "Norwegen", "ita" to "Norvegia", "por" to "Noruega", "pol" to "Norwegia", "nld" to "Noorwegen", "swe" to "Norge", "nor" to "Norge", "dan" to "Norge", "fin" to "Norja", "cmn" to "挪威", "yue" to "挪威", "jpn" to "ノルウェー", "kor" to "노르웨이"),
		"DK" to mapOf("fra" to "Danemark", "deu" to "Dänemark", "ita" to "Danimarca", "por" to "Dinamarca", "pol" to "Dania", "nld" to "Denemarken", "swe" to "Danmark", "nor" to "Danmark", "dan" to "Danmark", "fin" to "Tanska", "cmn" to "丹麦", "yue" to "丹麥", "jpn" to "デンマーク", "kor" to "덴마크"),
		"FI" to mapOf("fra" to "Finlande", "deu" to "Finnland", "ita" to "Finlandia", "por" to "Finlândia", "pol" to "Finlandia", "nld" to "Finland", "swe" to "Finland", "nor" to "Finland", "dan" to "Finland", "fin" to "Suomi", "cmn" to "芬兰", "yue" to "芬蘭", "jpn" to "フィンランド", "kor" to "핀란드"),
		"PL" to mapOf("fra" to "Pologne", "deu" to "Polen", "ita" to "Polonia", "por" to "Polônia", "pol" to "Polska", "nld" to "Polen", "swe" to "Polen", "nor" to "Polen", "dan" to "Polen", "fin" to "Puola", "cmn" to "波兰", "yue" to "波蘭", "jpn" to "ポーランド", "kor" to "폴란드"),
		"GR" to mapOf("fra" to "Grèce", "deu" to "Griechenland", "ita" to "Grecia", "por" to "Grécia", "pol" to "Grecja", "nld" to "Griekenland", "swe" to "Grekland", "nor" to "Hellas", "dan" to "Grækenland", "fin" to "Kreikka", "cmn" to "希腊", "yue" to "希臘", "jpn" to "ギリシャ", "kor" to "그리스"),
		"TR" to mapOf("fra" to "Turquie", "deu" to "Türkei", "ita" to "Turchia", "por" to "Turquia", "pol" to "Turcja", "nld" to "Turkije", "swe" to "Turkiet", "nor" to "Tyrkia", "dan" to "Tyrkiet", "fin" to "Turkki", "cmn" to "土耳其", "yue" to "土耳其", "jpn" to "トルコ", "kor" to "터키"),
		"CA" to mapOf("fra" to "Canada", "deu" to "Kanada", "ita" to "Canada", "por" to "Canadá", "pol" to "Kanada", "nld" to "Canada", "swe" to "Kanada", "nor" to "Canada", "dan" to "Canada", "fin" to "Kanada", "cmn" to "加拿大", "yue" to "加拿大", "jpn" to "カナダ", "kor" to "캐나다"),
		"AU" to mapOf("fra" to "Australie", "deu" to "Australien", "ita" to "Australia", "por" to "Austrália", "pol" to "Australia", "nld" to "Australië", "swe" to "Australien", "nor" to "Australia", "dan" to "Australien", "fin" to "Australia", "cmn" to "澳大利亚", "yue" to "澳大利亞", "jpn" to "オーストラリア", "kor" to "호주"),
		"NZ" to mapOf("fra" to "Nouvelle-Zélande", "deu" to "Neuseeland", "ita" to "Nuova Zelanda", "por" to "Nova Zelândia", "pol" to "Nowa Zelandia", "nld" to "Nieuw-Zeeland", "swe" to "Nya Zeeland", "nor" to "New Zealand", "dan" to "New Zealand", "fin" to "Uusi-Seelanti", "cmn" to "新西兰", "yue" to "新西蘭", "jpn" to "ニュージーランド", "kor" to "뉴질랜드"),
		"JP" to mapOf("fra" to "Japon", "deu" to "Japan", "ita" to "Giappone", "por" to "Japão", "pol" to "Japonia", "nld" to "Japan", "swe" to "Japan", "nor" to "Japan", "dan" to "Japan", "fin" to "Japani", "cmn" to "日本", "yue" to "日本", "jpn" to "日本", "kor" to "일본"),
		"CN" to mapOf("fra" to "Chine", "deu" to "China", "ita" to "Cina", "por" to "China", "pol" to "Chiny", "nld" to "China", "swe" to "Kina", "nor" to "Kina", "dan" to "Kina", "fin" to "Kiina", "cmn" to "中国", "yue" to "中國", "jpn" to "中国", "kor" to "중국"),
		"KR" to mapOf("fra" to "Corée du Sud", "deu" to "Südkorea", "ita" to "Corea del Sud", "por" to "Coreia do Sul", "pol" to "Korea Południowa", "nld" to "Zuid-Korea", "swe" to "Sydkorea", "nor" to "Sør-Korea", "dan" to "Sydkorea", "fin" to "Etelä-Korea", "cmn" to "韩国", "yue" to "韓國", "jpn" to "韓国", "kor" to "한국"),
		"IN" to mapOf("fra" to "Inde", "deu" to "Indien", "ita" to "India", "por" to "Índia", "pol" to "Indie", "nld" to "India", "swe" to "Indien", "nor" to "India", "dan" to "Indien", "fin" to "Intia", "cmn" to "印度", "yue" to "印度", "jpn" to "インド", "kor" to "인도"),
		"RU" to mapOf("fra" to "Russie", "deu" to "Russland", "ita" to "Russia", "por" to "Rússia", "pol" to "Rosja", "nld" to "Rusland", "swe" to "Ryssland", "nor" to "Russland", "dan" to "Rusland", "fin" to "Venäjä", "cmn" to "俄罗斯", "yue" to "俄羅斯", "jpn" to "ロシア", "kor" to "러시아"),
		"UA" to mapOf("fra" to "Ukraine", "deu" to "Ukraine", "ita" to "Ucraina", "por" to "Ucrânia", "pol" to "Ukraina", "nld" to "Oekraïne", "swe" to "Ukraina", "nor" to "Ukraina", "dan" to "Ukraine", "fin" to "Ukraina", "cmn" to "乌克兰", "yue" to "烏克蘭", "jpn" to "ウクライナ", "kor" to "우크라이나"),
		"IL" to mapOf("fra" to "Israël", "deu" to "Israel", "ita" to "Israele", "por" to "Israel", "pol" to "Izrael", "nld" to "Israël", "swe" to "Israel", "nor" to "Israel", "dan" to "Israel", "fin" to "Israel", "cmn" to "以色列", "yue" to "以色列", "jpn" to "イスラエル", "kor" to "이스라엘")
	)

	/** Inglaterra, Escocia y Gales en todos los idiomas. */
	private val NATIONS: Map<String, Map<String, String>> = mapOf(
		"gbeng" to mapOf("spa" to "Inglaterra", "eng" to "England", "fra" to "Angleterre", "deu" to "England", "ita" to "Inghilterra", "por" to "Inglaterra", "pol" to "Anglia", "nld" to "Engeland", "swe" to "England", "nor" to "England", "dan" to "England", "fin" to "Englanti", "cmn" to "英格兰", "yue" to "英格蘭", "jpn" to "イングランド", "kor" to "잉글랜드"),
		"gbsct" to mapOf("spa" to "Escocia", "eng" to "Scotland", "fra" to "Écosse", "deu" to "Schottland", "ita" to "Scozia", "por" to "Escócia", "pol" to "Szkocja", "nld" to "Schotland", "swe" to "Skottland", "nor" to "Skottland", "dan" to "Skotland", "fin" to "Skotlanti", "cmn" to "苏格兰", "yue" to "蘇格蘭", "jpn" to "スコットランド", "kor" to "스코틀랜드"),
		"gbwls" to mapOf("spa" to "Gales", "eng" to "Wales", "fra" to "Pays de Galles", "deu" to "Wales", "ita" to "Galles", "por" to "País de Gales", "pol" to "Walia", "nld" to "Wales", "swe" to "Wales", "nor" to "Wales", "dan" to "Wales", "fin" to "Wales", "cmn" to "威尔士", "yue" to "威爾斯", "jpn" to "ウェールズ", "kor" to "웨일스")
	)

	private val ES_WORDS: Map<String, String> = mapOf(
		"smiling" to "sonriente", "grinning" to "sonriendo", "laughing" to "riendo",
		"crying" to "llorando", "sleeping" to "durmiendo", "thinking" to "pensando",
		"winking" to "guiñando", "kissing" to "besando", "hugging" to "abrazando",
		"frowning" to "con el ceño", "pouting" to "enojada",
		"man" to "hombre", "woman" to "mujer", "person" to "persona",
		"people" to "personas", "child" to "niño", "baby" to "bebé",
		"boy" to "niño", "girl" to "niña", "old" to "viejo", "young" to "joven",
		"cat" to "gato", "dog" to "perro", "mouse" to "ratón", "bear" to "oso",
		"panda" to "panda", "lion" to "león", "tiger" to "tigre", "monkey" to "mono",
		"pig" to "cerdo", "cow" to "vaca", "bird" to "pájaro", "penguin" to "pingüino",
		"fish" to "pez", "whale" to "ballena", "heart" to "corazón", "hearts" to "corazones",
		"star" to "estrella", "sun" to "sol", "moon" to "luna", "cloud" to "nube",
		"rain" to "lluvia", "snow" to "nieve", "fire" to "fuego", "water" to "agua",
		"tree" to "árbol", "flower" to "flor", "hand" to "mano", "hands" to "manos",
		"eye" to "ojo", "eyes" to "ojos", "ear" to "oído", "nose" to "nariz",
		"mouth" to "boca", "foot" to "pie", "leg" to "pierna", "finger" to "dedo",
		"thumb" to "pulgar", "house" to "casa", "car" to "carro", "bus" to "autobús",
		"train" to "tren", "airplane" to "avión", "ship" to "barco", "boat" to "bote",
		"phone" to "teléfono", "computer" to "computadora", "camera" to "cámara",
		"book" to "libro", "money" to "dinero", "key" to "llave", "lock" to "candado",
		"gift" to "regalo", "cake" to "pastel", "balloon" to "globo", "trophy" to "trofeo",
		"medal" to "medalla", "crown" to "corona", "ring" to "anillo", "bell" to "campana",
		"clock" to "reloj", "light" to "luz", "red" to "rojo", "orange" to "naranja",
		"yellow" to "amarillo", "green" to "verde", "blue" to "azul", "purple" to "morado",
		"black" to "negro", "white" to "blanco", "brown" to "marrón",
		"with" to "con", "and" to "y", "of" to "de", "on" to "en", "in" to "en",
		"up" to "arriba", "down" to "abajo", "left" to "izquierda", "right" to "derecha",
		"leftwards" to "a la izquierda", "rightwards" to "a la derecha",
		"upwards" to "hacia arriba", "downwards" to "hacia abajo",
		"new" to "nuevo", "big" to "grande", "small" to "pequeño",
		"hot" to "caliente", "cold" to "frío", "happy" to "feliz", "sad" to "triste",
		"angry" to "enojada", 		"arrow" to "flecha", "arrows" to "flechas", "mark" to "marca", "button" to "botón",
		"circle" to "círculo", "square" to "cuadrado", "triangle" to "triángulo",
		"diamond" to "rombo", "kiss" to "beso", "hug" to "abrazo",
		"large" to "grande", "medium" to "medio",
		"play" to "reproducir", "pause" to "pausa", "stop" to "detener",
		"record" to "grabar", "eject" to "expulsar", "shuffle" to "aleatorio",
		"repeat" to "repetir", "track" to "pista", "previous" to "anterior",
		"next" to "siguiente", "forward" to "adelante", "backward" to "atrás",
		"fast" to "rápido", "timer" to "temporizador", "stopwatch" to "cronómetro",
		"watch" to "reloj", "keyboard" to "teclado", "printer" to "impresora",
		"fax" to "fax", "hourglass" to "reloj de arena", "information" to "información",
		"digit" to "dígito", "capital" to "mayúscula", "latin" to "latino",
		"letter" to "letra", "ideograph" to "ideograma", "squared" to "cuadrado",
		"unified" to "unificado", "enclosed" to "encerrado",
		"raising" to "levantando", "waving" to "saludando", "clapping" to "aplaudiendo",
		"pointing" to "apuntando", "love" to "amor", "thought" to "pensamiento",
		"speech" to "habla", "check" to "palomita", "cross" to "cruz",
		"warning" to "advertencia", "prohibited" to "prohibido", "recycling" to "reciclaje",
		"peace" to "paz", "nerd" to "empollona", "clown" to "payaso", "ghost" to "fantasma",
		"alien" to "extraterrestre", "robot" to "robot", "skull" to "calavera",
		"pill" to "pastilla", "microbe" to "microbio",
		"rolling" to "rodando", "flushed" to "sonrojada", "dizzy" to "mareada",
		"tongue" to "lengua", "sweat" to "sudor", "tear" to "lágrima", "tears" to "lágrimas",
		"pirate" to "pirata", "king" to "rey", "queen" to "reina", "angel" to "ángel",
		"prince" to "príncipe", "princess" to "princesa", "bride" to "novia",
		"frog" to "rana", "rabbit" to "conejo", "butterfly" to "mariposa",
		"bee" to "abeja", "snake" to "serpiente", "lion" to "león", "tiger" to "tigre",
		"wolf" to "lobo", "fox" to "zorro", "horse" to "caballo", "octopus" to "pulpo",
		"whale" to "ballena", "dolphin" to "delfín", "turtle" to "tortuga",
		"spider" to "araña", "shark" to "tiburón", "elephant" to "elefante",
		"wave" to "ola", "ocean" to "océano", "rainbow" to "arcoíris",
		"lightning" to "relámpago", "volcano" to "volcán", "mountain" to "montaña",
		"beach" to "playa", "island" to "isla", "desert" to "desierto",
		"guitar" to "guitarra", "drum" to "tambor", "violin" to "violín",
		"trumpet" to "trompeta", "microphone" to "micrófono", "song" to "canción",
		"music" to "música", "film" to "película", "cinema" to "cine",
		"party" to "fiesta", "birthday" to "cumpleaños", "christmas" to "navidad",
		"wedding" to "boda", "night" to "noche", "day" to "día",
		"morning" to "mañana", "evening" to "tarde", "spring" to "primavera",
		"summer" to "verano", "autumn" to "otoño", "winter" to "invierno",
		"strawberry" to "fresa", "orange" to "naranja", "lemon" to "limón",
		"peach" to "durazno", "cherry" to "cereza", "cherries" to "cerezas",
		"melon" to "melón", "pineapple" to "piña", "pear" to "pera",
		"coconut" to "coco", "egg" to "huevo", "milk" to "leche",
		"sandwich" to "sándwich", "salad" to "ensalada", "sushi" to "sushi",
		"bread" to "pan", "rice" to "arroz", "noodles" to "fideos",
		"cheese" to "queso", "cookie" to "galleta", "candy" to "dulce",
		"donut" to "dona", "taco" to "taco", "popcorn" to "palomitas",
		"truck" to "camión", "boat" to "bote", "bicycle" to "bicicleta",
		"taxi" to "taxi", "rocket" to "cohete", "helicopter" to "helicóptero",
		"sailboat" to "velero", "motorcycle" to "motocicleta",
		"umbrella" to "sombrilla", "flag" to "bandera", "globe" to "globo",
		"compass" to "brújula", "anchor" to "ancla", "map" to "mapa",
		"shirt" to "camisa", "pants" to "pantalones", "shoe" to "zapato",
		"hat" to "sombrero", "glasses" to "gafas", "tie" to "corbata",
		"bag" to "bolsa", "basket" to "cesta", "radio" to "radio",
		"ball" to "balón", "die" to "dado", "puzzle" to "rompecabezas",
		"kite" to "cometa", "pin" to "alfiler", "paper" to "papel",
		"pen" to "bolígrafo", "pencil" to "lápiz", "scissors" to "tijeras",
		"open" to "abierto", "closed" to "cerrado", "high" to "alto",
		"low" to "bajo", "long" to "largo", "short" to "corto",
		"slow" to "lento", "first" to "primero", "last" to "último",
		"full" to "lleno", "empty" to "vacío", "sign" to "letrero",
		"symbol" to "símbolo", "number" to "número", "letter" to "letra",
		"input" to "entrada", "shower" to "ducha", "bath" to "baño",
		"door" to "puerta", "window" to "ventana", "garden" to "jardín",
		"park" to "parque", "road" to "camino", "bridge" to "puente",
		"church" to "iglesia", "temple" to "templo", "school" to "escuela",
		"hospital" to "hospital", "bank" to "banco", "hotel" to "hotel",
		"office" to "oficina", "castle" to "castillo", "tent" to "carpa",
		"zombie" to "zombi", "vampire" to "vampiro", "fairy" to "hada",
		"mage" to "mago", "ninja" to "ninja", "superhero" to "superhéroe",
		"face" to "cara", "head" to "cabeza", "tooth" to "diente",
		"blood" to "sangre", "bone" to "hueso", "lips" to "labios",
		"hug" to "abrazo", "kiss" to "beso", "stars" to "estrellas",
		"clouds" to "nubes", "leaf" to "hoja", "rose" to "rosa",
		"children" to "niños", "women" to "mujeres", "men" to "hombres") + esWords2()

	private fun fraWords2(): Map<String, String> = mapOf(
		"a" to "un", "admission" to "admission", "alarm" to "alarme", "alien" to "extraterrestre", "ambulance" to "ambulance", "american" to "américain",
		"anguished" to "angoissé", "antenna" to "antenne", "arrows" to "flèches", "arts" to "arts", "astonished" to "étonné", "automobile" to "automobile",
		"back" to "dos", "backhand" to "revers", "bag" to "sac", "bank" to "banque", "barber" to "coiffeur", "bars" to "barres",
		"baseball" to "baseball", "basketball" to "basket", "battery" to "batterie", "beach" to "plage", "beaming" to "rayonnant", "beating" to "battant",
		"beer" to "bière", "beverage" to "boisson", "biceps" to "biceps", "bicycle" to "vélo", "birthday" to "anniversaire", "blond" to "blond",
		"blowing" to "soufflant", "books" to "livres", "bouquet" to "bouquet", "bowing" to "salut", "bowl" to "bol", "bowling" to "bowling",
		"briefcase" to "mallette", "broken" to "cassé", "bulb" to "ampoule", "calendar" to "calendrier", "call" to "appel", "camping" to "camping",
		"candy" to "bonbon", "cap" to "casquette", "card" to "carte", "cart" to "chariot", "castle" to "château", "chart" to "graphique",
		"cherries" to "cerises", "chicken" to "poulet", "chinese" to "chinois", "circus" to "cirque", "clapping" to "applaudissant", "clinking" to "trinquant",
		"clipboard" to "presse-papiers", "clockwise" to "sens horaire", "closed" to "fermé", "confused" to "confus", "construction" to "construction", "convenience" to "dépanneur",
		"cooked" to "cuit", "cookie" to "biscuit", "cooking" to "cuisine", "couple" to "couple", "cowboy" to "cowboy", "credit" to "crédit",
		"crescent" to "croissant", "croissant" to "croissant", "crossed" to "croisés", "crystal" to "cristal", "curl" to "boucle", "currency" to "devise",
		"dancers" to "danseurs", "dancing" to "danse", "decoration" to "décoration", "die" to "dé", "direct" to "direct", "disappointed" to "déçu",
		"dolphin" to "dauphin", "doughnut" to "beigne", "downcast" to "abattu", "dress" to "robe", "e-mail" to "courriel", "electric" to "électrique",
		"engine" to "moteur", "envelope" to "enveloppe", "european" to "européen", "evergreen" to "toujours vert", "exchange" to "échange", "exclamation" to "exclamation",
		"exploding" to "explosant", "expressionless" to "inexpressif", "factory" to "usine", "fast" to "rapide", "fast-forward" to "avance rapide", "fax" to "fax",
		"fear" to "peur", "fearful" to "craintif", "file" to "fichier", "fingers" to "doigts", "first" to "premier", "fist" to "poing",
		"flash" to "flash", "flexed" to "fléchi", "floor" to "sol", "folded" to "plié", "folder" to "dossier", "food" to "nourriture",
		"football" to "football", "fork" to "fourchette", "french" to "frites", "fries" to "frites", "from" to "de", "game" to "jeu",
		"gem" to "joyau", "gesture" to "geste", "gesturing" to "geste", "getting" to "soin", "ghost" to "fantôme", "glass" to "verre",
		"glowing" to "lumineux", "goblin" to "gobelin", "grapes" to "raisins", "grimacing" to "grimaçant", "growing" to "grandissant", "guardsman" to "garde",
		"hair" to "cheveux", "haircut" to "coupe", "halo" to "auréole", "hammer" to "marteau", "handle" to "poignée", "handshake" to "poignée de main",
		"hat" to "chapeau", "headphone" to "casque", "hear-no-evil" to "sourd au mal", "heart-eyes" to "yeux en cœur", "helicopter" to "hélicoptère", "high" to "haut",
		"high-heeled" to "à talons", "hit" to "coup", "honeybee" to "abeille", "horns" to "cornes", "hotel" to "hôtel", "hourglass" to "sablier",
		"hundred" to "cent", "increasing" to "en hausse", "index" to "index", "joy" to "joie", "kitchen" to "cuisine", "knife" to "couteau",
		"knocked-out" to "assommé", "laptop" to "portable", "last" to "dernier", "lemon" to "citron", "letter" to "lettre", "lipstick" to "rouge à lèvres",
		"locked" to "verrouillé",
		"lollipop" to "sucette", "loudly" to "fort", "loudspeaker" to "haut-parleur", "love-you" to "je t aime", "low" to "bas", "machine" to "machine",
		"magnifying" to "loupe", "mask" to "masque", "massage" to "massage", "me" to "moi", "medical" to "médical", "medium" to "moyen",
		"megaphone" to "mégaphone", "memo" to "note", "microscope" to "microscope", "middle" to "majeur", "mobile" to "portable", "mode" to "mode",
		"monocle" to "monocle", "mountain" to "montagne", "mug" to "chope", "mugs" to "chopes", "musical" to "musical", "muted" to "muet",
		"nail" to "ongle", "national" to "national", "nerd" to "intello", "neutral" to "neutre", "newspaper" to "journal", "next" to "suivant",
		"no" to "non", "note" to "note", "notebook" to "carnet", "notes" to "notes", "off" to "éteint", "ogre" to "ogre",
		"older" to "aîné", "oncoming" to "approchant", "package" to "colis", "page" to "page", "paperclip" to "trombone", "park" to "parc",
		"partying" to "fête", "paw" to "patte", "peach" to "pêche", "pensive" to "pensif", "performing" to "spectacle", "phones" to "téléphones",
		"pile" to "tas", "pill" to "pilule", "pineapple" to "ananas", "place" to "place", "plug" to "prise", "pointing" to "pointant",
		"points" to "points", "pole" to "poteau", "police" to "police", "polish" to "vernis", "poo" to "caca", "pool" to "billard",
		"popcorn" to "pop-corn", "popper" to "pétard", "poultry" to "volaille", "princess" to "princesse", "prints" to "empreintes", "prohibited" to "interdit",
		"pushpin" to "punaise", "question" to "question", "radio" to "radio", "raised" to "levé", "raising" to "levant", "rays" to "rayons",
		"receiver" to "combiné", "recycling" to "recyclage", "reverse" to "retour", "revolving" to "tournants", "rice" to "riz", "rocket" to "fusée",
		"rose" to "rose", "running" to "course", "sailboat" to "voilier", "salad" to "salade", "salute" to "salut", "satellite" to "satellite",
		"savoring" to "savourant", "school" to "école", "screaming" to "hurlant", "scroll" to "parchemin", "see-no-evil" to "aveugle au mal", "selfie" to "égoportrait",
		"shoe" to "chaussure", "shopping" to "magasinage", "shortcake" to "gâteau", "shuffle" to "aléatoire", "sign" to "panneau", "skull" to "crâne",
		"sleepy" to "endormi", "slightly" to "légèrement", "smirking" to "narquois", "snow-capped" to "enneigé", "snowflake" to "flocon", "soccer" to "soccer",
		"sparkling" to "étincelant", "speak-no-evil" to "muet au mal", "speaker" to "haut-parleur", "splayed" to "écartés", "sports" to "sport", "spouting" to "soufflant",
		"squinting" to "plissant", "stadium" to "stade", "steam" to "vapeur", "steaming" to "fumant", "stone" to "pierre", "stopwatch" to "chronomètre",
		"store" to "magasin", "strawberry" to "fraise", "sunflower" to "tournesol", "sunglasses" to "lunettes de soleil", "surfing" to "surf", "swimming" to "nage",
		"symbol" to "symbole", "syringe" to "seringue", "tangerine" to "mandarine", "teacup" to "tasse", "tears" to "larmes", "telephone" to "téléphone",
		"telescope" to "télescope", "television" to "télévision", "tennis" to "tennis", "tent" to "tente", "the" to "le", "thought" to "pensée",
		"thumbs" to "pouces", "tickets" to "billets", "timer" to "minuteur", "tipping" to "renseignement", "tired" to "fatigué", "tracks" to "pistes",
		"turban" to "turban", "two" to "deux", "unamused" to "blasé", "unlocked" to "déverrouillé", "upside-down" to "à l envers", "vertical" to "vertical",
		"vibration" to "vibration", "victory" to "victoire", "video" to "vidéo", "volleyball" to "volleyball", "voltage" to "tension", "volume" to "volume",
		"vulcan" to "vulcain", "walking" to "marche", "watermelon" to "melon d eau", "waving" to "salut", "wearing" to "portant", "weary" to "épuisé",
		"wedding" to "mariage", "wine" to "vin", "wings" to "ailes", "without" to "sans", "wolf" to "loup", "worker" to "ouvrier",
		"worried" to "inquiet", "wrapped" to "emballé", "zzz" to "dodo"
	)
	private fun deuWords2(): Map<String, String> = mapOf(
		"a" to "ein", "admission" to "Aufnahme", "alarm" to "Alarm", "alien" to "Außerirdischer", "ambulance" to "Krankenwagen", "american" to "amerikanisch",
		"anguished" to "gequält", "antenna" to "Antenne", "arrows" to "Pfeile", "arts" to "Kunst", "astonished" to "erstaunt", "automobile" to "Auto",
		"back" to "Rücken", "backhand" to "Rückhand", "bag" to "Tasche", "bank" to "Bank", "barber" to "Friseur", "bars" to "Balken",
		"baseball" to "Baseball", "basketball" to "Basketball", "battery" to "Batterie", "beach" to "Strand", "beaming" to "strahlend", "beating" to "schlagend",
		"beer" to "Bier", "beverage" to "Getränk", "biceps" to "Bizeps", "bicycle" to "Fahrrad", "birthday" to "Geburtstag", "blond" to "blond",
		"blowing" to "blasend", "books" to "Bücher", "bouquet" to "Strauß", "bowing" to "Verbeugung", "bowl" to "Schüssel", "bowling" to "Bowling",
		"briefcase" to "Aktentasche", "broken" to "gebrochen", "bulb" to "Birne", "calendar" to "Kalender", "call" to "Anruf", "camping" to "Camping",
		"candy" to "Bonbon", "cap" to "Kappe", "card" to "Karte", "cart" to "Wagen", "castle" to "Schloss", "chart" to "Tabelle",
		"cherries" to "Kirschen", "chicken" to "Hähnchen", "chinese" to "chinesisch", "circus" to "Zirkus", "clapping" to "klatschend", "clinking" to "anstoßend",
		"clipboard" to "Klemmbrett", "clockwise" to "im Uhrzeigersinn", "closed" to "geschlossen", "confused" to "verwirrt", "construction" to "Bau", "convenience" to "Kiosk",
		"cooked" to "gekocht", "cookie" to "Keks", "cooking" to "Kochen", "couple" to "Paar", "cowboy" to "Cowboy", "credit" to "Kredit",
		"crescent" to "Halbmond", "croissant" to "Croissant", "crossed" to "gekreuzt", "crystal" to "Kristall", "curl" to "Locke", "currency" to "Währung",
		"dancers" to "Tänzer", "dancing" to "Tanz", "decoration" to "Deko", "die" to "Würfel", "direct" to "direkt", "disappointed" to "enttäuscht",
		"dolphin" to "Delfin", "doughnut" to "Donut", "downcast" to "niedergeschlagen", "dress" to "Kleid", "e-mail" to "E-Mail", "electric" to "elektrisch",
		"engine" to "Motor", "envelope" to "Umschlag", "european" to "europäisch", "evergreen" to "immergrün", "exchange" to "Wechsel", "exclamation" to "Ausruf",
		"exploding" to "explodierend", "expressionless" to "ausdruckslos", "factory" to "Fabrik", "fast" to "schnell", "fast-forward" to "Vorspulen", "fax" to "Fax",
		"fear" to "Angst", "fearful" to "ängstlich", "file" to "Datei", "fingers" to "Finger", "first" to "erste", "fist" to "Faust",
		"flash" to "Blitz", "flexed" to "angespannt", "floor" to "Boden", "folded" to "gefaltet", "folder" to "Ordner", "food" to "Essen",
		"football" to "Football", "fork" to "Gabel", "french" to "Pommes", "fries" to "Fritten", "from" to "von", "game" to "Spiel",
		"gem" to "Edelstein", "gesture" to "Geste", "gesturing" to "Geste", "getting" to "Pflege", "ghost" to "Geist", "glass" to "Glas",
		"glowing" to "leuchtend", "goblin" to "Goblin", "grapes" to "Trauben", "grimacing" to "grimassierend", "growing" to "wachsend", "guardsman" to "Gardist",
		"hair" to "Haare", "haircut" to "Haarschnitt", "halo" to "Heiligenschein", "hammer" to "Hammer", "handle" to "Griff", "handshake" to "Händedruck",
		"hat" to "Hut", "headphone" to "Kopfhörer", "hear-no-evil" to "taub für Böses", "heart-eyes" to "Herzaugen", "helicopter" to "Hubschrauber", "high" to "hoch",
		"high-heeled" to "mit Absätzen", "hit" to "Treffer", "honeybee" to "Honigbiene", "horns" to "Hörner", "hourglass" to "Sanduhr", "hundred" to "hundert",
		"increasing" to "steigend", "index" to "Zeigefinger", "joy" to "Freude", "kitchen" to "Küche", "knife" to "Messer", "knocked-out" to "k. o.",
		"laptop" to "Laptop", "last" to "letzte", "lemon" to "Zitrone", 			"letter" to "Brief", "lipstick" to "Lippenstift", "locked" to "abgeschlossen",
		"lollipop" to "Lolli", "loudly" to "laut", "loudspeaker" to "Lautsprecher", "love-you" to "ich liebe dich", "low" to "niedrig", "machine" to "Maschine",
		"magnifying" to "Lupe", "mask" to "Maske", "massage" to "Massage", "me" to "mich", "medical" to "medizinisch", "medium" to "mittel",
		"megaphone" to "Megafon", "memo" to "Notiz", "microscope" to "Mikroskop", "middle" to "Mittel", "mobile" to "Handy", "mode" to "Modus",
		"monocle" to "Monokel", "mountain" to "Berg", "mug" to "Becher", "mugs" to "Becher", "musical" to "Musik", "muted" to "stumm",
		"nail" to "Nagel", "national" to "National", "nerd" to "Streber", "neutral" to "neutral", "newspaper" to "Zeitung", "next" to "nächste",
		"no" to "nein", "note" to "Note", "notebook" to "Notizbuch", "notes" to "Noten", "off" to "aus", "ogre" to "Oger",
		"older" to "älter", "oncoming" to "ankommend", "package" to "Paket", "page" to "Seite", "paperclip" to "Büroklammer", "park" to "Park",
		"partying" to "Party", "paw" to "Pfote", "peach" to "Pfirsich", "pensive" to "nachdenklich", "performing" to "Aufführung", "phones" to "Telefone",
		"pile" to "Haufen", "pill" to "Pille", "pineapple" to "Ananas", "place" to "Platz", "plug" to "Stecker", "pointing" to "zeigend",
		"points" to "Punkte", "pole" to "Stange", "police" to "Polizei", "polish" to "Lack", "poo" to "Kacke", "pool" to "Billard",
		"popcorn" to "Popcorn", "popper" to "Knaller", "poultry" to "Geflügel", "princess" to "Prinzessin", "prints" to "Abdrücke", "prohibited" to "verboten",
		"pushpin" to "Pinnnadel", "question" to "Frage", "radio" to "Radio", "raised" to "erhoben", "raising" to "hebend", "rays" to "Strahlen",
		"receiver" to "Hörer", "recycling" to "Recycling", "reverse" to "zurück", "revolving" to "drehend", "rice" to "Reis", "robot" to "Roboter",
		"rocket" to "Rakete", "rose" to "Rose", "running" to "Laufen", "sailboat" to "Segelboot", "salad" to "Salat", "salute" to "Gruß",
		"satellite" to "Satellit", "savoring" to "genießend", "school" to "Schule", "screaming" to "schreiend", "scroll" to "Schriftrolle", "see-no-evil" to "blind für Böses",
		"selfie" to "Selfie", "shoe" to "Schuh", "shopping" to "Einkaufen", "shortcake" to "Törtchen", "shuffle" to "Zufällig", "sign" to "Schild",
		"skull" to "Schädel", "sleepy" to "schläfrig", "slightly" to "leicht", "smirking" to "grinsend", "snow-capped" to "schneebedeckt", "snowflake" to "Schneeflocke",
		"soccer" to "Fußball", "sparkling" to "funkelnd", "speak-no-evil" to "stumm für Böses", "speaker" to "Lautsprecher", "splayed" to "gespreizt", "sports" to "Sport",
		"spouting" to "spritzend", "squinting" to "blinzelnd", "stadium" to "Stadion", "steam" to "Dampf", "steaming" to "dampfend", "stone" to "Stein",
		"stopwatch" to "Stoppuhr", "store" to "Laden", "strawberry" to "Erdbeere", "sunflower" to "Sonnenblume", "sunglasses" to "Sonnenbrille", "surfing" to "Surfen",
		"swimming" to "Schwimmen", "symbol" to "Symbol", "syringe" to "Spritze", "tangerine" to "Mandarine", "teacup" to "Teetasse", "tears" to "Tränen",
		"telephone" to "Telefon", "telescope" to "Teleskop", "television" to "Fernseher", "tennis" to "Tennis", "tent" to "Zelt", "the" to "der",
		"thought" to "Gedanke", "thumbs" to "Daumen", "tickets" to "Tickets", "timer" to "Timer", "tipping" to "Trinkgeld", "tired" to "müde",
		"tracks" to "Spuren", "turban" to "Turban", "two" to "zwei", "unamused" to "genervt", "unlocked" to "offen", "upside-down" to "auf dem Kopf",
		"vertical" to "senkrecht", "vibration" to "Vibration", "victory" to "Sieg", "video" to "Video", "volleyball" to "Volleyball", "voltage" to "Spannung",
		"volume" to "Lautstärke", "vulcan" to "Vulkanier", "walking" to "Gehen", "watermelon" to "Wassermelone", "waving" to "Winken", "wearing" to "tragend",
		"weary" to "erschöpft", "wedding" to "Hochzeit", "wine" to "Wein", "wings" to "Flügel", "without" to "ohne", "wolf" to "Wolf",
		"worker" to "Arbeiter", "worried" to "besorgt", "wrapped" to "verpackt", "zzz" to "schnarch"
	)
	private fun itaWords2(): Map<String, String> = mapOf(
		"a" to "un", "admission" to "ammissione", "alarm" to "allarme", "alien" to "alieno", "ambulance" to "ambulanza", "american" to "americano",
		"anguished" to "angosciato", "antenna" to "antenna", "arrows" to "frecce", "arts" to "arti", "astonished" to "stupito", "automobile" to "automobile",
		"back" to "schiena", "backhand" to "rovescio", "bag" to "borsa", "bank" to "banca", "barber" to "barbiere", "bars" to "sbarre",
		"baseball" to "baseball", "basketball" to "pallacanestro", "battery" to "batteria", "beach" to "spiaggia", "beaming" to "raggiante", "beating" to "battente",
		"beer" to "birra", "beverage" to "bevanda", "biceps" to "bicipite", "bicycle" to "bicicletta", "birthday" to "compleanno", "blond" to "biondo",
		"blowing" to "soffiante", "books" to "libri", "bouquet" to "mazzo", "bowing" to "inchino", "bowl" to "ciotola", "bowling" to "bowling",
		"briefcase" to "valigetta", "broken" to "rotto", "bulb" to "lampadina", "calendar" to "calendario", "call" to "chiamata", "camping" to "campeggio",
		"candy" to "caramella", "cap" to "berretto", "card" to "carta", "cart" to "carrello", "castle" to "castello", "chart" to "grafico",
		"cherries" to "ciliegie", "chicken" to "pollo", "chinese" to "cinese", "circus" to "circo", "clapping" to "applaudente", "clinking" to "brindante",
		"clipboard" to "portablocco", "clockwise" to "senso orario", "closed" to "chiuso", "confused" to "confuso", "construction" to "cantiere", "convenience" to "minimarket",
		"cooked" to "cotto", "cookie" to "biscotto", "cooking" to "cucina", "couple" to "coppia", "cowboy" to "cowboy", "credit" to "credito",
		"crescent" to "mezzaluna", "croissant" to "cornetto", "crossed" to "incrociate", "crystal" to "cristallo", "curl" to "ricciolo", "currency" to "valuta",
		"dancers" to "ballerini", "dancing" to "danza", "decoration" to "decorazione", "die" to "dado", "direct" to "diretto", "disappointed" to "deluso",
		"dolphin" to "delfino", "doughnut" to "ciambella", "downcast" to "abbattuto", "dress" to "vestito", "e-mail" to "email", "electric" to "elettrico",
		"engine" to "motore", "envelope" to "busta", "european" to "europeo", "evergreen" to "sempreverde", "exchange" to "cambio", "exclamation" to "esclamazione",
		"exploding" to "esplosiva", "expressionless" to "inespressiva", "factory" to "fabbrica", "fast" to "veloce", "fast-forward" to "avanti veloce", "fax" to "fax",
		"fear" to "paura", "fearful" to "timoroso", "file" to "file", "fingers" to "dita", "first" to "primo", "fist" to "pugno",
		"flash" to "flash", "flexed" to "flesso", "floor" to "pavimento", "folded" to "piegate", "folder" to "cartella", "food" to "cibo",
		"football" to "football", "fork" to "forchetta", "french" to "patatine", "fries" to "fritte", "from" to "da", "game" to "gioco",
		"gem" to "gemma", "gesture" to "gesto", "gesturing" to "gesto", "getting" to "cura", "ghost" to "fantasma", "glass" to "bicchiere",
		"glowing" to "luminoso", "goblin" to "goblin", "grapes" to "uva", "grimacing" to "smorfia", "growing" to "crescente", "guardsman" to "guardia",
		"hair" to "capelli", "haircut" to "taglio", "halo" to "aureola", "hammer" to "martello", "handle" to "manico", "handshake" to "stretta di mano",
		"hat" to "cappello", "headphone" to "cuffia", "hear-no-evil" to "sordo al male", "heart-eyes" to "occhi a cuore", "helicopter" to "elicottero", "high" to "alto",
		"high-heeled" to "col tacco", "hit" to "colpo", "honeybee" to "ape", "horns" to "corna", "hourglass" to "clessidra", "hundred" to "cento",
		"increasing" to "in aumento", "index" to "indice", "joker" to "jolly", "joy" to "gioia", "kitchen" to "cucina", "knife" to "coltello",
		"knocked-out" to "stordito", "laptop" to "portatile", "last" to "ultimo", "lemon" to "limone", "letter" to "lettera", "lipstick" to "rossetto",
		"locked" to "chiuso",
		"lollipop" to "lecca-lecca", "loudly" to "forte", "loudspeaker" to "altoparlante", "love-you" to "ti amo", "low" to "basso", "machine" to "macchina",
		"magnifying" to "lente", "mask" to "maschera", "massage" to "massaggio", "me" to "me", "medical" to "medico", "medium" to "medio",
		"megaphone" to "megafono", "memo" to "nota", "microscope" to "microscopio", "middle" to "medio", "mobile" to "cellulare", "mode" to "modalità",
		"monocle" to "monocolo", "mountain" to "montagna", "mug" to "boccale", "mugs" to "boccali", "musical" to "musicale", "muted" to "muto",
		"nail" to "unghia", "national" to "nazionale", "nerd" to "secchione", "neutral" to "neutro", "newspaper" to "giornale", "next" to "prossimo",
		"no" to "no", "note" to "nota", "notebook" to "quaderno", "notes" to "note", "off" to "spento", "ogre" to "orco",
		"older" to "maggiore", "oncoming" to "in arrivo", "package" to "pacco", "page" to "pagina", "paperclip" to "graffetta", "park" to "parco",
		"partying" to "festa", "paw" to "zampa", "peach" to "pesca", "pensive" to "pensieroso", "performing" to "spettacolo", "phones" to "telefoni",
		"pile" to "mucchio", "pill" to "pillola", "pineapple" to "ananas", "place" to "posto", "plug" to "spina", "pointing" to "indicante",
		"points" to "punti", "pole" to "palo", "police" to "polizia", "polish" to "smalto", "poo" to "cacca", "pool" to "biliardo",
		"popcorn" to "popcorn", "popper" to "petardo", "poultry" to "pollame", "princess" to "principessa", "prints" to "impronte", "prohibited" to "vietato",
		"pushpin" to "puntina", "question" to "domanda", "radio" to "radio", "raised" to "alzata", "raising" to "alzante", "rays" to "raggi",
		"receiver" to "cornetta", "recycling" to "riciclo", "reverse" to "indietro", "revolving" to "girevoli", "rice" to "riso", "rocket" to "razzo",
		"rose" to "rosa", "running" to "corsa", "sailboat" to "barca a vela", "salad" to "insalata", "salute" to "saluto", "satellite" to "satellite",
		"savoring" to "gustando", "school" to "scuola", "screaming" to "urlante", "scroll" to "pergamena", "see-no-evil" to "cieco al male", "selfie" to "selfie",
		"shoe" to "scarpa", "shopping" to "spesa", "shortcake" to "tortina", "shuffle" to "casuale", "sign" to "cartello", "skull" to "teschio",
		"sleepy" to "assonnato", "slightly" to "leggermente", "smirking" to "sogghigno", "snow-capped" to "innevata", "snowflake" to "fiocco di neve", "soccer" to "calcio",
		"sparkling" to "scintillante", "speak-no-evil" to "muto al male", "speaker" to "altoparlante", "splayed" to "divaricate", "sports" to "sport", "spouting" to "spruzzante",
		"squinting" to "strizzante", "stadium" to "stadio", "steam" to "vapore", "steaming" to "fumante", "stone" to "pietra", "stopwatch" to "cronometro",
		"store" to "negozio", "strawberry" to "fragola", "sunflower" to "girasole", "sunglasses" to "occhiali da sole", "surfing" to "surf", "swimming" to "nuoto",
		"symbol" to "simbolo", "syringe" to "siringa", "tangerine" to "mandarino", "teacup" to "tazzina", "tears" to "lacrime", "telephone" to "telefono",
		"telescope" to "telescopio", "television" to "televisione", "tennis" to "tennis", "tent" to "tenda", "the" to "il", "thought" to "pensiero",
		"thumbs" to "pollici", "tickets" to "biglietti", "timer" to "timer", "tipping" to "mancia", "tired" to "stanco", "tracks" to "tracce",
		"turban" to "turbante", "two" to "due", "unamused" to "annoiato", "unlocked" to "aperto", "upside-down" to "sottosopra", "vertical" to "verticale",
		"vibration" to "vibrazione", "victory" to "vittoria", "video" to "video", "volleyball" to "pallavolo", "voltage" to "tensione", "volume" to "volume",
		"vulcan" to "vulcaniano", "walking" to "camminata", "watermelon" to "anguria", "waving" to "ciao", "wearing" to "indossante", "weary" to "sfinito",
		"wedding" to "matrimonio", "wine" to "vino", "wings" to "ali", "without" to "senza", "wolf" to "lupo", "worker" to "operaio",
		"worried" to "preoccupato", "wrapped" to "incartato", "zzz" to "ronf"
	)
	private fun esWords2(): Map<String, String> = mapOf(
	"a" to "un", "admission" to "admisión", "alarm" to "alarma", "ambulance" to "ambulancia", "american" to "americano", "anguished" to "angustiado",
	"antenna" to "antena", "arts" to "artes", "astonished" to "asombrado", "automobile" to "automóvil", "back" to "espalda", "backhand" to "revés",
	"barber" to "peluquero", "bars" to "barras", "baseball" to "béisbol", "basketball" to "baloncesto", "battery" to "batería", "beaming" to "radiante",
	"beating" to "latiendo", "beer" to "cerveza", "beverage" to "bebida", "biceps" to "bíceps", "blond" to "rubio", "blowing" to "soplando",
	"books" to "libros", "bouquet" to "ramo", "bowing" to "reverencia", "bowl" to "tazón", "bowling" to "boliche", "brain" to "cerebro", "briefcase" to "portafolio",
	"broken" to "roto", "bulb" to "foco", "calendar" to "calendario", "call" to "llamada", "camping" to "campamento", "cap" to "gorra",
	"card" to "tarjeta", "cart" to "carrito", "chart" to "gráfica", "chicken" to "pollo", "chinese" to "chino", "circus" to "circo",
	"clinking" to "brindando", "clipboard" to "portapapeles", "clockwise" to "sentido horario", "cocktail" to "cóctel", "confused" to "confundido", "construction" to "construcción",
	"convenience" to "tienda", "cooked" to "cocido", "cooking" to "cocina", "couple" to "pareja", "cowboy" to "vaquero", "credit" to "crédito",
	"crescent" to "luna creciente", "croissant" to "cruasán", "crossed" to "cruzados", "crystal" to "cristal", "curl" to "rizo", "currency" to "moneda",
	"dancers" to "bailarines", "dancing" to "bailando", "decoration" to "decoración", "direct" to "directo", "disappointed" to "decepcionado", "doughnut" to "dona",
	"downcast" to "abatido", "dress" to "vestido", "e-mail" to "correo", "electric" to "eléctrico", "engine" to "motor", "envelope" to "sobre",
	"european" to "europeo", "evergreen" to "siempre verde", "exchange" to "cambio", "exclamation" to "exclamación", "exploding" to "explotando", "expressionless" to "inexpresivo",
	"factory" to "fábrica", "fast-forward" to "adelanto rápido", "fear" to "miedo", "fearful" to "temeroso", "file" to "archivo", "fingers" to "dedos",
	"fist" to "puño", "flash" to "destello", "flexed" to "flexionado", "floor" to "suelo", "folded" to "doblado", "folder" to "carpeta",
	"food" to "comida", "football" to "fútbol americano", "fork" to "tenedor", "french" to "papas", "fries" to "fritas", "from" to "de",
	"game" to "juego", "gem" to "gema", "gesture" to "gesto", "gesturing" to "gesto", "getting" to "cuidado", "glass" to "vaso",
	"glowing" to "brillante", "goblin" to "duende", "grapes" to "uvas", "grimacing" to "mueca", "growing" to "creciente", "guardsman" to "guardia",
	"hair" to "cabello", "haircut" to "corte", "halo" to "aureola", "hammer" to "martillo", "handle" to "mango", "handshake" to "apretón",
	"headphone" to "audífono", "hear-no-evil" to "sordo al mal", "heart-eyes" to "ojos de corazón", "high-heeled" to "de tacón", "hit" to "golpe",
	"honeybee" to "abeja", "horns" to "cuernos", "hundred" to "cien", "increasing" to "subiendo", "index" to "índice", "joker" to "comodín",
	"joy" to "alegría", "kitchen" to "cocina", "knife" to "cuchillo", "knocked-out" to "noqueado", "laptop" to "portátil", "lipstick" to "labial",
	"locked" to "cerrado",
	"lollipop" to "paleta", "loudly" to "fuerte", "loudspeaker" to "altavoz", "love-you" to "te quiero", "machine" to "máquina", "magnifying" to "lupa",
	"mask" to "mascarilla", "massage" to "masaje", "me" to "me", "medical" to "médico", "megaphone" to "megáfono", "memo" to "nota",
	"microscope" to "microscopio", "middle" to "medio", "mobile" to "móvil", "mode" to "modo", "monocle" to "monóculo", "mug" to "tarro",
	"mugs" to "tarros", "musical" to "musical", "muted" to "silenciado", "nail" to "uña", "national" to "nacional", "neutral" to "neutral",
	"newspaper" to "periódico", "no" to "no", "note" to "nota", "notebook" to "cuaderno", "notes" to "notas", "off" to "apagado",
	"ogre" to "ogro", "ok" to "ok", "older" to "mayor", "oncoming" to "próximo", "package" to "paquete", "page" to "página",
	"paperclip" to "clip", "partying" to "fiesta", "paw" to "pata", "pensive" to "pensativo", "performing" to "función", "phones" to "teléfonos",
	"pile" to "montón", "place" to "lugar", "plug" to "enchufe", "points" to "puntos", "pole" to "poste", "police" to "policía",
	"polish" to "esmalte", "poo" to "caca", "pool" to "billar", "popper" to "petardo", "poultry" to "aves", "prints" to "huellas",
	"pushpin" to "chincheta", "question" to "pregunta", "raised" to "levantada", "rays" to "rayos", "receiver" to "auricular", "reverse" to "reversa",
	"revolving" to "giratorios", "running" to "corriendo", "salute" to "saludo", "satellite" to "satélite", "savoring" to "saboreando", "screaming" to "gritando",
	"scroll" to "pergamino", "see-no-evil" to "ciego al mal", "selfie" to "selfi", "shopping" to "compras", "shortcake" to "pastelito", "sleepy" to "soñoliento",
	"slightly" to "levemente", "smirking" to "sonrisita", "snow-capped" to "nevado", "snowflake" to "copo", "soccer" to "fútbol", "sparkling" to "brillante",
	"speak-no-evil" to "mudo al mal", "speaker" to "bocina", "splayed" to "abiertos", "sports" to "deportes", "spouting" to "chorro", "squinting" to "entrecerrados",
	"stadium" to "estadio", "steam" to "vapor", "steaming" to "humeante", "stone" to "piedra", "store" to "tienda", "sunflower" to "girasol",
	"sunglasses" to "gafas de sol", "surfing" to "surf", "swimming" to "natación", "syringe" to "jeringa", "tangerine" to "mandarina", "teacup" to "taza",
	"telephone" to "teléfono", "telescope" to "telescopio", "television" to "televisión", "tennis" to "tenis", "the" to "el", "thumbs" to "pulgares",
	"tickets" to "boletos", "tipping" to "propina", "tired" to "cansado", "tracks" to "pistas", "turban" to "turbante", "two" to "dos",
	"unamused" to "aburrido", "unlocked" to "abierto", "upside-down" to "al revés", "vertical" to "vertical", "vibration" to "vibración", "victory" to "victoria",
	"video" to "video", "volleyball" to "voleibol", "voltage" to "voltaje", "volume" to "volumen", "vulcan" to "vulcano", "walking" to "caminando",
	"watermelon" to "sandía", "wearing" to "llevando", "weary" to "cansado", "wine" to "vino", "wings" to "alas", "without" to "sin",
	"worker" to "obrero", "worried" to "preocupado", "wrapped" to "envuelto", "zzz" to "zzz"
	)

	/** Traducción por palabras del inglés para diez idiomas del
	 *  motor. Clave en minúsculas, como sale del nombre Unicode.
	 *  Español, francés, alemán e italiano cubren todo el
	 *  vocabulario de los emojis comunes, así cada emoji se dice
	 *  detallado en todos los idiomas del motor; lo que no está
	 *  aquí queda en inglés, que al menos se oye. */
	private val WORDS: Map<String, Map<String, String>> = mapOf(
		"fra" to (fraWords2() + mapOf(
			"smiling" to "souriant", "grinning" to "souriant", "laughing" to "riant",
			"crying" to "pleurant", "sleeping" to "dormant", "thinking" to "pensant",
			"winking" to "clin d œil", "kissing" to "bise", "hugging" to "câlin",
			"frowning" to "mécontent", "pouting" to "boudant", "rolling" to "roulant",
			"flushed" to "rougi", "dizzy" to "étourdi", "tongue" to "langue",
			"sweat" to "sueur", "face" to "visage", "head" to "tête",
			"eyes" to "yeux", "eye" to "œil", "ear" to "oreille", "nose" to "nez",
			"mouth" to "bouche", "hand" to "main", "hands" to "mains",
			"finger" to "doigt", "thumb" to "pouce", "foot" to "pied",
			"leg" to "jambe", "brain" to "cerveau", "kiss" to "baiser", "hug" to "câlin",
			"man" to "homme", "woman" to "femme", "person" to "personne",
			"people" to "gens", "child" to "enfant", "baby" to "bébé",
			"boy" to "garçon", "girl" to "fille", "old" to "vieux", "young" to "jeune",
			"king" to "roi", "queen" to "reine", "angel" to "ange", "pirate" to "pirate",
			"cat" to "chat", "dog" to "chien", "mouse" to "souris", "bear" to "ours",
			"panda" to "panda", "lion" to "lion", "tiger" to "tigre", "monkey" to "singe",
			"pig" to "cochon", "cow" to "vache", "bird" to "oiseau", "penguin" to "pingouin",
			"fish" to "poisson", "whale" to "baleine", "frog" to "grenouille",
			"rabbit" to "lapin", "horse" to "cheval", "snake" to "serpent",
			"octopus" to "poulpe", "butterfly" to "papillon", "bee" to "abeille",
			"heart" to "cœur", "hearts" to "cœurs", "star" to "étoile",
			"sun" to "soleil", "moon" to "lune", "cloud" to "nuage",
			"rain" to "pluie", "snow" to "neige", "fire" to "feu", "water" to "eau",
			"tree" to "arbre", "flower" to "fleur", "rainbow" to "arc-en-ciel",
			"house" to "maison", "car" to "voiture", "bus" to "bus", "train" to "train",
			"airplane" to "avion", "ship" to "navire", "boat" to "bateau",
			"phone" to "téléphone", "computer" to "ordinateur", "camera" to "caméra",
			"book" to "livre", "money" to "argent", "key" to "clé", "lock" to "serrure",
			"gift" to "cadeau", "cake" to "gâteau", "balloon" to "ballon",
			"trophy" to "trophée", "medal" to "médaille", "crown" to "couronne",
			"ring" to "bague", "bell" to "cloche", "clock" to "horloge",
			"light" to "lumière", "red" to "rouge", "orange" to "orange",
			"yellow" to "jaune", "green" to "vert", "blue" to "bleu",
			"purple" to "violet", "black" to "noir", "white" to "blanc",
			"brown" to "marron", "with" to "avec", "and" to "et", "of" to "de",
			"on" to "sur", "in" to "dans", "up" to "haut", "down" to "bas",
			"left" to "gauche", "right" to "droite", "new" to "nouveau",
			"big" to "grand", "small" to "petit", "hot" to "chaud", "cold" to "froid",
			"happy" to "heureux", "sad" to "triste", "angry" to "en colère",
			"arrow" to "flèche", "mark" to "marque", "button" to "bouton",
			"circle" to "cercle", "square" to "carré", "check" to "coche",
			"cross" to "croix", "peace" to "paix", "love" to "amour",
			"speech" to "parole", "play" to "lecture", "pause" to "pause",
			"stop" to "arrêt", "record" to "enregistrement", "repeat" to "répétition",
			"track" to "piste", "music" to "musique", "film" to "film",
			"party" to "fête", "night" to "nuit", "day" to "jour",
			"apple" to "pomme", "bread" to "pain", "cheese" to "fromage",
			"coffee" to "café", "egg" to "œuf", "milk" to "lait",
			"flag" to "drapeau", "umbrella" to "parapluie", "ball" to "ballon",
			"open" to "ouvert", "wave" to "vague", "ocean" to "océan")),
		"deu" to (deuWords2() + mapOf(
			"smiling" to "lächelnd", "grinning" to "grinsend", "laughing" to "lachend",
			"crying" to "weinend", "sleeping" to "schlafend", "thinking" to "nachdenklich",
			"winking" to "zwinkernd", "kissing" to "Kuss", "hugging" to "Umarmung",
			"frowning" to "missmutig", "pouting" to "schmollend", "rolling" to "rollend",
			"flushed" to "errötet", "dizzy" to "schwindlig", "tongue" to "Zunge",
			"sweat" to "Schweiß", "face" to "Gesicht", "head" to "Kopf",
			"eyes" to "Augen", "eye" to "Auge", "ear" to "Ohr", "nose" to "Nase",
			"mouth" to "Mund", "hand" to "Hand", "hands" to "Hände",
			"finger" to "Finger", "thumb" to "Daumen", "foot" to "Fuß",
			"leg" to "Bein", "brain" to "Gehirn", "kiss" to "Kuss", "hug" to "Umarmung",
			"man" to "Mann", "woman" to "Frau", "person" to "Person",
			"people" to "Leute", "child" to "Kind", "baby" to "Baby",
			"boy" to "Junge", "girl" to "Mädchen", "old" to "alt", "young" to "jung",
			"king" to "König", "queen" to "Königin", "angel" to "Engel", "pirate" to "Pirat",
			"cat" to "Katze", "dog" to "Hund", "mouse" to "Maus", "bear" to "Bär",
			"panda" to "Panda", "lion" to "Löwe", "tiger" to "Tiger", "monkey" to "Affe",
			"pig" to "Schwein", "cow" to "Kuh", "bird" to "Vogel", "penguin" to "Pinguin",
			"fish" to "Fisch", "whale" to "Wal", "frog" to "Frosch",
			"rabbit" to "Hase", "horse" to "Pferd", "snake" to "Schlange",
			"octopus" to "Krake", "butterfly" to "Schmetterling", "bee" to "Biene",
			"heart" to "Herz", "hearts" to "Herzen", "star" to "Stern",
			"sun" to "Sonne", "moon" to "Mond", "cloud" to "Wolke",
			"rain" to "Regen", "snow" to "Schnee", "fire" to "Feuer", "water" to "Wasser",
			"tree" to "Baum", "flower" to "Blume", "rainbow" to "Regenbogen",
			"house" to "Haus", "car" to "Auto", "bus" to "Bus", "train" to "Zug",
			"airplane" to "Flugzeug", "ship" to "Schiff", "boat" to "Boot",
			"phone" to "Telefon", "computer" to "Computer", "camera" to "Kamera",
			"book" to "Buch", "money" to "Geld", "key" to "Schlüssel", "lock" to "Schloss",
			"gift" to "Geschenk", "cake" to "Kuchen", "balloon" to "Luftballon",
			"trophy" to "Pokal", "medal" to "Medaille", "crown" to "Krone",
			"ring" to "Ring", "bell" to "Glocke", "clock" to "Uhr",
			"light" to "Licht", "red" to "rot", "orange" to "orange",
			"yellow" to "gelb", "green" to "grün", "blue" to "blau",
			"purple" to "lila", "black" to "schwarz", "white" to "weiß",
			"brown" to "braun", "with" to "mit", "and" to "und", "of" to "von",
			"on" to "auf", "in" to "in", "up" to "hoch", "down" to "runter",
			"left" to "links", "right" to "rechts", "new" to "neu",
			"big" to "groß", "small" to "klein", "hot" to "heiß", "cold" to "kalt",
			"happy" to "glücklich", "sad" to "traurig", "angry" to "wütend",
			"arrow" to "Pfeil", "mark" to "Zeichen", "button" to "Knopf",
			"circle" to "Kreis", "square" to "Quadrat", "check" to "Häkchen",
			"cross" to "Kreuz", "peace" to "Frieden", "love" to "Liebe",
			"speech" to "Rede", "play" to "Abspielen", "pause" to "Pause",
			"stop" to "Stopp", "record" to "Aufnahme", "repeat" to "Wiederholung",
			"track" to "Titel", "music" to "Musik", "film" to "Film",
			"party" to "Party", "night" to "Nacht", "day" to "Tag",
			"apple" to "Apfel", "bread" to "Brot", "cheese" to "Käse",
			"coffee" to "Kaffee", "egg" to "Ei", "milk" to "Milch",
			"flag" to "Flagge", "umbrella" to "Regenschirm", "ball" to "Ball",
			"open" to "offen", "wave" to "Welle", "ocean" to "Ozean")),
		"ita" to (itaWords2() + mapOf(
			"smiling" to "sorridente", "grinning" to "sorridente", "laughing" to "ridente",
			"crying" to "piangente", "sleeping" to "dormiente", "thinking" to "pensierosa",
			"winking" to "ammiccante", "kissing" to "bacio", "hugging" to "abbraccio",
			"frowning" to "corrucciata", "pouting" to "imbronciata", "rolling" to "rotolante",
			"flushed" to "arrossita", "dizzy" to "stordita", "tongue" to "lingua",
			"sweat" to "sudore", "face" to "faccia", "head" to "testa",
			"eyes" to "occhi", "eye" to "occhio", "ear" to "orecchio", "nose" to "naso",
			"mouth" to "bocca", "hand" to "mano", "hands" to "mani",
			"finger" to "dito", "thumb" to "pollice", "foot" to "piede",
			"leg" to "gamba", "brain" to "cervello", "kiss" to "bacio", "hug" to "abbraccio",
			"man" to "uomo", "woman" to "donna", "person" to "persona",
			"people" to "persone", "child" to "bambino", "baby" to "neonato",
			"boy" to "ragazzo", "girl" to "ragazza", "old" to "vecchio", "young" to "giovane",
			"king" to "re", "queen" to "regina", "angel" to "angelo", "pirate" to "pirata",
			"cat" to "gatto", "dog" to "cane", "mouse" to "topo", "bear" to "orso",
			"panda" to "panda", "lion" to "leone", "tiger" to "tigre", "monkey" to "scimmia",
			"pig" to "maiale", "cow" to "mucca", "bird" to "uccello", "penguin" to "pinguino",
			"fish" to "pesce", "whale" to "balena", "frog" to "rana",
			"rabbit" to "coniglio", "horse" to "cavallo", "snake" to "serpente",
			"octopus" to "polpo", "butterfly" to "farfalla", "bee" to "ape",
			"heart" to "cuore", "hearts" to "cuori", "star" to "stella",
			"sun" to "sole", "moon" to "luna", "cloud" to "nuvola",
			"rain" to "pioggia", "snow" to "neve", "fire" to "fuoco", "water" to "acqua",
			"tree" to "albero", "flower" to "fiore", "rainbow" to "arcobaleno",
			"house" to "casa", "car" to "auto", "bus" to "autobus", "train" to "treno",
			"airplane" to "aereo", "ship" to "nave", "boat" to "barca",
			"phone" to "telefono", "computer" to "computer", "camera" to "fotocamera",
			"book" to "libro", "money" to "soldi", "key" to "chiave", "lock" to "lucchetto",
			"gift" to "regalo", "cake" to "torta", "balloon" to "palloncino",
			"trophy" to "trofeo", "medal" to "medaglia", "crown" to "corona",
			"ring" to "anello", "bell" to "campana", "clock" to "orologio",
			"light" to "luce", "red" to "rosso", "orange" to "arancione",
			"yellow" to "giallo", "green" to "verde", "blue" to "blu",
			"purple" to "viola", "black" to "nero", "white" to "bianco",
			"brown" to "marrone", "with" to "con", "and" to "e", "of" to "di",
			"on" to "su", "in" to "in", "up" to "su", "down" to "giù",
			"left" to "sinistra", "right" to "destra", "new" to "nuovo",
			"big" to "grande", "small" to "piccolo", "hot" to "caldo", "cold" to "freddo",
			"happy" to "felice", "sad" to "triste", "angry" to "arrabbiata",
			"arrow" to "freccia", "mark" to "segno", "button" to "pulsante",
			"circle" to "cerchio", "square" to "quadrato", "check" to "spunta",
			"cross" to "croce", "peace" to "pace", "love" to "amore",
			"speech" to "discorso", "play" to "riproduci", "pause" to "pausa",
			"stop" to "stop", "record" to "registra", "repeat" to "ripeti",
			"track" to "brano", "music" to "musica", "film" to "film",
			"party" to "festa", "night" to "notte", "day" to "giorno",
			"apple" to "mela", "bread" to "pane", "cheese" to "formaggio",
			"coffee" to "caffè", "egg" to "uovo", "milk" to "latte",
			"flag" to "bandiera", "umbrella" to "ombrello", "ball" to "palla",
			"open" to "aperto", "wave" to "onda", "ocean" to "oceano")),
		"por" to mapOf(
			"smiling" to "sorridente", "grinning" to "sorrindo", "laughing" to "rindo",
			"crying" to "chorando", "sleeping" to "dormindo", "thinking" to "pensativo",
			"winking" to "piscando", "kissing" to "beijo", "hugging" to "abraço",
			"frowning" to "triste", "pouting" to "emburrado", "rolling" to "rolando",
			"flushed" to "corado", "dizzy" to "tonto", "tongue" to "língua",
			"sweat" to "suor", "face" to "rosto", "head" to "cabeça",
			"eyes" to "olhos", "eye" to "olho", "ear" to "orelha", "nose" to "nariz",
			"mouth" to "boca", "hand" to "mão", "hands" to "mãos",
			"finger" to "dedo", "thumb" to "polegar", "foot" to "pé",
			"leg" to "perna", "brain" to "cérebro", "kiss" to "beijo", "hug" to "abraço",
			"man" to "homem", "woman" to "mulher", "person" to "pessoa",
			"people" to "pessoas", "child" to "criança", "baby" to "bebê",
			"boy" to "menino", "girl" to "menina", "old" to "velho", "young" to "jovem",
			"king" to "rei", "queen" to "rainha", "angel" to "anjo", "pirate" to "pirata",
			"cat" to "gato", "dog" to "cachorro", "mouse" to "rato", "bear" to "urso",
			"panda" to "panda", "lion" to "leão", "tiger" to "tigre", "monkey" to "macaco",
			"pig" to "porco", "cow" to "vaca", "bird" to "pássaro", "penguin" to "pinguim",
			"fish" to "peixe", "whale" to "baleia", "frog" to "sapo",
			"rabbit" to "coelho", "horse" to "cavalo", "snake" to "cobra",
			"octopus" to "polvo", "butterfly" to "borboleta", "bee" to "abelha",
			"heart" to "coração", "hearts" to "corações", "star" to "estrela",
			"sun" to "sol", "moon" to "lua", "cloud" to "nuvem",
			"rain" to "chuva", "snow" to "neve", "fire" to "fogo", "water" to "água",
			"tree" to "árvore", "flower" to "flor", "rainbow" to "arco-íris",
			"house" to "casa", "car" to "carro", "bus" to "ônibus", "train" to "trem",
			"airplane" to "avião", "ship" to "navio", "boat" to "barco",
			"phone" to "telefone", "computer" to "computador", "camera" to "câmera",
			"book" to "livro", "money" to "dinheiro", "key" to "chave", "lock" to "cadeado",
			"gift" to "presente", "cake" to "bolo", "balloon" to "balão",
			"trophy" to "troféu", "medal" to "medalha", "crown" to "coroa",
			"ring" to "anel", "bell" to "sino", "clock" to "relógio",
			"light" to "luz", "red" to "vermelho", "orange" to "laranja",
			"yellow" to "amarelo", "green" to "verde", "blue" to "azul",
			"purple" to "roxo", "black" to "preto", "white" to "branco",
			"brown" to "marrom", "with" to "com", "and" to "e", "of" to "de",
			"on" to "em", "in" to "em", "up" to "para cima", "down" to "para baixo",
			"left" to "esquerda", "right" to "direita", "new" to "novo",
			"big" to "grande", "small" to "pequeno", "hot" to "quente", "cold" to "frio",
			"happy" to "feliz", "sad" to "triste", "angry" to "bravo",
			"arrow" to "seta", "mark" to "marca", "button" to "botão",
			"circle" to "círculo", "square" to "quadrado", "check" to "certo",
			"cross" to "xis", "peace" to "paz", "love" to "amor",
			"speech" to "fala", "play" to "tocar", "pause" to "pausa",
			"stop" to "parar", "record" to "gravar", "repeat" to "repetir",
			"track" to "faixa", "music" to "música", "film" to "filme",
			"party" to "festa", "night" to "noite", "day" to "dia",
			"apple" to "maçã", "bread" to "pão", "cheese" to "queijo",
			"coffee" to "café", "egg" to "ovo", "milk" to "leite",
			"flag" to "bandeira", "umbrella" to "guarda-chuva", "ball" to "bola",
			"open" to "aberto", "wave" to "onda", "ocean" to "oceano"
		),
		"pol" to mapOf(
			"smiling" to "uśmiechnięta", "grinning" to "uśmiechnięta", "laughing" to "śmiejąca",
			"crying" to "płacząca", "sleeping" to "śpiąca", "thinking" to "zamyślona",
			"winking" to "mrugająca", "kissing" to "całująca", "hugging" to "przytulająca",
			"frowning" to "niezadowolona", "pouting" to "nadąsana", "rolling" to "turlająca",
			"flushed" to "zarumieniona", "dizzy" to "oszołomiona", "tongue" to "język",
			"sweat" to "pot", "face" to "twarz", "head" to "głowa",
			"eyes" to "oczy", "eye" to "oko", "ear" to "ucho", "nose" to "nos",
			"mouth" to "usta", "hand" to "ręka", "hands" to "ręce",
			"finger" to "palec", "thumb" to "kciuk", "foot" to "stopa",
			"leg" to "noga", "brain" to "mózg", "kiss" to "pocałunek", "hug" to "uścisk",
			"man" to "mężczyzna", "woman" to "kobieta", "person" to "osoba",
			"people" to "ludzie", "child" to "dziecko", "baby" to "niemowlę",
			"boy" to "chłopiec", "girl" to "dziewczynka", "old" to "stary", "young" to "młody",
			"king" to "król", "queen" to "królowa", "angel" to "anioł", "pirate" to "pirat",
			"cat" to "kot", "dog" to "pies", "mouse" to "mysz", "bear" to "niedźwiedź",
			"panda" to "panda", "lion" to "lew", "tiger" to "tygrys", "monkey" to "małpa",
			"pig" to "świnia", "cow" to "krowa", "bird" to "ptak", "penguin" to "pingwin",
			"fish" to "ryba", "whale" to "wieloryb", "frog" to "żaba",
			"rabbit" to "królik", "horse" to "koń", "snake" to "wąż",
			"octopus" to "ośmiornica", "butterfly" to "motyl", "bee" to "pszczoła",
			"heart" to "serce", "hearts" to "serca", "star" to "gwiazda",
			"sun" to "słońce", "moon" to "księżyc", "cloud" to "chmura",
			"rain" to "deszcz", "snow" to "śnieg", "fire" to "ogień", "water" to "woda",
			"tree" to "drzewo", "flower" to "kwiat", "rainbow" to "tęcza",
			"house" to "dom", "car" to "samochód", "bus" to "autobus", "train" to "pociąg",
			"airplane" to "samolot", "ship" to "statek", "boat" to "łódź",
			"phone" to "telefon", "computer" to "komputer", "camera" to "aparat",
			"book" to "książka", "money" to "pieniądze", "key" to "klucz", "lock" to "zamek",
			"gift" to "prezent", "cake" to "tort", "balloon" to "balon",
			"trophy" to "trofeum", "medal" to "medal", "crown" to "korona",
			"ring" to "pierścionek", "bell" to "dzwonek", "clock" to "zegar",
			"light" to "światło", "red" to "czerwony", "orange" to "pomarańczowy",
			"yellow" to "żółty", "green" to "zielony", "blue" to "niebieski",
			"purple" to "fioletowy", "black" to "czarny", "white" to "biały",
			"brown" to "brązowy", "with" to "z", "and" to "i", "of" to "z",
			"on" to "na", "in" to "w", "up" to "w górę", "down" to "w dół",
			"left" to "lewo", "right" to "prawo", "new" to "nowy",
			"big" to "duży", "small" to "mały", "hot" to "gorący", "cold" to "zimny",
			"happy" to "szczęśliwa", "sad" to "smutna", "angry" to "zła",
			"arrow" to "strzałka", "mark" to "znak", "button" to "przycisk",
			"circle" to "koło", "square" to "kwadrat", "check" to "ptaszek",
			"cross" to "krzyżyk", "peace" to "pokój", "love" to "miłość",
			"speech" to "mowa", "play" to "odtwórz", "pause" to "pauza",
			"stop" to "stop", "record" to "nagraj", "repeat" to "powtórz",
			"track" to "utwór", "music" to "muzyka", "film" to "film",
			"party" to "impreza", "night" to "noc", "day" to "dzień",
			"apple" to "jabłko", "bread" to "chleb", "cheese" to "ser",
			"coffee" to "kawa", "egg" to "jajko", "milk" to "mleko",
			"flag" to "flaga", "umbrella" to "parasol", "ball" to "piłka",
			"open" to "otwarta", "wave" to "fala", "ocean" to "ocean"
		),
		"nld" to mapOf(
			"smiling" to "glimlachend", "grinning" to "grijnzend", "laughing" to "lachend",
			"crying" to "huilend", "sleeping" to "slapend", "thinking" to "nadenkend",
			"winking" to "knipogend", "kissing" to "kussend", "hugging" to "knuffelend",
			"frowning" to "fronsend", "pouting" to "pruilend", "rolling" to "rollend",
			"flushed" to "blozend", "dizzy" to "duizelig", "tongue" to "tong",
			"sweat" to "zweet", "face" to "gezicht", "head" to "hoofd",
			"eyes" to "ogen", "eye" to "oog", "ear" to "oor", "nose" to "neus",
			"mouth" to "mond", "hand" to "hand", "hands" to "handen",
			"finger" to "vinger", "thumb" to "duim", "foot" to "voet",
			"leg" to "been", "brain" to "hersenen", "kiss" to "kus", "hug" to "knuffel",
			"man" to "man", "woman" to "vrouw", "person" to "persoon",
			"people" to "mensen", "child" to "kind", "baby" to "baby",
			"boy" to "jongen", "girl" to "meisje", "old" to "oud", "young" to "jong",
			"king" to "koning", "queen" to "koningin", "angel" to "engel", "pirate" to "piraat",
			"cat" to "kat", "dog" to "hond", "mouse" to "muis", "bear" to "beer",
			"panda" to "panda", "lion" to "leeuw", "tiger" to "tijger", "monkey" to "aap",
			"pig" to "varken", "cow" to "koe", "bird" to "vogel", "penguin" to "pinguïn",
			"fish" to "vis", "whale" to "walvis", "frog" to "kikker",
			"rabbit" to "konijn", "horse" to "paard", "snake" to "slang",
			"octopus" to "octopus", "butterfly" to "vlinder", "bee" to "bij",
			"heart" to "hart", "hearts" to "harten", "star" to "ster",
			"sun" to "zon", "moon" to "maan", "cloud" to "wolk",
			"rain" to "regen", "snow" to "sneeuw", "fire" to "vuur", "water" to "water",
			"tree" to "boom", "flower" to "bloem", "rainbow" to "regenboog",
			"house" to "huis", "car" to "auto", "bus" to "bus", "train" to "trein",
			"airplane" to "vliegtuig", "ship" to "schip", "boat" to "boot",
			"phone" to "telefoon", "computer" to "computer", "camera" to "camera",
			"book" to "boek", "money" to "geld", "key" to "sleutel", "lock" to "slot",
			"gift" to "cadeau", "cake" to "taart", "balloon" to "ballon",
			"trophy" to "trofee", "medal" to "medaille", "crown" to "kroon",
			"ring" to "ring", "bell" to "bel", "clock" to "klok",
			"light" to "licht", "red" to "rood", "orange" to "oranje",
			"yellow" to "geel", "green" to "groen", "blue" to "blauw",
			"purple" to "paars", "black" to "zwart", "white" to "wit",
			"brown" to "bruin", "with" to "met", "and" to "en", "of" to "van",
			"on" to "op", "in" to "in", "up" to "omhoog", "down" to "omlaag",
			"left" to "links", "right" to "rechts", "new" to "nieuw",
			"big" to "groot", "small" to "klein", "hot" to "heet", "cold" to "koud",
			"happy" to "blij", "sad" to "verdrietig", "angry" to "boos",
			"arrow" to "pijl", "mark" to "teken", "button" to "knop",
			"circle" to "cirkel", "square" to "vierkant", "check" to "vinkje",
			"cross" to "kruis", "peace" to "vrede", "love" to "liefde",
			"speech" to "spraak", "play" to "afspelen", "pause" to "pauze",
			"stop" to "stop", "record" to "opnemen", "repeat" to "herhalen",
			"track" to "nummer", "music" to "muziek", "film" to "film",
			"party" to "feest", "night" to "nacht", "day" to "dag",
			"apple" to "appel", "bread" to "brood", "cheese" to "kaas",
			"coffee" to "koffie", "egg" to "ei", "milk" to "melk",
			"flag" to "vlag", "umbrella" to "paraplu", "ball" to "bal",
			"open" to "open", "wave" to "golf", "ocean" to "oceaan"
		),
		"swe" to mapOf(
			"smiling" to "leende", "grinning" to "flinande", "laughing" to "skrattande",
			"crying" to "gråtande", "sleeping" to "sovande", "thinking" to "funderande",
			"winking" to "blinkande", "kissing" to "kyssande", "hugging" to "kramande",
			"frowning" to "missnöjd", "pouting" to "surmulen", "rolling" to "rullande",
			"flushed" to "rodnande", "dizzy" to "snurrig", "tongue" to "tunga",
			"sweat" to "svett", "face" to "ansikte", "head" to "huvud",
			"eyes" to "ögon", "eye" to "öga", "ear" to "öra", "nose" to "näsa",
			"mouth" to "mun", "hand" to "hand", "hands" to "händer",
			"finger" to "finger", "thumb" to "tumme", "foot" to "fot",
			"leg" to "ben", "brain" to "hjärna", "kiss" to "kyss", "hug" to "kram",
			"man" to "man", "woman" to "kvinna", "person" to "person",
			"people" to "människor", "child" to "barn", "baby" to "bebis",
			"boy" to "pojke", "girl" to "flicka", "old" to "gammal", "young" to "ung",
			"king" to "kung", "queen" to "drottning", "angel" to "ängel", "pirate" to "pirat",
			"cat" to "katt", "dog" to "hund", "mouse" to "mus", "bear" to "björn",
			"panda" to "panda", "lion" to "lejon", "tiger" to "tiger", "monkey" to "apa",
			"pig" to "gris", "cow" to "ko", "bird" to "fågel", "penguin" to "pingvin",
			"fish" to "fisk", "whale" to "val", "frog" to "groda",
			"rabbit" to "kanin", "horse" to "häst", "snake" to "orm",
			"octopus" to "bläckfisk", "butterfly" to "fjäril", "bee" to "bi",
			"heart" to "hjärta", "hearts" to "hjärtan", "star" to "stjärna",
			"sun" to "sol", "moon" to "måne", "cloud" to "moln",
			"rain" to "regn", "snow" to "snö", "fire" to "eld", "water" to "vatten",
			"tree" to "träd", "flower" to "blomma", "rainbow" to "regnbåge",
			"house" to "hus", "car" to "bil", "bus" to "buss", "train" to "tåg",
			"airplane" to "flygplan", "ship" to "skepp", "boat" to "båt",
			"phone" to "telefon", "computer" to "dator", "camera" to "kamera",
			"book" to "bok", "money" to "pengar", "key" to "nyckel", "lock" to "lås",
			"gift" to "present", "cake" to "tårta", "balloon" to "ballong",
			"trophy" to "pokal", "medal" to "medalj", "crown" to "krona",
			"ring" to "ring", "bell" to "klocka", "clock" to "klocka",
			"light" to "ljus", "red" to "röd", "orange" to "orange",
			"yellow" to "gul", "green" to "grön", "blue" to "blå",
			"purple" to "lila", "black" to "svart", "white" to "vit",
			"brown" to "brun", "with" to "med", "and" to "och", "of" to "av",
			"on" to "på", "in" to "i", "up" to "upp", "down" to "ner",
			"left" to "vänster", "right" to "höger", "new" to "ny",
			"big" to "stor", "small" to "liten", "hot" to "varm", "cold" to "kall",
			"happy" to "glad", "sad" to "ledsen", "angry" to "arg",
			"arrow" to "pil", "mark" to "märke", "button" to "knapp",
			"circle" to "cirkel", "square" to "fyrkant", "check" to "bock",
			"cross" to "kryss", "peace" to "fred", "love" to "kärlek",
			"speech" to "tal", "play" to "spela", "pause" to "paus",
			"stop" to "stopp", "record" to "spela in", "repeat" to "repetera",
			"track" to "spår", "music" to "musik", "film" to "film",
			"party" to "fest", "night" to "natt", "day" to "dag",
			"apple" to "äpple", "bread" to "bröd", "cheese" to "ost",
			"coffee" to "kaffe", "egg" to "ägg", "milk" to "mjölk",
			"flag" to "flagga", "umbrella" to "paraply", "ball" to "boll",
			"open" to "öppen", "wave" to "våg", "ocean" to "hav"
		),
		"nor" to mapOf(
			"smiling" to "smilende", "grinning" to "glisende", "laughing" to "latter",
			"crying" to "gråtende", "sleeping" to "sovende", "thinking" to "tenkende",
			"winking" to "blinkende", "kissing" to "kyssende", "hugging" to "klemmende",
			"frowning" to "misfornøyd", "pouting" to "surmulende", "rolling" to "rullende",
			"flushed" to "rødmende", "dizzy" to "svimmel", "tongue" to "tunge",
			"sweat" to "svette", "face" to "ansikt", "head" to "hode",
			"eyes" to "øyne", "eye" to "øye", "ear" to "øre", "nose" to "nese",
			"mouth" to "munn", "hand" to "hånd", "hands" to "hender",
			"finger" to "finger", "thumb" to "tommel", "foot" to "fot",
			"leg" to "bein", "brain" to "hjerne", "kiss" to "kyss", "hug" to "klem",
			"man" to "mann", "woman" to "kvinne", "person" to "person",
			"people" to "folk", "child" to "barn", "baby" to "baby",
			"boy" to "gutt", "girl" to "jente", "old" to "gammel", "young" to "ung",
			"king" to "konge", "queen" to "dronning", "angel" to "engel", "pirate" to "pirat",
			"cat" to "katt", "dog" to "hund", "mouse" to "mus", "bear" to "bjørn",
			"panda" to "panda", "lion" to "løve", "tiger" to "tiger", "monkey" to "ape",
			"pig" to "gris", "cow" to "ku", "bird" to "fugl", "penguin" to "pingvin",
			"fish" to "fisk", "whale" to "hval", "frog" to "frosk",
			"rabbit" to "kanin", "horse" to "hest", "snake" to "slange",
			"octopus" to "blekksprut", "butterfly" to "sommerfugl", "bee" to "bie",
			"heart" to "hjerte", "hearts" to "hjerter", "star" to "stjerne",
			"sun" to "sol", "moon" to "måne", "cloud" to "sky",
			"rain" to "regn", "snow" to "snø", "fire" to "ild", "water" to "vann",
			"tree" to "tre", "flower" to "blomst", "rainbow" to "regnbue",
			"house" to "hus", "car" to "bil", "bus" to "buss", "train" to "tog",
			"airplane" to "fly", "ship" to "skip", "boat" to "båt",
			"phone" to "telefon", "computer" to "datamaskin", "camera" to "kamera",
			"book" to "bok", "money" to "penger", "key" to "nøkkel", "lock" to "lås",
			"gift" to "gave", "cake" to "kake", "balloon" to "ballong",
			"trophy" to "pokal", "medal" to "medalje", "crown" to "krone",
			"ring" to "ring", "bell" to "bjelle", "clock" to "klokke",
			"light" to "lys", "red" to "rød", "orange" to "oransje",
			"yellow" to "gul", "green" to "grønn", "blue" to "blå",
			"purple" to "lilla", "black" to "svart", "white" to "hvit",
			"brown" to "brun", "with" to "med", "and" to "og", "of" to "av",
			"on" to "på", "in" to "i", "up" to "opp", "down" to "ned",
			"left" to "venstre", "right" to "høyre", "new" to "ny",
			"big" to "stor", "small" to "liten", "hot" to "varm", "cold" to "kald",
			"happy" to "glad", "sad" to "trist", "angry" to "sint",
			"arrow" to "pil", "mark" to "merke", "button" to "knapp",
			"circle" to "sirkel", "square" to "firkant", "check" to "hake",
			"cross" to "kryss", "peace" to "fred", "love" to "kjærlighet",
			"speech" to "tale", "play" to "spill", "pause" to "pause",
			"stop" to "stopp", "record" to "ta opp", "repeat" to "gjenta",
			"track" to "spor", "music" to "musikk", "film" to "film",
			"party" to "fest", "night" to "natt", "day" to "dag",
			"apple" to "eple", "bread" to "brød", "cheese" to "ost",
			"coffee" to "kaffe", "egg" to "egg", "milk" to "melk",
			"flag" to "flagg", "umbrella" to "paraply", "ball" to "ball",
			"open" to "åpen", "wave" to "bølge", "ocean" to "hav"
		),
		"dan" to mapOf(
			"smiling" to "smilende", "grinning" to "grinende", "laughing" to "grinende",
			"crying" to "grædende", "sleeping" to "sovende", "thinking" to "tænkende",
			"winking" to "blinkende", "kissing" to "kyssende", "hugging" to "krammende",
			"frowning" to "utilfreds", "pouting" to "surmulende", "rolling" to "rullende",
			"flushed" to "rødmende", "dizzy" to "svimmel", "tongue" to "tunge",
			"sweat" to "sved", "face" to "ansigt", "head" to "hoved",
			"eyes" to "øjne", "eye" to "øje", "ear" to "øre", "nose" to "næse",
			"mouth" to "mund", "hand" to "hånd", "hands" to "hænder",
			"finger" to "finger", "thumb" to "tommelfinger", "foot" to "fod",
			"leg" to "ben", "brain" to "hjerne", "kiss" to "kys", "hug" to "kram",
			"man" to "mand", "woman" to "kvinde", "person" to "person",
			"people" to "folk", "child" to "barn", "baby" to "baby",
			"boy" to "dreng", "girl" to "pige", "old" to "gammel", "young" to "ung",
			"king" to "konge", "queen" to "dronning", "angel" to "engel", "pirate" to "pirat",
			"cat" to "kat", "dog" to "hund", "mouse" to "mus", "bear" to "bjørn",
			"panda" to "panda", "lion" to "løve", "tiger" to "tiger", "monkey" to "abe",
			"pig" to "gris", "cow" to "ko", "bird" to "fugl", "penguin" to "pingvin",
			"fish" to "fisk", "whale" to "hval", "frog" to "frø",
			"rabbit" to "kanin", "horse" to "hest", "snake" to "slange",
			"octopus" to "blæksprutte", "butterfly" to "sommerfugl", "bee" to "bi",
			"heart" to "hjerte", "hearts" to "hjerter", "star" to "stjerne",
			"sun" to "sol", "moon" to "måne", "cloud" to "sky",
			"rain" to "regn", "snow" to "sne", "fire" to "ild", "water" to "vand",
			"tree" to "træ", "flower" to "blomst", "rainbow" to "regnbue",
			"house" to "hus", "car" to "bil", "bus" to "bus", "train" to "tog",
			"airplane" to "flyvemaskine", "ship" to "skib", "boat" to "båd",
			"phone" to "telefon", "computer" to "computer", "camera" to "kamera",
			"book" to "bog", "money" to "penge", "key" to "nøgle", "lock" to "lås",
			"gift" to "gave", "cake" to "kage", "balloon" to "ballon",
			"trophy" to "pokal", "medal" to "medalje", "crown" to "krone",
			"ring" to "ring", "bell" to "klokke", "clock" to "ur",
			"light" to "lys", "red" to "rød", "orange" to "orange",
			"yellow" to "gul", "green" to "grøn", "blue" to "blå",
			"purple" to "lilla", "black" to "sort", "white" to "hvid",
			"brown" to "brun", "with" to "med", "and" to "og", "of" to "af",
			"on" to "på", "in" to "i", "up" to "op", "down" to "ned",
			"left" to "venstre", "right" to "højre", "new" to "ny",
			"big" to "stor", "small" to "lille", "hot" to "varm", "cold" to "kold",
			"happy" to "glad", "sad" to "ked", "angry" to "vred",
			"arrow" to "pil", "mark" to "mærke", "button" to "knap",
			"circle" to "cirkel", "square" to "firkant", "check" to "flueben",
			"cross" to "kryds", "peace" to "fred", "love" to "kærlighed",
			"speech" to "tale", "play" to "afspil", "pause" to "pause",
			"stop" to "stop", "record" to "optag", "repeat" to "gentag",
			"track" to "nummer", "music" to "musik", "film" to "film",
			"party" to "fest", "night" to "nat", "day" to "dag",
			"apple" to "æble", "bread" to "brød", "cheese" to "ost",
			"coffee" to "kaffe", "egg" to "æg", "milk" to "mælk",
			"flag" to "flag", "umbrella" to "paraply", "ball" to "bold",
			"open" to "åben", "wave" to "bølge", "ocean" to "hav"
		),
		"fin" to mapOf(
			"smiling" to "hymyilevä", "grinning" to "virnistävä", "laughing" to "naurava",
			"crying" to "itkevä", "sleeping" to "nukkuva", "thinking" to "miettivä",
			"winking" to "silmää iskevä", "kissing" to "suukottava", "hugging" to "halaava",
			"frowning" to "nyrpeä", "pouting" to "mököttävä", "rolling" to "pyörivä",
			"flushed" to "punastuva", "dizzy" to "pyörryttävä", "tongue" to "kieli",
			"sweat" to "hiki", "face" to "naama", "head" to "pää",
			"eyes" to "silmät", "eye" to "silmä", "ear" to "korva", "nose" to "nenä",
			"mouth" to "suu", "hand" to "käsi", "hands" to "kädet",
			"finger" to "sormi", "thumb" to "peukalo", "foot" to "jalka",
			"leg" to "sääri", "brain" to "aivot", "kiss" to "suukko", "hug" to "halaus",
			"man" to "mies", "woman" to "nainen", "person" to "henkilö",
			"people" to "ihmiset", "child" to "lapsi", "baby" to "vauva",
			"boy" to "poika", "girl" to "tyttö", "old" to "vanha", "young" to "nuori",
			"king" to "kuningas", "queen" to "kuningatar", "angel" to "enkeli", "pirate" to "merirosvo",
			"cat" to "kissa", "dog" to "koira", "mouse" to "hiiri", "bear" to "karhu",
			"panda" to "panda", "lion" to "leijona", "tiger" to "tiikeri", "monkey" to "apina",
			"pig" to "sika", "cow" to "lehmä", "bird" to "lintu", "penguin" to "pingviini",
			"fish" to "kala", "whale" to "valas", "frog" to "sammakko",
			"rabbit" to "kani", "horse" to "hevonen", "snake" to "käärme",
			"octopus" to "mustekala", "butterfly" to "perhonen", "bee" to "mehiläinen",
			"heart" to "sydän", "hearts" to "sydämet", "star" to "tähti",
			"sun" to "aurinko", "moon" to "kuu", "cloud" to "pilvi",
			"rain" to "sade", "snow" to "lumi", "fire" to "tuli", "water" to "vesi",
			"tree" to "puu", "flower" to "kukka", "rainbow" to "sateenkaari",
			"house" to "talo", "car" to "auto", "bus" to "bussi", "train" to "juna",
			"airplane" to "lentokone", "ship" to "laiva", "boat" to "vene",
			"phone" to "puhelin", "computer" to "tietokone", "camera" to "kamera",
			"book" to "kirja", "money" to "raha", "key" to "avain", "lock" to "lukko",
			"gift" to "lahja", "cake" to "kakku", "balloon" to "ilmapallo",
			"trophy" to "pokaali", "medal" to "mitali", "crown" to "kruunu",
			"ring" to "sormus", "bell" to "kello", "clock" to "kello",
			"light" to "valo", "red" to "punainen", "orange" to "oranssi",
			"yellow" to "keltainen", "green" to "vihreä", "blue" to "sininen",
			"purple" to "violetti", "black" to "musta", "white" to "valkoinen",
			"brown" to "ruskea", "with" to "kanssa", "and" to "ja", "of" to "n",
			"on" to "päällä", "in" to "ssa", "up" to "ylös", "down" to "alas",
			"left" to "vasen", "right" to "oikea", "new" to "uusi",
			"big" to "iso", "small" to "pieni", "hot" to "kuuma", "cold" to "kylmä",
			"happy" to "iloinen", "sad" to "surullinen", "angry" to "vihainen",
			"arrow" to "nuoli", "mark" to "merkki", "button" to "nappi",
			"circle" to "ympyrä", "square" to "neliö", "check" to "rasti",
			"cross" to "rasti", "peace" to "rauha", "love" to "rakkaus",
			"speech" to "puhe", "play" to "soita", "pause" to "tauko",
			"stop" to "seis", "record" to "nauhoita", "repeat" to "toista",
			"track" to "kappale", "music" to "musiikki", "film" to "elokuva",
			"party" to "juhla", "night" to "yö", "day" to "päivä",
			"apple" to "omena", "bread" to "leipä", "cheese" to "juusto",
			"coffee" to "kahvi", "egg" to "muna", "milk" to "maito",
			"flag" to "lippu", "umbrella" to "sateenvarjo", "ball" to "pallo",
			"open" to "auki", "wave" to "aalto", "ocean" to "meri"
		)
	)
}
