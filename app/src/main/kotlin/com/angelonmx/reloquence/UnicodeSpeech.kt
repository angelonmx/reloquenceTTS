package com.angelonmx.reloquence

import java.util.Locale

/**
 * Los símbolos que no son emoji, dichos por su nombre.
 *
 * El motor solo conoce el juego occidental y [WesternText] convierte
 * en espacio lo que no cabe, así que una flecha, una llave
 * matemática o un símbolo de moneda desaparecen. Con el interruptor
 * de símbolos Unicode encendido, cada uno entra como palabras en el
 * idioma de la emisión: primero el nombre de CLDR, que ya está
 * traducido, y si no está, el nombre oficial de Unicode.
 *
 * Los emojis no pasan por aquí: tienen su propio interruptor y su
 * propio camino. Tampoco la puntuación normal ni los acentos, que
 * no son símbolos. El grado se deja para la regla fija que lo lee
 * como "grados" aunque este interruptor esté apagado.
 */
object UnicodeSpeech {

	fun describeLang(text: String, lang: String = "spa"): String {
		if (text.isEmpty()) return text
		val normalized = normalizeLang(lang)
		var out: StringBuilder? = null
		var i = 0
		while (i < text.length) {
			val cp = text.codePointAt(i)
			val size = Character.charCount(cp)
			if (!isSymbol(cp) || cp == 0x00B0 || isEmojiPictograph(cp)) {
				out?.append(text, i, i + size)
				i += size
				continue
			}
			val match = CldrData.emojiAt(text, i, normalized)
			val name = match?.name ?: fallbackName(cp, normalized)
			if (name == null) {
				out?.append(text, i, i + size)
				i += size
			} else {
				if (out == null) out = StringBuilder(text.length + 32).append(text, 0, i)
				out.append(' ').append(name).append(' ')
				i = match?.end ?: (i + size)
			}
		}
		val built = out ?: return text
		return SPACES.replace(built, " ")
	}

	private val SPACES = Regex(" {2,}")

	/** Solo letras y números tienen nombre hablado; flechas, formas,
	 *  matemáticas, monedas y demás símbolos, sí. */
	private fun isSymbol(cp: Int): Boolean {
		if (cp < 0x80) return false
		return when (Character.getType(cp)) {
			Character.MATH_SYMBOL.toInt(),
			Character.CURRENCY_SYMBOL.toInt(),
			Character.MODIFIER_SYMBOL.toInt(),
			Character.OTHER_SYMBOL.toInt() -> true
			else -> false
		}
	}

	/** Los pictogramas que ya lee [EmojiSpeech]. Se dejan para su
	 *  interruptor aunque este esté encendido, para que las dos
	 *  opciones sean de verdad independientes. */
	private fun isEmojiPictograph(cp: Int): Boolean =
		cp >= 0x1F000 ||
			cp in 0x2600..0x27BF ||
			cp in 0x2B00..0x2BFF ||
			cp == 0x203C || cp == 0x2049 ||
			cp == 0x3030 || cp == 0x303D ||
			cp == 0x3297 || cp == 0x3299

	private fun fallbackName(cp: Int, lang: String): String? {
		val raw = CldrData.unicodeName(cp)
			?: runCatching { Character.getName(cp) }.getOrNull()
			?: return null
		val name = raw.lowercase(Locale.ROOT).replace('_', ' ').trim()
		if (name.isEmpty() || name.startsWith("<")) return null
		return if (lang == "spa") spanishName(name) else name
	}

	/** Traducción de las palabras que más se repiten en los nombres
	 *  oficiales. CLDR cubre lo común; esto es para lo que no trae. */
	private val ES_WORDS = mapOf(
		"arrow" to "flecha", "left" to "izquierda", "right" to "derecha",
		"up" to "arriba", "down" to "abajo", "upwards" to "hacia arriba",
		"downwards" to "hacia abajo", "leftwards" to "hacia la izquierda",
		"rightwards" to "hacia la derecha", "double" to "doble",
		"black" to "negro", "white" to "blanco", "circle" to "círculo",
		"square" to "cuadrado", "triangle" to "triángulo",
		"pointed" to "apuntado", "star" to "estrella", "heart" to "corazón",
		"check" to "verificación", "mark" to "marca", "heavy" to "grueso",
		"light" to "ligero", "bold" to "grueso", "box" to "caja",
		"drawings" to "dibujos", "math" to "matemático", "plus" to "más",
		"minus" to "menos", "multiplication" to "multiplicación",
		"division" to "división", "equals" to "igual", "greater" to "mayor",
		"less" to "menor", "than" to "que", "infinity" to "infinito",
		"degree" to "grado", "sign" to "signo", "female" to "femenino",
		"male" to "masculino", "registered" to "registrada",
		"trade" to "comercial", "copyright" to "copyright",
		"currency" to "moneda", "euro" to "euro", "pound" to "libra",
		"yen" to "yen", "cent" to "centavo", "dollar" to "dólar",
		"broken" to "quebrado", "bar" to "barra", "vertical" to "vertical",
		"horizontal" to "horizontal", "angle" to "ángulo",
		"integral" to "integral", "summation" to "sumatoria",
		"product" to "producto", "radical" to "radical",
		"therefore" to "por lo tanto", "because" to "porque",
		"similar" to "similar", "equivalent" to "equivalente",
		"not" to "no", "approximately" to "aproximadamente"
	)

	private fun spanishName(en: String): String =
		en.split(' ').joinToString(" ") { ES_WORDS[it] ?: it }

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
}
