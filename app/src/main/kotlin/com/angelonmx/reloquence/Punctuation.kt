package com.angelonmx.reloquence

/** La puntuación dicha por el sintetizador.
 *
 *  Apagado va el texto tal cual (la lee el lector si sabe).
 *  Encendido hay tres niveles: TODO la deletrea toda ("hola,
 *  coma, cómo estás"), ESENCIAL solo la que lleva significado
 *  (. , ; : ! ? ¡ ¿ …) y NINGUNA no dice ningún nombre pero deja
 *  los signos para que el motor haga sus pausas (hola, [pausa]
 *  cómo estás). Quitarlos también quitaría la pausa, que es lo
 *  que se corrige aquí.
 */
object Punctuation {

	const val ALL = 0
	const val ESSENTIAL = 1
	const val NONE = 2

	fun apply(text: String, enabled: Boolean, level: Int, lang: String): String {
		if (!enabled || text.isEmpty()) return text
		return when (level) {
			ALL -> spellAll(text, tableOf(lang))
			NONE -> keepForPauses(text)
			else -> spellEssential(text, tableOf(lang))
		}
	}

	/**
	 * NINGUNA: ni un nombre de signo, ni un signo. 4.2: antes esta
	 * opción dejaba el texto tal cual y confiaba en que el motor
	 * callara la puntuación, que es lo que hace normalmente, pero
	 * no siempre: con un `` `v `` puesto antes, el motor se quedaba
	 * en un modo en el que un punto suelto o un "1." sí se leían.
	 *
	 * Ahora no se confía: **la marca se quita y en su lugar se deja
	 * la pausa**, con la duración que le tocaba según la marca. Así
	 * el texto que llega al motor no tiene nada que verbalizar (ni
	 * por el motor, ni por el diccionario, ni porque el estado
	 * interno fuera el que fuera) y la pausa sigue ahí, que es lo
	 * único que este nivel promete.
	 */
	fun silence(text: String, pauseMs: Int): String {
		if (text.isEmpty()) return text
		if (text.none { it in SILENCED }) return text
		val brief = pauseMs.coerceIn(0, 2000)
		if (brief <= 0) return text.filterNot { it in SILENCED }
		val out = StringBuilder(text.length + 16)
		var at = 0
		while (at < text.length) {
			val c = text[at]
			if (c !in SILENCED) {
				out.append(c)
				at++
				continue
			}
			// 4.2: una marca entre dos dígitos no es puntuación, es
			// parte del número. El punto de "3.14", los dos puntos de
			// "2:30", la coma de "1,024" y el guion de "555-1234" se
			// quedan donde están: el motor los lee sin sonido y sin
			// pausa, que es justo lo que se quiere, y quitarlos
			// partía el número en dos.
			val before = if (at > 0) text[at - 1] else ' '
			var after = at + 1
			while (after < text.length && text[after] == c) after++
			val next = if (after < text.length) text[after] else ' '
			if (before.isDigit() && next.isDigit()) {
				out.append(text, at, after)
				at = after
				continue
			}
			// Una corrida de la misma marca es una sola pausa, que es
			// como las cuenta el motor: "espera..." no son tres.
			at = after
			if (c == ' ') continue
			out.append(' ').append(PAUSE).append(durationFor(c, brief))
		}
		return out.toString()
	}

	/** La duración de la pausa de cada marca, en múltiplos de la de
	 *  referencia: una coma es un rescoldo y un punto es una frase
	 *  que acaba. Proporcionado a ojo, no medido, porque lo que se
	 *  promises es que la pausa siga ahí y no su duración exacta. */
	private fun durationFor(mark: Char, brief: Int): Int = when (mark) {
		',', ';', ':', '…' -> (brief * 2).coerceIn(1, 2000)
		'-', '–', '—' -> brief
		else -> (brief * 4).coerceIn(1, 2000)
	}

	/** Las marcas que este nivel quita. Las de abrir y cerrar
	 *  paréntesis y las comillas no Pausean, así que se dejan: solo
	 *  se quita lo que además de sonar hace una pausa. */
	private const val SILENCED = ".,;:!?¡¿…-"

