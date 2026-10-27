package com.angelonmx.reloquence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4.4: cómo se llaman las dos variantes de español.
 *
 * Los módulos del motor son `eses` y `esus`, y sus países tal como
 * los tiene el sistema son "ESP" y "MEX". De ahí salía
 * "español (México)", que es lo que está mal escrito, y no por
 * descuido del texto sino porque [Eci.LOCALES] le ponía "MEX" a un
 * módulo que no es de México.
 *
 * `esus` es el **español latinoamericano de IBM**, de los años
 * noventa, y cubre todo lo que no es España. No es un país: por eso
 * el nombre va escrito y no sale de `Locale.getDisplayName`, que
 * además lo traducía al idioma del aparato y lo nombraba de tres
 * maneras distintas según cuál fuera.
 */
class LanguageNamesTest {

	private val spain = 0x00020000
	private val latin = 0x00020001
	private val britishEnglish = 0x00010001

	@Test
	fun `the two spanish variants do not share a name`() {
		// Lo primero: si los dos se llaman igual, el usuario no puede
		// elegir entre ellos en los ajustes.
		assertNotEquals(
			"España y Latinoamérica tienen que distinguirse",
			Eci.displayName(spain),
			Eci.displayName(latin)
		)
	}

	@Test
	fun `neither spanish variant is named after Mexico`() {
		// El nombre que se veía, y el que se quería quitar.
		for (language in listOf(spain, latin)) {
			val name = Eci.displayName(language)
			assertFalse(
				"«$name» sigue llamándose México, y no lo es",
				name.contains("Méxic", ignoreCase = true)
			)
		}
	}

	@Test
	fun `the two names are the ones that were asked for`() {
		assertEquals("Español (España)", Eci.displayName(spain))
		assertEquals("Español (Latinoamérica)", Eci.displayName(latin))
	}

	@Test
	fun `the names do not change with the language of the phone`() {
		// El bug de fondo: el nombre salía de Locale.getDisplayName,
		// que depende de Locale.getDefault(). En un teléfono en inglés
		// el mismo motor se llamaba "Spanish (Mexico)" y en alemán
		// "Spanisch (Mexiko)". El nombre del módulo es del módulo.
		val before = java.util.Locale.getDefault()
		try {
			val seen = mutableSetOf<String>()
			for (tag in listOf("en", "de", "fr", "es", "ja")) {
				java.util.Locale.setDefault(java.util.Locale.forLanguageTag(tag))
				seen += Eci.displayName(spain)
				seen += Eci.displayName(latin)
			}
			assertEquals(
				"el nombre del idioma del aparato no puede cambiarlo: ${seen.toList()}",
				setOf("Español (España)", "Español (Latinoamérica)"),
				seen
			)
		} finally {
			java.util.Locale.setDefault(before)
		}
	}

	@Test
	fun `the two variants still keep their own ISO codes`() {
		// Renombrar la etiqueta es una cosa y romper el emparejamiento
		// con Android es otra: el nombre de país sigue siendo el que
		// el motor declara, porque es lo que se le pasa a Android.
		// 5.0: el módulo latinoamericano ya no dice "MEX". 419 es la
		// región M.49 de Latinoamérica, que es lo que el módulo
		// cubre; "MEX" hacía que `onGetLanguage` contestara México.
		assertEquals("spa", Eci.localeOf(spain)?.first)
		assertEquals("ESP", Eci.localeOf(spain)?.second)
		assertEquals("spa", Eci.localeOf(latin)?.first)
		assertEquals("419", Eci.localeOf(latin)?.second)
	}

	@Test
	fun `the others are left as the system names them`() {
		// El arreglo es solo para los dos módulos de español: el resto
		// sigue saliendo de Locale, como siempre.
		val name = Eci.displayName(britishEnglish)
		assertTrue("el inglés sigue con su nombre de siempre: $name", name.isNotBlank())
		assertTrue("y no se le ha colado el arreglo: $name", !name.contains("Español"))
	}

	@Test
	fun `the unicode bit does not hide a variant`() {
		// El bit de juego de caracteres viaja en la palabra de idioma
		// (Eci.UNICODE_CODE_SET). Un nombre que se buscara con la
		// palabra entera no encontraría el dialecto.
		val odd = latin or Eci.UNICODE_CODE_SET
		assertEquals("el dialecto sigue siendo el mismo", "Español (Latinoamérica)", Eci.displayName(odd))
	}
}