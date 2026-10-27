package com.angelonmx.reloquence

/** Texto largo, cortado en trozos entre los que el motor
 *  puede detenerse.
 *
 *  El motor no puede abandonar una emisión. Eso está resuelto y
 *  no es abierto -- los propios documentos de openevv dicen que
 *  se probaron seis formas de añadir un punto de abandono y
 *  todas fallaron -- así que pedir silencio espera a lo que está
 *  en vuelo. Para una línea de una lista es una treintava de
 *  segundo y nadie lo oye. Para un párrafo no, y un lector que
 *  desliza cada par de cien milisegundos tiene todo lo que
 *  cae dentro de la espera tirado: el habla se queda en silencio
 *  por varios elementos y luego alcanza al último.
 *
 *  Entregar el texto en trozos hace que la espera sea un trozo
 *  en lugar de un mensaje. Donde termina el trozo es el problema
 *  entero, porque terminar uno no es gratis: el motor pausa al
 *  final de una emisión como lo haría en un punto final, así que
 *  un punto de corte que no se habría hecho él mismo es una
 *  brecha audible.
 *
 *  La regla aquí es la de openevv, portada de su controlador
 *  NVDA, y los números son medidos en vez de elegidos. El final
 *  de una frase no cuesta nada -- un mensaje llegó a 252,010
 *  muestras completo y las mismas 252,010 en seis trozos -- y
 *  en cualquier otro lugar cuesta unas cuatro décimas de segundo.
 *  Así que un punto final tiene que argumentar que es uno: una
 *  palabra que lleva un punto dentro es una abreviatura ("p.ej.",
 *  "EE.UU.") y una palabra corta que empieza con mayúscula es un
 *  inicial o un título ("J.", "Sr."), y cortar en esos puntos
 *  medidos 1,48 s y 0,70 s más largo que las mismas frases
 *  completas. Solo pasados los caracteres [PIECE_CAP] un trozo
 *  termina en un espacio normal, que limita el caso que no ofrece
 *  punto final alguno.
 */
object TextPieces {

	/** Tamaño máximo de la primera ventana: pequeño para que la
	 *  primera sílaba suene casi al instante (milisegundos). */
	const val FIRST_WINDOW_CHARS = 240

	/** Mínimo de la primera ventana para que el motor tenga audio
	 *  suficiente mientras se prepara la siguiente. */
	const val FIRST_WINDOW_MIN_CHARS = 40

	/** Tamaño de las ventanas siguientes: mayor para eficiencia
	 *  una vez que el audio ya está fluyendo. */
	const val WINDOW_CHARS = 1500

	/** [texto] en ventanas para arranque instantáneo: cada una se
	 *  procesa y se habla antes de mirar la siguiente, así la
	 *  primera suena en milisegundos venga un párrafo o un libro.
	 *  La primera ventana es pequeña ([FIRST_WINDOW_CHARS]) para
	 *  latencia mínima; las siguientes usan [WINDOW_CHARS].
	 *  El corte cae en límite de frase o párrafo cuando lo hay a
	 *  la vista, si no en un espacio, y solo en última instancia
	 *  a mitad de palabra (textos sin espacios, como CJK). Cada
	 *  ventana sale en orden y sin solapes; los blancos de borde
	 *  se pliegan (no se oyen). Perezoso: la primera ventana sale
	 *  sin recorrer el resto. */
	fun windows(text: String, max: Int = WINDOW_CHARS): Sequence<String> = sequence {
		val cap = max.coerceIn(500, 20000)
		val n = text.length
		if (n == 0) return@sequence
		if (n <= cap) {
			yield(text)
			return@sequence
		}
		var start = 0
		var firstWindow = true
		while (start < n) {
			// Salta blancos iniciales: pertenecen a la ventana
			// anterior y una ventana en blanco no dice nada.
			while (start < n && text[start].isWhitespace() && start > 0) start++
			if (start >= n) break
			val windowCap = if (firstWindow) FIRST_WINDOW_CHARS else cap
			val end = minOf(start + windowCap, n)
			if (end >= n) {
				yield(text.substring(start, n))
				break
			}
			val early = if (firstWindow) findFirstWindowCut(text, start, end) else -1
			val cut = if (early > start) early else findWindowCut(text, start, end)
			yield(text.substring(start, cut))
			start = cut
			firstWindow = false
		}
	}

	private fun findFirstWindowCut(text: String, start: Int, end: Int): Int {
		val min = minOf(start + FIRST_WINDOW_MIN_CHARS, end)
		var wordStart = start
		var i = start
		while (i < end) {
			val c = text[i]
			if (c == '\n') {
				val cut = i + 1
				if (cut > min) return cut
				wordStart = cut
			} else if (c.isWhitespace()) {
				if (i > wordStart && endsSentence(text.substring(wordStart, i))) {
					val cut = i + 1
					if (cut > min) return cut
				}
				wordStart = i + 1
			}
			i++
		}
		return -1
	}

