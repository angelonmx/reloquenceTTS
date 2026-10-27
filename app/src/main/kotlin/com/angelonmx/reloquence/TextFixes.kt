package com.angelonmx.reloquence

/** Texto que el motor lee mal, puesto correcto antes de que
 *  lo vea.
 *
 *  Eloquencia se rinde en una palabra que no puede analizar y
 *  la deletrea en su lugar, una letra a la vez. Por eso "teamtalk5"
 *  vuelve como t e a m t a l k 5: el dígito final hace que todo el
 *  token sea iranalizable, así que todo el token se deletrea. Un espacio
 *  delante del dígito es suficiente para arreglarlo.
 *
 *  La mayoría de estos son de davidacm, del controlador NVDA
 *  IBMTTS's ibm_global_fixes. Cada uno fue medido contra el motor
 *  primero, porque una regla que se lee bien puede costar más de
 *  lo que ahorra. Dos del controlador fueron omitidos:
 *  el par que colapsa los dobles espacios alrededor de los
 *  paréntesis, que existen porque el procesamiento de símbolos
 *  propio de NVDA los pone ahí y el de TalkBack no.
 *
 *  Esto corre antes de Pauses y Prosody, y tiene que ser así.
 *  Esos añaden anotaciones como `` `p1 ``, y una regla aquí que
 *  pone un espacio entre una letra y un dígito convertiría eso en
 *  `` `p 1 ``, que el motor pronuncia en lugar de obedece.
 */
object TextFixes {

	/**
	 * [text] con las palabras que el motor maneja mal reescritas.
	 * Nada aquí cambia lo que se dice, solo cómo está escrito
	 * para el motor. Estilo JAWS: el texto entra al motor tal
	 * cual, sin inventar marcas ni puntuación.
	 *
	 * [lang] activa las reglas de pronunciación fija del español:
	 * la letra y, "muay thai", los grados y los enteros con
	 * decimales. Sin idioma se quedan fuera, que es lo que usan
	 * las llamadas antiguas y las pruebas.
	 */
	fun apply(text: String, lang: String = ""): String {
		if (text.isEmpty()) return text
		var out = text
		if (lang == "spa" || lang == "es") out = money(out)
		out = BEFORE_A_DIGIT.replace(out, "$1 $2")
		out = BEFORE_AN_OPENER.replace(out, "$1 $2")
		out = LOOSE_S_SUFFIX.replace(out, "$1$2")
		out = SPACE_BEFORE_A_MARK.replace(out, "$1$2")
		out = GROUPED_THOUSANDS.replace(out) { it.value.replace(",", "") }
		if (lang == "spa" || lang == "es") out = spanish(out)
		return out
	}

	/** Las reglas que solo valen para el español. */
	private fun spanish(text: String): String {
		var out = text
		// La letra y, no la conjunción. La conjunción se queda como
		// está ("pan y vino"): solo se toca cuando el contexto dice
		// que es una letra. La Y mayúscula suelta sí es letra casi
		// siempre, y por eso se cambia aunque no haya artículo.
		out = LETTER_Y_AFTER_ARTICLE.replace(out) { m ->
			val open = m.groupValues[2]
			val shut = m.groupValues[4].ifEmpty {
				when (open) {
					"«" -> "»"
					"“" -> "”"
					else -> ""
				}
			}
			"${m.groupValues[1]} $open${m.groupValues[3]} griega$shut"
		}
		out = LETTER_Y_QUOTED.replace(out) { m ->
			"${m.groupValues[1]}y griega${m.groupValues[3]}"
		}
		out = LETTER_Y_ALONE.replace(out) { m ->
			val lead = m.groupValues[1]
			val tail = m.groupValues[3]
			"${lead}y griega$tail"
		}
		out = UPPER_Y.replace(out, "y griega")
		out = MUAY_THAI.replace(out, "muai tai")
		out = DEGREE.replace(out, " grados ")
		// Un entero con separador de miles y decimales se dice sin
		// la parte decimal: 27.335,42 -> 27335. También un decimal
		// suelto con coma, que en español es la coma decimal.
		out = GROUPED_ES.replace(out) { m ->
			m.value.replace(".", "").substringBefore(",")
		}
		out = DECIMAL_COMMA_ES.replace(out, "$1")
		return out
	}

