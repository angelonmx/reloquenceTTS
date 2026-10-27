package com.angelonmx.reloquence

/** Forzar cómo se pronuncian los números.
 *
 *  Apagado va el texto tal cual (el motor decide: 123 es
 *  "ciento veintitrés").
 *  Encendido parte las corridas de dígitos en grupos con
 *  espacios para que el motor las diga por partes, o al revés
 *  las pega cuando vienen separadas:
 *  - 1: por dígito ("12345" -> "1 2 3 4 5", uno dos tres...).
 *  - 2: por dúo ("12345" -> "12 34 5", doce treinta y cuatro...).
 *  - 3: por trío ("1234567" -> "123 456 7").
 *  - ... hasta 10, siempre de izquierda a derecha y el último
 *    grupo puede quedar más corto.
 *  - CORRIDO (0): todo pegado ("1 2 3" -> "123", "12 34" ->
 *    "1234"), para que lo que venía separado se lea como un
 *    solo número corrido, literalmente corrido.
 *
 *  Se aplica a TODAS las corridas de dígitos, sin importar
 *  lo que haya antes o después (letras, espacios, guiones...).
 *  Solo se protegen los casos típicos de decimales (3.14),
 *  horas (2:30) y miles con coma (1,024) donde el separador
 *  está ENTRE dígitos.
 */
object Numbers {

	/** Todo pegado: une lo separado en un solo número corrido. */
	const val CORRIDO = 0

	const val MIN_GROUP = 1
	const val MAX_GROUP = 10

	/** [text] con los números forzados al grupo pedido.
	 *  [group] de 1 a 10 parte, [CORRIDO] pega. */
	fun apply(text: String, enabled: Boolean, group: Int): String {
		if (!enabled || text.isEmpty()) return text
		if (group == CORRIDO) return joinSpaced(text)
		val size = group.coerceIn(MIN_GROUP, MAX_GROUP)
		return splitGroups(text, size)
	}

	/** Parte cada corrida pura de dígitos en grupos de [size]
	 *  con un espacio entre uno y otro. Las anotaciones del motor
	 *  (`` `vs50 ``) se dejan tal cual: partirlas las mata. */
	private fun splitGroups(text: String, size: Int): String {
		if (size <= 1) {
			// Por dígito: un espacio entre dígito y dígito.
			return PURE_RUN.replace(text) { m ->
				if (m.groupValues[1].isNotEmpty()) return@replace m.value
				val run = m.groupValues[2]
				if (run.length <= 1) run
				else run.toCharArray().joinToString(" ")
			}
		}
		return PURE_RUN.replace(text) { m ->
			if (m.groupValues[1].isNotEmpty()) return@replace m.value
			val run = m.groupValues[2]
			if (run.length <= size) run
			else buildString(run.length + run.length / size) {
				var at = 0
				while (at < run.length) {
					if (at > 0) append(' ')
					append(run.substring(at, minOf(at + size, run.length)))
					at += size
				}
			}
		}
	}

	/** Pega lo que viene separado solo por espacios ("1 2 3" ->
	 *  "123"). Solo espacios entre dígitos: "3.1 4" o "2: 30"
	 *  no son corridas separadas y se dejan en paz. Una anotación
	 *  con espacios (`` `vs5 0 ``, ya rota) no se toca. */
	private fun joinSpaced(text: String): String {
		if (' ' !in text) return text
		return SPACED_RUN.replace(text) { m ->
			if (m.groupValues[1].isNotEmpty()) m.value
			else m.value.replace(" ", "")
		}
	}

	/** Una corrida de dígitos que NO forme parte de un decimal (3.14),
	 *  hora (2:30) o miles con coma (1,024). Se protege solo cuando
	 *  el separador está ENTRE dígitos: dígito + [.,:] + dígito.
	 *  Así "52554mensaje", "52554-voz", "abc123" SÍ se procesan.
	 *  4.0: ni las anotaciones del motor (`` `vs50 ``). */
	private val PURE_RUN = Regex("(`[a-zA-Z]{1,3}\\d+)|((?<!\\d[.,:])(\\d+)(?![.,:]\\d))")

	/** Dígitos separados solo por espacios ("12 34 5"), sin
	 *  dígito pegado fuera: lo que se pega en corrido.
	 *  4.0: ni anotaciones con espacios. */
	private val SPACED_RUN = Regex("(`[a-zA-Z]{1,3}[\\d ]*)|((?<![0-9])([0-9]+(?: +[0-9]+)+)(?![0-9]))")
}
