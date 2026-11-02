package com.angelonmx.reloquence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextPiecesTest {

	@Test
	fun `the first window ends at the first sentence after the minimum`() {
		val sentence = "a".repeat(130) + ". "
		val text = sentence + "b".repeat(4000)
		assertEquals(sentence, TextPieces.windows(text).first())
	}

	@Test
	fun `a sentence shorter than the minimum does not end the first window`() {
		val expected = "Hola. " + "e".repeat(130) + ". "
		val text = expected + "f".repeat(4000)
		assertEquals(expected, TextPieces.windows(text).first())
	}

	@Test
	fun `an abbreviation does not end the first window`() {
		val expected = "El Sr. " + "g".repeat(130) + ". "
		val text = expected + "h".repeat(4000)
		assertEquals(expected, TextPieces.windows(text).first())
	}

	@Test
	fun `medium text still uses the short first window`() {
		val text = "palabra ".repeat(100)
		val windows = TextPieces.windows(text).toList()
		assertTrue("el texto debe dividirse antes de procesar todo el bloque", windows.size > 1)
		assertTrue(
			"la primera ventana debe respetar el límite de latencia",
			windows.first().length <= TextPieces.FIRST_WINDOW_CHARS
		)
		assertEquals(text, windows.joinToString(""))
	}

	@Test
	fun `windows keep every character in order`() {
		val text = buildString {
			repeat(120) {
				append("Frase ").append(it).append(" con algunas palabras. ")
			}
			append("Sin punto final")
		}
		val windows = TextPieces.windows(text).toList()
		assertTrue(windows.size > 1)
		assertEquals(text, windows.joinToString(""))
	}
}
