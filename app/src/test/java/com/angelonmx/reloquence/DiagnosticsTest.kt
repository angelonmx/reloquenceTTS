package com.angelonmx.reloquence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 5.3: el formato del TXT de diagnóstico.
 *
 * Lo que se comprueba aquí es lo que hace que el archivo se pueda
 * compartir: una línea por suceso con hora, categoría y pares
 * `clave=valor`; secciones por categoría; y la línea final que dice
 * que no lleva contenido del usuario. Nada de esto necesita Android.
 */
class DiagnosticsTest {

	@Test
	fun `una linea tiene hora, categoria y pares`() {
		val line = Diagnostics.formatLine(
			"2026-10-25T18:00:00.000-06:00",
			"prosody",
			listOf(
				"event" to "reset",
				"voice" to "3",
				"codes" to "`vs50 `vb60"
			)
		)
		assertEquals(
			"2026-10-25T18:00:00.000-06:00 prosody event=reset voice=3 codes=`vs50_`vb60",
			line
		)
	}

	@Test
	fun `un valor con espacios o saltos no parte la linea`() {
		val line = Diagnostics.formatLine(
			"2026-10-25T18:00:00.000-06:00",
			"error",
			listOf("frames" to "A.b(F.kt:1)\nC.d(F.kt:2)")
		)
		assertFalse("no puede haber salto de línea", line.contains("\n"))
		assertEquals(3, line.split(' ').size)
		assertTrue(line.endsWith("frames=A.b(F.kt:1)_C.d(F.kt:2)"))
	}

	@Test
	fun `el TXT lleva cabecera, secciones y la linea final`() {
		val header = listOf(
			"app" to "reloquenceTTS",
			"version" to "5.3",
			"edition" to "full"
		)
		val lines = listOf(
			"2026-10-25T18:00:00.000-06:00 lifecycle event=service_created",
			"2026-10-25T18:00:01.000-06:00 engine event=opened voice=2",
			"2026-10-25T18:00:02.000-06:00 lifecycle event=stop",
			"2026-10-25T18:00:03.000-06:00 prosody event=reset voice=2"
		)
		val text = Diagnostics.render(header, lines)

		assertTrue("empieza por el título", text.startsWith("# reloquenceTTS diagnostics\n"))
		assertTrue(text.contains("app=reloquenceTTS\n"))
		assertTrue(text.contains("version=5.3\n"))
		assertTrue(text.contains("edition=full\n"))
		assertTrue("una sección por categoría", text.contains("\n--- lifecycle ---\n"))
		assertTrue(text.contains("\n--- engine ---\n"))
		assertTrue(text.contains("\n--- prosody ---\n"))
		assertEquals(
			"la línea final va al final y sin nada detrás",
			"# No user content included. Safe to share.",
			text.trimEnd().lines().last()
		)
	}

	@Test
	fun `las lineas de una seccion conservan su orden`() {
		val lines = listOf(
			"2026-10-25T18:00:00.000-06:00 engine event=first",
			"2026-10-25T18:00:01.000-06:00 engine event=second",
			"2026-10-25T18:00:02.000-06:00 engine event=third"
		)
		val text = Diagnostics.render(emptyList(), lines)
		val first = text.indexOf("event=first")
		val second = text.indexOf("event=second")
		val third = text.indexOf("event=third")
		assertTrue("primero", first in 0 until second)
		assertTrue("después", second in 0 until third)
	}

	@Test
	fun `las lineas vacias no abren seccion`() {
		val text = Diagnostics.render(
			emptyList(),
			listOf("", "   ", "2026-10-25T18:00:00.000-06:00 lifecycle event=x")
		)
		assertTrue(text.contains("--- lifecycle ---"))
		assertFalse("no hay categoría vacía", text.contains("---  ---"))
	}

	@Test
	fun `la cabecera tambien se limpia`() {
		val text = Diagnostics.render(listOf("model" to "Pixel 9 Pro"), emptyList())
		assertTrue(text.contains("model=Pixel_9_Pro\n"))
	}

	@Test
	fun `el TXT agrupa los subsistemas que importan`() {
		val categories = listOf(
			"lifecycle", "engine", "settings", "prosody",
			"effects", "dictionary", "backup", "error"
		)
		val lines = categories.mapIndexed { at, category ->
			"2026-10-25T18:00:0$at.000-06:00 $category event=probe"
		}
		val text = Diagnostics.render(emptyList(), lines)
		for (category in categories) {
			assertTrue(
				"falta la sección de $category",
				text.contains("\n--- $category ---\n")
			)
		}
	}
}
