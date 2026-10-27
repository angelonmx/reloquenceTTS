package com.angelonmx.reloquence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** La referencia matemática interna viaja con la app y su licencia. */
class StixAssetsTest {

	@Test
	fun `STIX Two Math esta empaquetado`() {
		val font = File("src/main/assets/stix/STIXTwoMath-Regular.otf")
		assertTrue("falta la fuente STIX Two Math", font.exists())
		assertTrue("la fuente está vacía", font.length() > 500_000)
		val head = font.inputStream().use { it.readNBytes(4) }
		assertEquals("OTTO", String(head, Charsets.US_ASCII))
	}

	@Test
	fun `la licencia OFL acompana a la fuente`() {
		val license = File("src/main/assets/stix/OFL.txt")
		assertTrue("falta la licencia OFL", license.exists())
		assertTrue(
			"la licencia no es la SIL Open Font License",
			license.readText().contains("SIL OPEN FONT LICENSE", ignoreCase = true)
		)
	}
}
