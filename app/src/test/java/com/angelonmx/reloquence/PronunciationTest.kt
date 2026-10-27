package com.angelonmx.reloquence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las pronunciaciones fijas del español no deben tocar la
 * conjunción ni el texto de otros idiomas.
 */
class PronunciationTest {

	@Test
	fun `la letra y se dice y griega y la conjuncion se queda`() {
		assertEquals("la y griega", TextFixes.apply("la y", "spa"))
		assertEquals("una Y griega", TextFixes.apply("una Y", "spa"))
		assertEquals("letra y griega", TextFixes.apply("letra y", "spa"))
		assertEquals("y griega", TextFixes.apply("y", "spa"))
		assertEquals("pan y vino", TextFixes.apply("pan y vino", "spa"))
		assertEquals("A y B", TextFixes.apply("A y B", "spa"))
		assertEquals("la \"y griega\"", TextFixes.apply("la \"y\"", "spa"))
		assertEquals("la «y griega»", TextFixes.apply("la «y»", "spa"))
		assertEquals("\"y griega\"", TextFixes.apply("\"y\"", "spa"))
	}

	@Test
	fun `no se duplica y griega`() {
		assertEquals("la y griega", TextFixes.apply("la y griega", "spa"))
		assertEquals("Y griega", TextFixes.apply("Y griega", "spa"))
	}

	@Test
	fun `muay thai y grados`() {
		assertEquals("muai tai", TextFixes.apply("Muay Thai", "spa"))
		assertEquals("25 grados", TextFixes.apply("25°", "spa").replace("  ", " ").trim())
	}

	@Test
	fun `los enteros dejan fuera los decimales`() {
		assertEquals("27335", TextFixes.apply("27.335,42", "spa"))
		assertEquals("1234", TextFixes.apply("1.234", "spa"))
		assertEquals("3", TextFixes.apply("3,14", "spa"))
		assertEquals("2:30", TextFixes.apply("2:30", "spa"))
	}

	@Test
	fun `la moneda con simbolo de peso se dice completa`() {
		assertEquals("1153 pesos", TextFixes.apply("\$ 1,153", "spa"))
		assertEquals("1153 pesos con 50 centavos", TextFixes.apply("\$1,153.50", "spa"))
		assertEquals("1153 pesos con 50 centavos", TextFixes.apply("\$1.153,50", "spa"))
		assertEquals("1 peso con 50 centavos", TextFixes.apply("\$1,50", "spa"))
		assertEquals("50 centavos", TextFixes.apply("\$0.50", "spa"))
		assertEquals("1 peso", TextFixes.apply("\$1.00", "spa"))
		assertEquals("2554 pesos", TextFixes.apply("\$2554", "spa"))
		assertEquals("1 centavo", TextFixes.apply("\$0.01", "spa"))
		assertEquals("1234567 pesos con 89 centavos", TextFixes.apply("\$1,234,567.89", "spa"))
	}

	@Test
	fun `las reglas fijas no cambian el ingles`() {
		assertEquals("the y", TextFixes.apply("the y"))
		assertTrue(TextFixes.apply("25°") != "25 grados")
	}

	@Test
	fun `los simbolos se dicen sin tocar los emojis`() {
		val arrow = UnicodeSpeech.describeLang("→", "spa")
		assertTrue(arrow, arrow.contains("flecha"))
		val emoji = UnicodeSpeech.describeLang("😀", "spa")
		assertEquals("😀", emoji)
	}
}
