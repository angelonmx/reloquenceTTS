package com.angelonmx.reloquence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Los datos de CLDR y Unicode no se escriben a mano: se generan
 * con tools/generate_unicode_data.py. Esta prueba falla si alguien
 * olvida volver a generarlos o si el empaquetado los deja fuera.
 */
class CldrAssetsTest {

	private fun table(path: String): Map<String, String> {
		val file = File("src/main/assets/$path")
		assertTrue("falta $path", file.exists())
		val lines = GZIPInputStream(file.inputStream()).bufferedReader(Charsets.UTF_8)
			.use { it.readLines() }
		return lines.mapNotNull { line ->
			val tab = line.indexOf('\t')
			if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1)
		}.toMap()
	}

	@Test
	fun `las tablas CLDR traen emojis y secuencias`() {
		val es = table("cldr/emoji-es.tsv.gz")
		assertTrue("la tabla española está vacía", es.size > 1500)
		val grin = es["1F600"]
		assertNotNull("falta el emoji sonriente", grin)
		assertTrue(grin!!.contains("sonri", ignoreCase = true))
		val joined = es["1F636 200D 1F32B"]
		assertNotNull("faltan las secuencias con pegamento", joined)
		assertFalse("no debe haber tonos de piel sueltos", es.keys.any { "1F3FB" in it })
	}

	@Test
	fun `los nombres Unicode traen las flechas`() {
		val names = table("unicode/names.tsv.gz")
		assertTrue(names.size > 5000)
		assertTrue(names["2192"]?.contains("ARROW") == true)
		assertTrue(names["221E"]?.contains("INFINITY") == true)
	}

	@Test
	fun `el lector acepta el asset comprimido y el que deja AAPT`() {
		val text = "1F600\tcara sonriendo\n2192\tflecha\n"
		val gz = ByteArrayOutputStream().also { out ->
			GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
		}.toByteArray()
		assertEquals(listOf("1F600\tcara sonriendo", "2192\tflecha"), CldrData.decode(ByteArrayInputStream(gz)))
		assertEquals(listOf("1F600\tcara sonriendo", "2192\tflecha"), CldrData.decode(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))))
	}
}
