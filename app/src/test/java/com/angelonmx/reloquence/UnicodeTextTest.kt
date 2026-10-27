package com.angelonmx.reloquence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 5.1: el texto estilizado llega al motor como letras normales. */
class UnicodeTextTest {

	@Test
	fun `las letras matematicas se convierten en letras planas`() {
		assertEquals("Hola", UnicodeText.normalize("𝐻𝑜𝑙𝑎"))
		assertEquals("ABC", UnicodeText.normalize("𝐀𝐁𝐂"))
		assertEquals("123", UnicodeText.normalize("①②③"))
		assertEquals("x2", UnicodeText.normalize("x²"))
	}

	@Test
	fun `los acentos descompuestos se componen`() {
		assertEquals("versión", UnicodeText.normalize("versio\u0301n"))
		assertEquals("canción", UnicodeText.normalize("cancio\u0301n"))
		assertEquals("ñ", UnicodeText.normalize("n\u0303"))
	}

	@Test
	fun `los simbolos con nombre conservan su nombre`() {
		assertTrue(UnicodeText.normalize("™").contains("trademark"))
		assertTrue(UnicodeText.normalize("№ 5").contains("number"))
		assertTrue(UnicodeText.normalize("25℃").contains("degrees Celsius"))
	}

	@Test
	fun `el espanol normal no cambia`() {
		assertEquals("¿Qué tal, señor?", UnicodeText.normalize("¿Qué tal, señor?"))
		assertEquals("La versión 5.1.", UnicodeText.normalize("La versión 5.1."))
	}

	@Test
	fun `el pipeline normaliza antes de las reglas fijas`() {
		val pipeline = TtsTextPipeline(
			emojis = false,
			unicodeSymbols = false,
			punctOn = false,
			punctLevel = 0,
			skipParens = false,
			numbersOn = false,
			numberGroup = 0,
			silentPunct = false,
			pauseMs = 0,
			tag = "spa"
		)
		assertEquals("Hola", pipeline.apply("𝐻𝑜𝑙𝑎"))
		assertEquals("versión", pipeline.apply("versio\u0301n"))
	}
}
