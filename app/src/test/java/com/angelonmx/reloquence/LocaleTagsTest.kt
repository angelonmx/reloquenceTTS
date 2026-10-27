package com.angelonmx.reloquence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 5.0: las etiquetas que ve Android.
 *
 * [Eci.localeOf] sigue en ISO-3 porque es lo que manda y contesta la
 * interfaz de `TextToSpeech`. Lo que se **anuncia** es otra cosa:
 * `EXTRA_AVAILABLE_VOICES`, `onGetVoices` y el nombre de cada voz
 * llevan BCP-47. Antes llevaban el nombre del módulo ("spa-MEX",
 * "jpn-JPN"), que no es una etiqueta, y por eso los selectores de voz
 * de terceros no encontraban el japonés y el español aparecía como
 * México aunque el módulo fuera el latinoamericano.
 */
class LocaleTagsTest {

	private val englishUs = 0x00010000
	private val englishGb = 0x00010001
	private val spanishEs = 0x00020000
	private val spanishLatam = 0x00020001
	private val frenchFr = 0x00030000
	private val frenchCa = 0x00030001
	private val german = 0x00040000
	private val italian = 0x00050000
	private val japanese = 0x00080000

	private val all = listOf(
		englishUs, englishGb, spanishEs, spanishLatam,
		frenchFr, frenchCa, german, italian, japanese
	)

	@Test
	fun `every module advertises a real BCP-47 tag`() {
		val expected = mapOf(
			englishUs to "en-US",
			englishGb to "en-GB",
			spanishEs to "es-ES",
			spanishLatam to "es-419",
			frenchFr to "fr-FR",
			frenchCa to "fr-CA",
			german to "de-DE",
			italian to "it-IT",
			japanese to "ja-JP"
		)
		for ((language, tag) in expected) {
			assertEquals(tag, Eci.bcp47Of(language))
		}
	}

	@Test
	fun `japanese is ja-JP and not the old jpn-JPN`() {
		// El defecto que se veía en el selector: Android construía
		// Locale("jpn", "JPN"), que no casa con nada que pida un
		// lector de pantalla.
		assertEquals("ja-JP", Eci.bcp47Of(japanese))
		assertNotEquals("jpn-JPN", Eci.bcp47Of(japanese))
		assertEquals("Japanese", Locale.forLanguageTag(Eci.bcp47Of(japanese)!!).getDisplayLanguage(Locale.ENGLISH))
	}

	@Test
	fun `every advertised tag parses to the locale it says`() {
		// Si el idioma del Locale queda vacío, Android no puede
		// emparejar la voz con lo que pide nadie.
		for (language in all) {
			for (tag in Eci.tagsOf(language)) {
				val locale = Locale.forLanguageTag(tag)
				assertTrue("«$tag» no da un idioma", locale.language.isNotEmpty())
				assertFalse("«$tag» no puede llevar el nombre del módulo", tag.contains("spa"))
			}
		}
	}

	@Test
	fun `latin american spanish advertises only its module tag`() {
		// 5.2: la variante `es-MX` se retiró. El módulo `esus` es
		// español latinoamericano y se anuncia una sola vez.
		assertEquals(listOf("es-419"), Eci.tagsOf(spanishLatam))
		assertEquals(listOf("es-ES"), Eci.tagsOf(spanishEs))
		assertEquals(listOf("ja-JP"), Eci.tagsOf(japanese))
		assertFalse(Eci.tagsOf(spanishLatam).contains(Eci.RETIRED_MEXICAN_TAG))
	}

	@Test
	fun `Mexico is no longer a country the engine maps`() {
		// El país llega en ISO-3 desde Android: MEX. Ya no hay
		// etiqueta mexicana que lo reclame; la región del módulo es
		// la M.49 419.
		assertNull(Eci.tagCountry(Eci.RETIRED_MEXICAN_TAG))
		assertEquals("419", Eci.tagCountry("es-419"))
		assertEquals("ESP", Eci.tagCountry("es-ES"))
	}

	@Test
	fun `a stored mexican tag migrates to latin american spanish`() {
		// Mitad de la migración: una etiqueta suelta que quedó
		// guardada se lee como la del módulo, sin tocar las demás.
		assertEquals("es-419", Eci.migrateTag("es-MX"))
		assertEquals("es-419", Eci.migrateTag("es-mx"))
		assertEquals("es-ES", Eci.migrateTag("es-ES"))
		assertEquals("en-US", Eci.migrateTag("en-US"))
		assertNull(Eci.migrateTag(null))
	}

	@Test
	fun `a stored mexican voice name migrates and keeps its preset`() {
		// La otra mitad: los nombres anunciados antes eran
		// `es-MX-<preset>`. Se leen como `es-419-<preset>`, que es
		// el mismo módulo y el mismo preset.
		assertEquals("es-419-Reed", Eci.migrateVoiceName("es-MX-Reed"))
		assertEquals("es-419-Reed", Eci.migrateVoiceName("es-mx-Reed"))
		// Una etiqueta suelta no es un nombre de voz: se queda como
		// está y la migra [Eci.migrateTag].
		assertEquals("es-MX", Eci.migrateVoiceName("es-MX"))
		assertEquals("es-419-", Eci.migrateVoiceName("es-MX-"))
		assertEquals("es-ES-Reed", Eci.migrateVoiceName("es-ES-Reed"))
		assertEquals("en-US-Reed", Eci.migrateVoiceName("en-US-Reed"))
		assertNull(Eci.migrateVoiceName(null))
	}
}
