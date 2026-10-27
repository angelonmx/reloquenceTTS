package com.angelonmx.reloquence

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 5.4.1: la sustitución del diccionario es una sola pasada sobre el
 * texto original.
 *
 * Antes se sustituían primero las frases y después las palabras sobre
 * el resultado. Eso hacía que la pronunciación de una frase se leyera
 * como texto nuevo: una regla de palabra dentro de una frase sustituida
 * se aplicaba otra vez, y la voz decía palabras de más o cambiaba de
 * tono. Estas pruebas fijan las tres reglas de la pasada única: nada de
 * lo que sale de una regla se vuelve a mirar, la frase gana a la palabra
 * cuando empiezan juntas, y dos aciertos nunca se pisan.
 */
class DictionariesTest {

	@Before
	fun clearCache() {
		Dictionaries.invalidateSubCache()
	}

	@Test
	fun `lo que sale de una regla no se sustituye otra vez`() {
		val entries = listOf(
			"a b" to "a",
			"a" to "x"
		)
		assertEquals("a", Dictionaries.substitute("a b", entries))
	}

	@Test
	fun `la frase gana a la palabra en el mismo comienzo`() {
		val entries = listOf(
			"San Francisco" to "San Francisco",
			"San" to "Santo"
		)
		assertEquals(
			"San Francisco",
			Dictionaries.substitute("San Francisco", entries)
		)
	}

	@Test
	fun `dos aciertos no se solapan y el resto queda igual`() {
		val entries = listOf(
			"New York" to "Nueva York",
			"York" to "Yorkshire"
		)
		assertEquals(
			"Nueva York y Yorkshire",
			Dictionaries.substitute("New York y York", entries)
		)
	}

	@Test
	fun `el reinicio solo se añade a una pronunciación con anotaciones`() {
		val annotated = listOf("hola" to "`vb hola")
		assertEquals(
			"`vb hola `v0",
			Dictionaries.substitute("hola", annotated, "`v0")
		)
		val plain = listOf("hola" to "ola")
		assertEquals("ola", Dictionaries.substitute("hola", plain, "`v0"))
	}
}