	private fun money(text: String): String =
		MONEY_ES.replace(text) { m ->
			val raw = m.groupValues[1]
			val last = raw.indexOfLast { it == '.' || it == ',' }
			var whole = raw
			var cents = 0
			if (last >= 0) {
				val tail = raw.substring(last + 1)
				if (tail.length !in 1..3) return@replace m.value
				if (tail.length < 3) {
					val head = raw.substring(0, last)
					if (head.indexOf(raw[last]) >= 0) return@replace m.value
					whole = head
					cents = tail.padEnd(2, '0').toInt()
				}
			}
			if (!grouped(whole)) return@replace m.value
			val amount = whole.filter { it in '0'..'9' }.toLongOrNull()
				?: return@replace m.value
			val unit = if (amount == 1L) "peso" else "pesos"
			val cent = if (cents == 1) "centavo" else "centavos"
			when {
				amount == 0L && cents == 0 -> "0 pesos"
				amount == 0L -> "$cents $cent"
				cents == 0 -> "$amount $unit"
				else -> "$amount $unit con $cents $cent"
			}
		}

	private fun grouped(raw: String): Boolean {
		val at = raw.indexOfFirst { it == '.' || it == ',' }
		if (at < 0) return raw.all { it in '0'..'9' }
		val sep = raw[at]
		val parts = raw.split(sep)
		if (parts.size < 2 || parts[0].isEmpty() || parts[0].length > 3) return false
		if (parts.any { part -> part.isEmpty() || part.any { it !in '0'..'9' } }) return false
		return parts.drop(1).all { it.length == 3 }
	}

	/** "la y", "la letra y", "una y" y sus comillas. */
	private val LETTER_Y_AFTER_ARTICLE = Regex(
		"""(?i)\b(la|una|letra)\s+(["“«']?)([yY])(["”»']?)(?!\p{L}|\s+griega)""",
		RegexOption.IGNORE_CASE
	)

	/** "y" entre comillas es la letra, no la conjunción. */
	private val LETTER_Y_QUOTED = Regex("""(["“«'])([yY])(["”»'])(?!\p{L})""")

	/** Toda la emisión es la letra y, con o sin puntuación. */
	private val LETTER_Y_ALONE = Regex("""^(\s*[¿¡"“«']*)([yY])([.!?…"”»']*\s*)$""")

	/** "Y" mayúscula suelta. No toca "Y griega", que ya está bien. */
	private val UPPER_Y = Regex("""(?<!\p{L})Y(?!\p{L})(?!(?i:\s+griega))""")

	private val MUAY_THAI = Regex("""(?i)\bmuay[\s-]+thai\b""")

	private val DEGREE = Regex("°")

	private val MONEY_ES = Regex("""(?<![\p{L}\p{N}])\$[ \t]*(\d(?:[\d.,]*\d)?)""")

	/** 27.335,42 o 1.234: el entero, sin puntos ni decimales. */
	private val GROUPED_ES = Regex("""(?<![\d.,])\d{1,3}(?:\.\d{3})+(?:,\d+)?(?![\d.,])""")

	/** 3,14: la parte entera. En español la coma es decimal. */
	private val DECIMAL_COMMA_ES = Regex("""(?<![\d.,])(\d+),\d+(?![\d.,])""")

	/** Una letra contra un dígito, que es todo el problema teamtalk5.
	 *  Donde el motor ya se las arreglaba el espacio no cuesta nada:
	 *  mp3, x86, v2, NVDA5 y EURUSD5 volvieron byte por byte igual con
	 *  él y sin él, así que esto no necesita prueba de qué palabras
	 *  vale la pena partir. Dígitos contra letras se dejan en paz:
	 *  1st ya está bien y empeora al partirse.
	 *  La letra es Unicode ([p{L}]): con solo ASCII las palabras
	 *  acentuadas (está, qué, ó) no se arreglaban y el español
	 *  sonaba distinto que el inglés.
	 *  4.1: las anotaciones del motor (`` `vs50 ``, `` `vb75 ``,
	 *  `` `v3 ``, `` `p80 ``) no se tocan: partirlas las mata y el
	 *  número suelto se pronunciaría. El negativo mira lo que hay
	 *  justo antes de la letra: contrabarra sola o con 1-2 letras
	 *  (`` `v3 ``, `` `vs50 ``) nunca es teamtalk5. */
	private val BEFORE_A_DIGIT = Regex("""(?<![`][a-zA-Z]{0,2})([\p{L}])(\d)""")

	/** Las marcas delante de las que el motor empieza a deletrear.
	 *  Los corchetes de cierre están deliberadamente ausentes:
	 *  "abc)" ya no cuesta más que "abc", mientras que "abc )"
	 *  cuesta el doble. */
	private val OPENERS = """~#${'$'}%^*({|\[<""" + "\u2022"