	/** La anotación de pausa del motor: `` `p `` y los milisegundos. */
	private const val PAUSE = "`p"

	/**
	 * NINGUNA con el criterio antiguo: el texto tal cual, con las
	 * marcas dentro para que el motor pause en ellas. Se conserva
	 * porque es el camino por defecto cuando el nivel está en
	 * NINGUNA pero las pausas van a su medida ([Punctuation.apply]
	 * sin [silence]); con las pausas ya puestas, quien llama usa
	 * [silence].
	 */
	private fun keepForPauses(text: String): String = text

	private fun tableOf(lang: String): Map<Char, String> =
		TABLES[lang] ?: TABLES["eng"]!!

	private val SPACES = Regex(" {2,}")

	private fun squish(out: StringBuilder): String =
		SPACES.replace(out, " ").trim()

	private fun spellAll(text: String, names: Map<Char, String>): String {
		val out = StringBuilder(text.length + 64)
		var i = 0
		while (i < text.length) {
			if (text.startsWith("...", i)) {
				out.append(' ').append(names['…'] ?: "...").append(' ')
				i += 3
				continue
			}
			val c = text[i]
			val name = names[c]
			if (name != null) out.append(' ').append(name).append(' ')
			else out.append(c)
			i++
		}
		return squish(out)
	}

	private fun spellEssential(text: String, names: Map<Char, String>): String {
		val out = StringBuilder(text.length + 64)
		var i = 0
		while (i < text.length) {
			if (text.startsWith("...", i)) {
				out.append(' ').append(names['…'] ?: "...").append(' ')
				i += 3
				continue
			}
			val c = text[i]
			if (c in ESSENTIAL_MARKS) {
				val name = names[c]
				if (name != null) out.append(' ').append(name).append(' ')
				else out.append(c)
			} else {
				out.append(c)
			}
			i++
		}
		return squish(out)
	}

	private val ESSENTIAL_MARKS: Set<Char> = setOf('.', ',', ';', ':', '!', '?', '¡', '¿', '…')

