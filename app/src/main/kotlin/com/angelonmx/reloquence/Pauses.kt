package com.angelonmx.reloquence

/** Acortar los huecos que el motor deja en la puntuación
 *  y al final de lo que le dieron.
 *
 *  Eloquencia pausa con generosidad. Eso se lee bien en
 *  prosa y mal en un lector de pantalla, donde la mayoría
 *  de las emisiones son unas pocas palabras y el hueco
 *  después de cada una es tiempo esperando. El motor no
 *  tiene un ajuste para ello, pero sí toma una anotación:
 *  `` `p `` y un número es una pausa de tantos milisegundos,
 *  y poner una delante de una marca de puntuación reemplaza
 *  la pausa que esa marca tendría.
 *
 *  La regla es la de davidacm, del controlador NVDA IBMTTS,
 *  que llama a estas "pausas estilo JAWS". Una marca cuenta
 *  si sigue a una letra, un dígito o un espacio y va seguida
 *  de espacios en blanco, una barra o el final, que es lo que
 *  mantiene el punto decimal en 3,14 y los dos puntos en 2:30
 *  sin tocarlos.
 */
object Pauses {

	/** Dejar las pausas propias del motor en paz. */
	const val KEEP = 0

	/** Acortar solo el hueco después de lo último dicho. */
	const val END_ONLY = 1

	/** Acortar también los huecos en la puntuación. */
	const val ALL = 2

	/** Las marcas en las que el motor pausa. Los dos guiones están
	 *  aquí como los caracteres que Android entrega; WesternText los
	 *  convierte en guiones después, cuando esto ya corrió.
	 *
	 *  4.4: se miró meter aquí `¡` y `¿`, que son
	 *  las dos marcas del español, y **no están**: la regla de
	 *  abajo exige que detrás de la marca venga blanco, barra
	 *  o el final, y en español `¿` e `¡` van siempre pegados a
	 *  la palabra que abren. En "¿Qué?" no hay dónde hacerlos
	 *  caber, así que el cambio no habría cambiado nada y
	 *  sí habría duplicado marcas sin motivo.
	 *
	 *  Lo que sí arregla las pausas del español no es esta lista
	 *  sino [Prosody]: la predicción de frase estaba encendida y
	 *  convertía en frontera de frase las comas que no lo son
	 *  (handle_non_phrasal_commas, lang/eses/rules/ut_inton.dr:276), que
	 *  es de donde salían las pausas donde no las había puesto
	 *  quien escribió el texto. */
	private const val MARKS = "-,.:;?!–—"

	/** Una marca que es de verdad puntuación en vez de parte de un
	 *  número: después de una letra, dígito o espacio, y antes de
	 *  espacios, una barra o el final. El tercer grupo toma una corrida
	 *  de la misma marca, así que "espera..." es una sola pausa.
	 *  Letras y números en Unicode: "está, ..." acorta igual que
	 *  "esta, ..."; con solo ASCII el español nunca acortaba. */
	private val AT_A_MARK = Regex("([\\p{L}\\p{N}]|\\s)([$MARKS])(\\2*?)(\\s|[\\\\/]|$)")

	/** El hueco después de lo último dicho, que no se hace tan corto
	 *  como el resto. El motor deja unos cuatrocientos milisegundos ahí,
	 *  y cortarlos todos pone la sílaba final dura contra el final del
	 *  búfer, donde lo que sea que lo reproduce recorta un poco:
	 *  "Google Gemini" salía como "Google Gemin". [endMs] dice cuántos
	 *  de esos milisegundos conservar; con cero no se añade nada. */
	private const val AT_THE_END = "`p"

	/**
	 * [text] con las pausas que [mode] pide: [punctMs] en cada signo
	 * de puntuación cuando [mode] es [ALL], y [endMs] milisegundos
	 * al final. [last] dice si este es el final de lo que se pidió,
	 * ya que el hueco después de eso es del que [END_ONLY] trata.
	 * Con cero no se añade nada en ese punto.
	 */
	fun apply(text: String, mode: Int, last: Boolean, endMs: Int = 150, punctMs: Int = endMs): String {
		if (mode == KEEP || text.isEmpty()) return text
		var out = text
		val brief = punctMs.coerceIn(0, 2000)
		if (mode == ALL && brief > 0) {
			val tag = "`p$brief"
			out = AT_A_MARK.replace(out) { m -> "${m.groupValues[1]} $tag${m.groupValues[2]}${m.groupValues[3]}${m.groupValues[4]}" }
		}
		// El motor pausa al final de una emisión pida algo ahí o no.
		// Una marca al final ya se trató.
		if (last && endMs > 0 && out.trimEnd().lastOrNull() !in MARKS.toSet()) {
			out = "$out $AT_THE_END${endMs.coerceIn(1, 2000)}"
		}
		return out
	}
}
