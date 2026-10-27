package com.angelonmx.reloquence

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4.3: los bytes que cada idioma le entrega a su motor.
 *
 * El motor lee un byte cada vez. Para los ocho idiomas de IBM ese
 * byte es el carácter, y [WesternText] se encarga. El japonés no
 * funciona así: su motor no habla UTF-8, lee Shift-JIS.
 *
 * Estas pruebas existen porque la diferencia no se veía. El JNI
 * llevaba un comentario diciendo que "Kotlin has already turned it
 * into that set" y no lo hacía nadie, así que el japonés no aparecía
 * en la lista de idiomas y todo parecía una decisión. Los bytes de
 * aquí son los que se midieron en el motor:
 *
 *     こんにちは  en UTF-8     ->      0 muestras
 *     こんにちは  en Shift-JIS ->  15.345 muestras
 */
class CodeSetTest {

	private val english = 0x00010000
	private val castilian = 0x00020000
	private val latam = 0x00020001
	private val japanese = 0x00080000

	@Test
	fun `only japanese needs a code set of its own`() {
		assertTrue(
			"el motor japonés lee Shift-JIS, no bytes sueltos",
			Eci.needsItsOwnCodeSet(japanese)
		)
		for (western in listOf(english, castilian, latam, 0x00030000, 0x00040000, 0x00050000)) {
			assertFalse(
				"un idioma de IBM lee el byte tal cual, y hay que usar WesternText",
				Eci.needsItsOwnCodeSet(western)
			)
		}
	}

	@Test
	fun `japanese text comes out as the shift-jis the engine wants`() {
		// こんにちは, byte a byte, que es como lo comprobamos en el
		// motor: こ 0x82B1, ん 0x82F1, に 0x82C9, ち 0x82BF, は 0x82CD.
		val out = Eci.encode("こんにちは", japanese)
		assertArrayEquals(
			"un kana son dos bytes con el mismo byte guía 0x82",
			byteArrayOf(
				0x82.toByte(), 0xB1.toByte(),
				0x82.toByte(), 0xF1.toByte(),
				0x82.toByte(), 0xC9.toByte(),
				0x82.toByte(), 0xBF.toByte(),
				0x82.toByte(), 0xCD.toByte()
			),
			out
		)
	}

	@Test
	fun `a kanji comes out as its own two bytes and not as a zero`() {
		// El bug de fondo era esto: UTF-8 da 0xE6 0x97 0xA5 para 日, y
		// el motor leía un cero en el medio y callaba. Shift-JIS da
		// 0x93 0xFA, que es lo que el motor busca.
		assertArrayEquals(
			"日 en UTF-8 son tres bytes y en Shift-JIS dos",
			byteArrayOf(0x93.toByte(), 0xFA.toByte()),
			Eci.encode("日", japanese)
		)
	}

	@Test
	fun `western text is still utf-8 and comes back unchanged`() {
		assertArrayEquals(
			"para los idiomas de IBM el camino no cambia",
			"¿Qué tal?".toByteArray(Charsets.UTF_8),
			Eci.encode("¿Qué tal?", castilian)
		)
	}

	@Test
	fun `punctuation that shift-jis has becomes its ascii rather than vanishing`() {
		// Las comillas tipográficas sí tienen equivalente en Shift-JIS,
		// y borrarlas deja la frase sin su interrogación, que es peor
		// que leerla con ASCII.
		val out = Eci.encode("“Hola”—¿qué?", japanese)
		val back = String(out, Charsets.ISO_8859_1)
		assertEquals("comillas rectas, guion, interrogación y acento quitado", "\"Hola\"-?que?", back)
	}

	@Test
	fun `what shift-jis has no room for is left out`() {
		// Un emoji no tiene dónde ir. [EmojiSpeech] lo cambia por
		// palabras antes, así que aquí solo se pierde lo que se
		// coló; lo que no puede ser es que se quede un cero, porque el
		// motor termina el texto en el primer cero.
		val out = Eci.encode("a😀b", japanese)
		assertFalse(
			"un emoji no puede dejar un cero, que el motor lo lee como fin",
			out.contains(0.toByte())
		)
		assertEquals("lo de alrededor se conserva", 2, out.size)
	}

	@Test
	fun `the same encoder can be used from two threads at once`() {
		// El codificador guarda estado entre llamadas, y `speak` se
		// llama desde el hilo de síntesis y desde el principal a la
		// vez. Esto sin candado devolvía bytes de otra frase.
		val text = "こんにちは"
		var failure: Throwable? = null
		val threads = (0 until 4).map { n ->
			Thread {
				try {
					repeat(300) {
						assertArrayEquals(
							"la frase $n llegó con bytes de otra",
							Eci.encode(text, japanese),
							Eci.encode(text, japanese)
						)
					}
				} catch (e: Throwable) {
					failure = e
				}
			}
		}
		threads.forEach(Thread::start)
		threads.forEach(Thread::join)
		if (failure != null) throw failure!!
	}
}
