package com.angelonmx.reloquence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El lector de copias no necesita Android para la parte de ajustes.
 * Estas pruebas fijan el JSON de 5.0 y que el formato de líneas de
 * antes siga entrando, porque una copia vieja no se puede perder.
 */
class BackupFormatTest {

	@Test
	fun `el JSON conserva el tipo de cada ajuste`() {
		val parsed = BackupStore.parseJsonBackup(
			"""
			{
			  "format": "reloqbkp",
			  "version": 2,
			  "name": "prueba",
			  "prefs": {
			    "process_emojis": {"type": "B", "value": true},
			    "unicode_symbols": {"type": "B", "value": true},
			    "voice_preset": {"type": "I", "value": 4},
			    "eq_band_3": {"type": "I", "value": 3},
			    "app_locale": {"type": "S", "value": "es"},
			    "voice_prueba_param1": {"type": "I", "value": 37},
			    "clave_inventada": {"type": "B", "value": true}
			  },
			  "dictionary": {}
			}
			""".trimIndent()
		)
		assertTrue(parsed != null)
		assertEquals("prueba", parsed!!.name)
		assertEquals(true, parsed.prefs["process_emojis"])
		assertEquals(true, parsed.prefs["unicode_symbols"])
		assertEquals(4, parsed.prefs["voice_preset"])
		assertEquals(3, parsed.prefs["eq_band_3"])
		assertEquals("es", parsed.prefs["app_locale"])
		assertEquals(37, parsed.prefs["voice_prueba_param1"])
		assertTrue("una clave desconocida no puede entrar", "clave_inventada" !in parsed.prefs)
		assertEquals(
			BackupStore.IssueKind.UNKNOWN_PREF_KEYS,
			parsed.issues.single { it.kind == BackupStore.IssueKind.UNKNOWN_PREF_KEYS }.kind
		)
	}

	@Test
	fun `una version futura se avisa y no se puede importar`() {
		val future = """{"format":"reloqbkp","version":3,"prefs":{"volume":{"type":"I","value":50}}}"""
		val parsed = BackupStore.parseJsonBackup(future)
		assertNotNull(parsed)
		assertFalse(parsed!!.compatible)
		assertTrue(parsed.prefs.isEmpty())
		assertEquals(BackupStore.IssueKind.FUTURE_VERSION, parsed.issues.single().kind)
	}

	@Test
	fun `los tipos y rangos invalidos se avisan y se omiten`() {
		val bad = """
			{
			  "format":"reloqbkp",
			  "version":2,
			  "prefs":{
			    "process_emojis":{"type":"B","value":true},
			    "volume":{"type":"I","value":9999},
			    "sample_rate_hz":{"type":"S","value":"11025"},
			    "eq_band_1":{"type":"I","value":50}
			  },
			  "dictionary":{"ext":"zip","data":"AAAA"}
			}
		""".trimIndent()
		val parsed = BackupStore.parseJsonBackup(bad)
		assertNotNull(parsed)
		assertEquals(true, parsed!!.prefs["process_emojis"])
		assertNull(parsed.prefs["volume"])
		assertNull(parsed.prefs["sample_rate_hz"])
		assertNull(parsed.prefs["eq_band_1"])
		val kinds = parsed.issues.map { it.kind }.toSet()
		assertTrue(BackupStore.IssueKind.INVALID_PREF_ENTRIES in kinds)
		assertTrue(BackupStore.IssueKind.OUT_OF_RANGE_PREFS in kinds)
		assertTrue(BackupStore.IssueKind.DICT_FORMAT in kinds)
	}

	@Test
	fun `un JSON ajeno no pasa por copia`() {
		val foreign = """{"format":"otra-app","version":2,"prefs":{"process_emojis":{"type":"B","value":true}}}"""
		assertNull(BackupStore.parseJsonBackup(foreign))
	}

	@Test
	fun `una copia sin nada tampoco pasa`() {
		val empty = """{"format":"reloqbkp","version":2,"name":"x","prefs":{},"dictionary":{}}"""
		assertNull(BackupStore.parseJsonBackup(empty))
	}

	@Test
	fun `el formato de lineas anterior sigue entrando`() {
		val old = """
			# reloqbkp v1
			name=vieja
			[prefs]
			B|process_emojis|true
			I|voice_preset|3
			S|app_locale|es
			SS|dict_order|uno${'\u001F'}dos
			[dict]
		""".trimIndent()
		val parsed = BackupStore.parseLegacyBackup(old)
		assertTrue(parsed != null)
		assertEquals("vieja", parsed!!.name)
		assertEquals(true, parsed.prefs["process_emojis"])
		assertEquals(3, parsed.prefs["voice_preset"])
		assertEquals("es", parsed.prefs["app_locale"])
		assertEquals(setOf("uno", "dos"), parsed.prefs["dict_order"])
	}
}