	private val BEFORE_AN_OPENER = Regex("([\\p{L}]+)([" + Regex.escape(OPENERS) + "])")

	/** "books (s)" de vuelta a "books(s)", que es un tercio más corto. */
	private val LOOSE_S_SUFFIX = Regex("""([\p{L}]+)\s+(\(s\))""")

	/** El motor no tolera un espacio delante de un signo de puntuación.
	 *  La mirada al frente deja fuera decimales y separadores de miles.
	 *  Letras y números en Unicode: "está ." y "2:30" se tratan igual
	 *  que "esta ." y "2:30" en inglés. */
	private val SPACE_BEFORE_A_MARK = Regex("""(\S)\s+([:.!;,?](?![\p{L}\p{N}]))""")

	/** Un número agrupado con comas alrededor de un millar redondo, que el
	 *  motor lee como "one comma hundred". El controlador lo deletrea como
	 *  cuatro reglas, una por longitud; una repetición cubre lo mismo y sigue
	 *  más allá del billón. Solo un grupo 000 califica, así que 12,345 se deja
	 *  en paz, que medido igual daba de cualquier forma. */
	private val GROUPED_THOUSANDS = Regex("""\b\d{1,3},000(?:,\d{3})+\b""")

	/** Punto final para el motor cuando la opción está en
	 *  vigor, y texto intacto cuando no: apagado el motor va
	 *  con sus parámetros normales, como el complemento de
	 *  NVDA (ninguna marca añadida, ningún contorno forzado;
	 *  lo que el usuario haya elegido en voz, pausas y ritmo
	 *  manda tal cual). Encendido se lee como siempre. */
	fun applySentenceDot(text: String, enabled: Boolean): String =
		if (enabled) terminalDot(text) else text

	/** Punto final para el motor en cada trozo y muestra: si
	 *  [text] termina en letra o dígito se le pone "." para que el
	 *  contorno sea de enunciado y no de continuación. Sin marca
	 *  terminal el motor sostiene la frase y en español eso sube
	 *  como pregunta o exclamación; con el punto cada etiqueta cae
	 *  como enunciado. Lo que ya termina en marca se deja tal
	 *  cual. Los cierres finales (comillas, paréntesis) no
	 *  cuentan: el punto va antes de ellos, que es donde el
	 *  motor lo lee. Un fragmento con apertura sin cierre
	 *  (¿Qué, ¡Hola) se cierra con su marca, que es la que
	 *  manda la curva. */
	fun terminalDot(text: String, terminal: String = "."): String {
		val t = text.trimEnd()
		if (t.isEmpty()) return text
		var end = t.length
		while (end > 0 && t[end - 1] in TERMINAL_CLOSERS) end--
		if (end == 0) return text
		val core = t.substring(0, end)
		val last = core.last()
		if (last == '.' || last == '!' || last == '?' || last == '…' ||
			last == ';' || last == ':' || last == ','
		) return text
		if (!last.isLetterOrDigit()) return text
		if (terminal.isEmpty() || terminal == ".") {
			val openQ = core.count { it == '¿' } - core.count { it == '?' }
			val openE = core.count { it == '¡' } - core.count { it == '!' }
			val mark = when {
				openQ > 0 && openQ >= openE -> "?"
				openE > 0 -> "!"
				else -> "."
			}
			return core + mark + t.substring(end)
		}
		// 4.2: con la pausa en vez de la marca, que es lo que se
		// pone cuando el nivel de puntuación es NINGUNA. Se pone
		// delante de los cierres, que es donde el motor la lee.
		return core + " " + terminal + t.substring(end)
	}

	/** Cierres que pueden ir tras la marca final sin quitarle
	 *  su valor: el contorno lo manda la marca, no ellos. */
	private const val TERMINAL_CLOSERS = "\"'»”’)]}>°"

	/** Lo que va entre paréntesis, para la opción de omitirlo:
	 *  "(aclaración)" sale sin decirse. Sin anidar. */
	private val PARENS = Regex("""\([^()]*\)""")

	/** [text] sin lo que va entre paréntesis. Elimina el contenido
	 *  y los paréntesis, colapsando espacios para no dejar huecos
	 *  ni dobles espacios. */
	fun withoutParens(text: String): String {
		if ('(' !in text) return text
		// Primero quita el contenido con paréntesis
		val step1 = PARENS.replace(text, "")
		// Luego colapsa espacios múltiples y recorta
		return Regex("\\s{2,}").replace(step1, " ").trim()
	}
}