	private val TABLES: Map<String, Map<Char, String>> = mapOf(
		"spa" to mapOf(
			'.' to "punto", ',' to "coma", ';' to "punto y coma", ':' to "dos puntos",
			'!' to "exclamación", '?' to "interrogación",
			'¡' to "apertura de exclamación", '¿' to "apertura de interrogación",
			'-' to "guion", '–' to "semirraya", '—' to "raya",
			'(' to "abre paréntesis", ')' to "cierra paréntesis",
			'"' to "comillas", '«' to "abre comillas", '»' to "cierra comillas",
			'…' to "puntos suspensivos", '\'' to "apóstrofo", '/' to "barra",
			'@' to "arroba", '#' to "numeral", '%' to "por ciento", '&' to "y comercial",
			'*' to "asterisco", '+' to "más", '=' to "igual",
			'<' to "menor que", '>' to "mayor que"
		),
		"eng" to mapOf(
			'.' to "period", ',' to "comma", ';' to "semicolon", ':' to "colon",
			'!' to "exclamation mark", '?' to "question mark",
			'¡' to "inverted exclamation mark", '¿' to "inverted question mark",
			'-' to "hyphen", '–' to "en dash", '—' to "em dash",
			'(' to "opening parenthesis", ')' to "closing parenthesis",
			'"' to "quote", '«' to "opening guillemet", '»' to "closing guillemet",
			'…' to "ellipsis", '\'' to "apostrophe", '/' to "slash",
			'@' to "at sign", '#' to "hash", '%' to "percent", '&' to "ampersand",
			'*' to "asterisk", '+' to "plus", '=' to "equals",
			'<' to "less than", '>' to "greater than"
		),
		"fra" to mapOf(
			'.' to "point", ',' to "virgule", ';' to "point-virgule", ':' to "deux-points",
			'!' to "point d'exclamation", '?' to "point d'interrogation",
			'¡' to "point d'exclamation inversé", '¿' to "point d'interrogation inversé",
			'-' to "trait d'union", '–' to "tiret demi-cadratin", '—' to "tiret cadratin",
			'(' to "parenthèse ouvrante", ')' to "parenthèse fermante",
			'"' to "guillemet", '«' to "guillemet ouvrant", '»' to "guillemet fermant",
			'…' to "points de suspension", '\'' to "apostrophe", '/' to "barre oblique",
			'@' to "arobase", '#' to "dièse", '%' to "pour cent", '&' to "esperluette",
			'*' to "astérisque", '+' to "plus", '=' to "égal",
			'<' to "inférieur à", '>' to "supérieur à"
		),
		"deu" to mapOf(
			'.' to "Punkt", ',' to "Komma", ';' to "Semikolon", ':' to "Doppelpunkt",
			'!' to "Ausrufezeichen", '?' to "Fragezeichen",
			'¡' to "umgekehrtes Ausrufezeichen", '¿' to "umgekehrtes Fragezeichen",
			'-' to "Bindestrich", '–' to "Halbgeviertstrich", '—' to "Geviertstrich",
			'(' to "Klammer auf", ')' to "Klammer zu",
			'"' to "Anführungszeichen", '«' to "Anführungszeichen auf", '»' to "Anführungszeichen zu",
			'…' to "Auslassungspunkte", '\'' to "Apostroph", '/' to "Schrägstrich",
			'@' to "At-Zeichen", '#' to "Raute", '%' to "Prozent", '&' to "Und-Zeichen",
			'*' to "Sternchen", '+' to "Plus", '=' to "Gleichheitszeichen",
			'<' to "kleiner als", '>' to "größer als"
		),
		"ita" to mapOf(
			'.' to "punto", ',' to "virgola", ';' to "punto e virgola", ':' to "due punti",
			'!' to "punto esclamativo", '?' to "punto interrogativo",
			'¡' to "punto esclamativo rovesciato", '¿' to "punto interrogativo rovesciato",
			'-' to "trattino", '–' to "lineetta", '—' to "lineetta lunga",
			'(' to "parentesi aperta", ')' to "parentesi chiusa",
			'"' to "virgolette", '«' to "virgolette aperte", '»' to "virgolette chiuse",
			'…' to "puntini di sospensione", '\'' to "apostrofo", '/' to "barra",
			'@' to "chiocciola", '#' to "cancelletto", '%' to "per cento", '&' to "e commerciale",
			'*' to "asterisco", '+' to "più", '=' to "uguale",
			'<' to "minore di", '>' to "maggiore di"
		),
		"jpn" to mapOf(
			'.' to "ピリオド", ',' to "カンマ", ';' to "セミコロン", ':' to "コロン",
			'!' to "感嘆符", '?' to "疑問符",
			'¡' to "逆感嘆符", '¿' to "逆疑問符",
			'-' to "ハイフン", '–' to "ダッシュ", '—' to "ダッシュ",
			'(' to "括弧開き", ')' to "括弧閉じ",
			'"' to "引用符", '«' to "引用符開き", '»' to "引用符閉じ",
			'…' to "三点リーダ", '\'' to "アポストロフィ", '/' to "スラッシュ",
			'@' to "アットマーク", '#' to "シャープ", '%' to "パーセント", '&' to "アンパサンド",
			'*' to "アスタリスク", '+' to "プラス", '=' to "イコール",
			'<' to "小なり", '>' to "大なり"
		),
		"pol" to mapOf(
			'.' to "kropka", ',' to "przecinek", ';' to "średnik", ':' to "dwukropek",
			'!' to "wykrzyknik", '?' to "znak zapytania",
			'¡' to "odwrócony wykrzyknik", '¿' to "odwrócony znak zapytania",
			'-' to "myślnik", '–' to "półpauza", '—' to "pauza",
			'(' to "nawias otwierający", ')' to "nawias zamykający",
			'"' to "cudzysłów", '«' to "cudzysłów otwierający", '»' to "cudzysłów zamykający",
			'…' to "wielokropek", '\'' to "apostrof", '/' to "ukośnik",
			'@' to "małpa", '#' to "kratka", '%' to "procent", '&' to "et",
			'*' to "gwiazdka", '+' to "plus", '=' to "równa się",
			'<' to "mniejsze niż", '>' to "większe niż"
		)
	)
}