	/** Dónde cortar la ventana [start, end): el mejor límite hacia
	 *  atrás desde [end]. Orden: párrafo, salto de línea, final
	 *  de frase seguido de blanco, espacio. Sin nada a la vista
	 *  se corta en [end]. */
	private fun findWindowCut(text: String, start: Int, end: Int): Int {
		// Solo se mira hacia atrás una distancia acotada: lo
		// bastante para encontrar frase sin recorrer la ventana
		// entera en textos larguísimos.
		val lookBack = minOf(end - start, 1500)
		val from = end - lookBack
		// Párrafo: doble salto o salto con retorno.
		var best = -1
		var i = end - 1
		while (i >= from) {
			val c = text[i]
			if (c == '\n' && i - 1 >= start && text[i - 1] == '\n') {
				best = i + 1
				break
			}
			i--
		}
		if (best > start + 100) return best
		// Salto de línea solo.
		i = end - 1
		while (i >= from) {
			if (text[i] == '\n') {
				val cut = i + 1
				if (cut > start + 100) return cut
				break
			}
			i--
		}
		// Final de frase seguido de blanco o fin: . ! ? … 。 ！ ？
		// con sus cierres (" ) ] » ” ’).
		i = end - 1
		while (i >= from) {
			val c = text[i]
			if (c.isWhitespace()) {
				var j = i - 1
				while (j >= start && text[j] in CLOSERS) j--
				if (j >= start && (text[j] in ENDERS || text[j] == '.' || text[j] == ';' || text[j] == ':')) {
					val cut = i + 1
					if (cut > start + 100) return cut
					break
				}
			}
			i--
		}
		// Espacio normal.
		i = end - 1
		while (i >= from) {
			if (text[i].isWhitespace()) {
				val cut = i + 1
				if (cut > start + 100) return cut
				break
			}
			i--
		}
		// Sin límite a la vista: corte duro (CJK y similares).
		return end
	}

	/** Qué tan lejos corre un trozo antes de terminar en un
	 *  espacio por falta de un final de frase. */
	const val PIECE_CAP = 500

	/** Marcas de cierre que pueden estar después de la puntuación
	 *  que termina una frase. */
	private const val CLOSERS = "\"')]}»”’"

	private const val ENDERS = "?!…。！？"

	/** Si una palabra termina una frase en lugar de ser una
	 *  abreviatura con puntos. */
	fun endsSentence(word: String): Boolean {
		val tail = word.trimEnd { it in CLOSERS }
		if (tail.isEmpty()) return false
		if (tail.last() in ENDERS) return true
		if (!tail.endsWith('.') || tail.length < 2 || tail[tail.length - 2].isDigit()) return false
		val without = tail.dropLast(1)
		if (without.contains('.')) return false
		return !(without.length <= 3 && without.first().isUpperCase())
	}

	/** [texto] como los trozos para entregar uno a la vez.
	 *  Cada carácter del original está en exactamente un trozo
	 *  y en el mismo orden, así que el habla es la misma habla.
	 */
	fun split(text: String): List<String> {
		if (text.length <= PIECE_CAP && !text.any { it.isWhitespace() }) return listOf(text)
		val pieces = ArrayList<String>()
		val piece = StringBuilder()
		var gathered = 0
		var sentence = false
		for (part in runs(text)) {
			piece.append(part)
			gathered += part.length
			if (part.isNotBlank()) {
				sentence = endsSentence(part)
				continue
			}
			// Un trozo termina después del espacio que sigue a
			// la palabra, así que ninguna palabra se parte y no
			// queda nada apoyado en el siguiente trozo.
			if (sentence || gathered >= PIECE_CAP) {
				pieces.add(piece.toString())
				piece.setLength(0)
				gathered = 0
			}
			sentence = false
		}
		if (piece.isNotBlank()) {
			pieces.add(piece.toString())
		} else if (piece.isNotEmpty() && pieces.isNotEmpty()) {
			// El espacio final pertenece al trozo de delante en
			// lugar de a un trozo propio, que sería una emisión
			// sin decir nada.
			pieces[pieces.lastIndex] = pieces.last() + piece
		}
		return if (pieces.isEmpty()) listOf(text) else pieces
	}

	/** [texto] en turnos de hasta [words] palabras para la
	 *  mezcla de voces: cada turno lo habla una voz y el
	 *  siguiente la otra. Una palabra es una corrida de no
	 *  blancos; los cortes caen siempre en límites de palabra
	 *  (ninguna palabra se parte jamás) y el espacio posterior
	 *  pertenece al turno que cierra, para no dejar turnos
	 *  vacíos. De 5 a 1000 palabras por turno. */
	fun alternateWords(text: String, words: Int): List<String> {
		val cap = words.coerceIn(5, 1000)
		if (text.isBlank()) return listOf(text)
		if (countWords(text) <= cap) return listOf(text)
		val out = ArrayList<String>()
		val cur = StringBuilder()
		var inTurn = 0
		var at = 0
		val n = text.length
		while (at < n) {
			val c = text[at]
			if (c.isWhitespace()) {
				// El blanco va con el turno que lo precede, sin
				// contar como palabra.
				cur.append(c)
				at++
			} else {
				// La palabra que llenaría de más abre turno nuevo:
				// el corte cae justo antes de ella, en el límite.
				if (inTurn >= cap) {
					out.add(cur.toString())
					cur.setLength(0)
					inTurn = 0
				}
				var end = at + 1
				while (end < n && !text[end].isWhitespace()) end++
				cur.append(text, at, end)
				at = end
				inTurn++
			}
		}
		if (cur.isNotBlank()) out.add(cur.toString())
		else if (cur.isNotEmpty() && out.isNotEmpty()) out[out.lastIndex] = out.last() + cur
		return if (out.isEmpty()) listOf(text) else out
	}

	/** Cuántas palabras (corridas de no blancos) trae [text].
	 *  Una pasada sin reservar nada. */
	fun countWords(text: String): Int {
		var count = 0
		var inWord = false
		for (i in text.indices) {
			if (text[i].isWhitespace()) inWord = false
			else if (!inWord) {
				inWord = true
				count++
			}
		}
		return count
	}

	/** [texto] como corridas alternas de espacios en blanco
	 *  y todo lo demás. */
	private fun runs(text: String): List<String> {
		val out = ArrayList<String>()
		var at = 0
		while (at < text.length) {
			val from = at
			val space = text[at].isWhitespace()
			while (at < text.length && text[at].isWhitespace() == space) at++
			out.add(text.substring(from, at))
		}
		return out
	}
}
