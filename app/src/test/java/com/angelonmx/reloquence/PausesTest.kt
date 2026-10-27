package com.angelonmx.reloquence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4.4: las pausas de la puntuación no se han tocado.
 *
 * Este archivo es sobre todo una red: mientras se arreglaba la
 * entonación (`Prosody`) seurge la tentación de tocar también las
 * marcas que acortan las pausas, y no hay que.
 *
 * ## Por qué `¡` y `¿` no están en [Pauses.MARKS]
 *
 * Se miró y se descartó. La regla de [Pauses.AT_A_MARK] exige que
 * detrás de la marca venga blanco, barra o el final, y en español
 * `¿` e `¡` van siempre pegados a la palabra que abren: en "¿Qué?"
 * detrás de "¿" hay una "q", así que el patrón no casa y la marca
 * no entra. Meterla no habría cambiado el resultado de nada y
 * habría dejado el conjunto de marcas con dos miembros muertos.
 *
 * Lo que sí produce pausas raras en español no es esta lista, sino la
 * predicción de frase: encendida convertía en frontera de frase las
 * comas que no lo son (`handle_non_phrasal_commas`,
 * `lang/eses/rules/ut_inton.dr:276`). Eso sí está arreglado, en
 * [Prosody].
 *
 * Lo que sí hay que vigilar aquí es que lo de siempre siga igual: los
 * decimales, las horas y los nombres de archivo no son puntuación, y
 * un cambio que los tocara parte números por la mitad.
 */
class PausesTest {

	private fun all(text: String, ms: Int = 120) =
		Pauses.apply(text, Pauses.ALL, last = false, endMs = 150, punctMs = ms)

	private fun pausesIn(text: String): List<String> =
		Regex("`p\\d+").findAll(text).map { it.value }.toList()

	// ---- lo que tiene que seguir igual -------------------------------

	@Test
	fun `a comma between words is shortened`() {
		assertEquals("uno `p120, dos", all("uno, dos"))
	}

	@Test
	fun `a full stop is shortened`() {
		assertEquals("uno `p120. Dos", all("uno. Dos"))
	}

	@Test
	fun `the end of the text is shortened but not to nothing`() {
		// Cero aquí parte el búfer en dos, y la última sílaba suena
		// cortada: ver la nota de [Pauses.AT_THE_END].
		val out = Pauses.apply("Hola", Pauses.ALL, last = true, endMs = 150, punctMs = 120)
		assertEquals("un hueco corto al final", listOf("`p150"), pausesIn(out))
	}

	@Test
	fun `a decimal point is not punctuation`() {
		// El 3,14 tiene que salir "tres coma catorce". Si la coma se
		// tratara como marca, el motor oiría "tres coma [pausa] catorce".
		assertEquals("3,14", all("3,14"))
		assertEquals("2:30", all("2:30"))
		assertEquals("1,024", all("1,024"))
	}

	@Test
	fun `a dot inside a word is not punctuation`() {
		assertEquals("archivo.txt y v1.2", all("archivo.txt y v1.2"))
	}

	@Test
	fun `a hyphen inside a word is not punctuation`() {
		assertEquals("es-PE y alto-bajo", all("es-PE y alto-bajo"))
	}

	@Test
	fun `a run of the same mark is one pause`() {
		// "espera..." son tres signos y una pausa, que es como las
		// cuenta el motor.
		assertEquals("espera `p120... sigue", all("espera... sigue"))
	}

	@Test
	fun `keeping the engine pauses inserts nothing at all`() {
		assertEquals("Hola, ¿qué tal?", Pauses.apply("Hola, ¿qué tal?", Pauses.KEEP, last = true))
	}

	@Test
	fun `end only leaves the commas alone`() {
		val out = Pauses.apply("uno, dos", Pauses.END_ONLY, last = true, endMs = 150, punctMs = 120)
		assertEquals("solo la del final", listOf("`p150"), pausesIn(out))
	}

	@Test
	fun `a text ending in a mark does not get a second pause`() {
		// El hueco que deja la marca final ya es el del final; añadir
		// otro encima es sumar dos silencios.
		val out = Pauses.apply("¿Qué tal?", Pauses.ALL, last = true, endMs = 150, punctMs = 120)
		assertEquals("una pausa por marca", listOf("`p120"), pausesIn(out))
	}

	// ---- los signos españoles, comprobados y NO tocados --------------

	@Test
	fun `the inverted marks do not become pauses of their own`() {
		// Lo que el nombre de esta prueba no deja es que alguien lo
		// cambie sin querer. En español van pegados a la palabra, así
		// que la regla de arriba no las alcanza y quedan como están:
		// la pausa de la pregunta la pone el "?" de cierre, que es
		// donde tiene que estar.
		val asked = all("Dime ¿qué tal?")
		assertFalse("¿ no abre una pausa", asked.contains("`p120¿"))
		assertTrue("la interrogación de cierre sí la lleva", asked.contains("`p120?"))

		val cried = all("¡Venga!")
		assertFalse("¡ no abre una pausa", cried.contains("`p120¡"))
		assertTrue("la exclamación de cierre sí la lleva", cried.contains("`p120!"))
	}

	@Test
	fun `accented letters count as letters for the mark rule`() {
		// El partido se lo jugaba esto: con una expresión de ASCII
		// pelado, "está," no acortaba la coma nunca, y el español
		// sonaba distinto del inglés por un conjunto de caracteres.
		assertEquals("está `p120, así", all("está, así"))
		assertEquals("corazón `p120, sí", all("corazón, sí"))
	}

	@Test
	fun `a digit counts as the letter side of the mark`() {
		assertEquals("día 5 `p120. Luego", all("día 5. Luego"))
	}

	@Test
	fun `a mark with nothing behind it is left alone`() {
		// Sin nada delante ni detrás no hay dónde insertar: una regla
		// que se comiera el texto para poner una pausa sería peor que
		// no acortar.
		assertEquals(".", all("."))
	}
}