package com.angelonmx.reloquence

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 4.3: que una copia de seguridad traiga todo lo que la app sabe
 * guardar.
 *
 * La puerta de [SettingsModel.applyParsed] es una lista de claves
 * escrita a mano. Lo que no está en ella se descarta sin avisar, y
 * la app se queda como estaba: el usuario importa una copia, la
 * importa bien, y un ajuste no cambia. No hay error ni aviso, solo
 * que "no funciona".
 *
 * Pasó con cinco ajustes de 4.3 --los bits del lofi, la profundidad
 * del anti-siseo, el tamaño de la sala, la cinta y el grave
 * profundo-- porque se añadieron a [Settings] y no a la lista.
 *
 * Esta prueba lee el código fuente y no el comportamiento, y es a
 * propósito: el olvido no era de ejecución sino de catálogo, y
 * comprobarlo en caliente exigiría un aparato y una SharedPreferences
 * de verdad. Lo que se puede comprobar en la máquina es que las dos
 * listas digieran lo mismo, y eso es justo lo que se rompió.
 */
class BackupGateTest {

	private val source = File("src/main/kotlin/com/angelonmx/reloquence")

	private fun text(name: String): String {
		val file = File(source, name)
		assertTrue("no encuentro ${file.path}", file.exists())
		return file.readText()
	}

	@Test
	fun `every key the app declares is one the import will accept`() {
		// Las claves con su valor, tal y como se declaran.
		val declared = Regex("""const val (KEY_\w+)\s*=\s*"([^"]*)"""")
			.findAll(text("Settings.kt"))
			.associate { it.groupValues[1] to it.groupValues[2] }
		assertTrue("no he encontrado ninguna clave en Settings.kt", declared.isNotEmpty())

		val model = text("SettingsModel.kt")
		val start = model.indexOf("private val KNOWN_KEYS")
		assertTrue("no encuentro la lista de la puerta en SettingsModel.kt", start >= 0)
		val block = model.substring(start).substringBefore("\n\t)")

		// De la lista se dejan fuera las de nombre compuesto, que se
		// aceptan por prefijo y no por igualdad: las diez bandas del
		// ecualizador y los parámetros de voz, que acaban en
		// "_param<n>".
		val allowed = Regex("""Settings\.(KEY_\w+)""").findAll(block)
			.map { it.groupValues[1] }.toSet()
		val byPrefix = listOf("KEY_EQ_BAND")

		val dropped = declared.keys
			.filter { it !in allowed && it !in byPrefix }
			.sorted()

		assertTrue(
			"la puerta de importación tira estas claves, así que una copia " +
				"las trae y la app se las guarda sin decir nada:\n  " +
				dropped.joinToString("\n  ") { key ->
					"$key = ${declared[key]}"
				} + "\n\nCada una necesita su línea en KNOWN_KEYS de SettingsModel.",
			dropped.isEmpty()
		)
	}

	@Test
	fun `every key the gate knows has exactly one expected type`() {
		val model = text("SettingsModel.kt")
		fun keysIn(name: String): Set<String> {
			val start = model.indexOf("private val $name")
			assertTrue("no encuentro $name en SettingsModel.kt", start >= 0)
			val block = model.substring(start).substringBefore("\n\t)")
			return Regex("""Settings\.(KEY_\w+)""").findAll(block)
				.map { it.groupValues[1] }.toSet()
		}

		val booleans = keysIn("BOOL_KEYS")
		val integers = keysIn("INT_KEYS")
		val strings = keysIn("STRING_KEYS")
		val known = keysIn("KNOWN_KEYS")
		val typed = booleans + integers + strings

		val untyped = (known - typed).sorted()
		assertTrue(
			"estas claves entran por KNOWN_KEYS pero expectedType() no " +
				"sabe su tipo, así que el lector las tira:\n  " +
				untyped.joinToString("\n  "),
			untyped.isEmpty()
		)
		val undeclared = (typed - known).sorted()
		assertTrue(
			"estas claves tienen tipo pero no están en KNOWN_KEYS:\n  " +
				undeclared.joinToString("\n  "),
			undeclared.isEmpty()
		)
		val duplicated = ((booleans intersect integers) +
			(booleans intersect strings) + (integers intersect strings)).sorted()
		assertTrue(
			"una clave no puede tener dos tipos:\n  " +
				duplicated.joinToString("\n  "),
			duplicated.isEmpty()
		)

		val expected = model.substring(model.indexOf("private fun expectedType"))
			.substringBefore("\n\t}")
		assertTrue(
			"expectedType() tiene que aceptar las bandas por prefijo",
			expected.contains("startsWith(Settings.KEY_EQ_BAND)")
		)
		assertTrue(
			"expectedType() tiene que aceptar los parámetros de voz por prefijo",
			expected.contains("startsWith(\"voice\")") && expected.contains("_param")
		)
		assertTrue(
			"expectedType() debe consultar las tres listas de tipos",
			expected.contains("in BOOL_KEYS") &&
				expected.contains("in INT_KEYS") &&
				expected.contains("in STRING_KEYS")
		)
	}

	@Test
	fun `the gate accepts the prefixes it claims to accept`() {
		// Las bandas del ecualizador y los parámetros de voz se
		// comprueban con `startsWith`, no con igualdad. Si alguien
		// cambia el nombre de una de las dos sin cambiar la regla, la
		// prueba de arriba pasa y estas dos no.
		val model = text("SettingsModel.kt")
		val gate = model.substring(model.indexOf("fun isKnownPrefKey"))
			.substringBefore("\n\t}\n")
		assertTrue(
			"la puerta debe seguir aceptando las bandas del ecualizador por prefijo",
			gate.contains("startsWith(Settings.KEY_EQ_BAND)")
		)
		assertTrue(
			"la puerta debe seguir aceptando los parámetros de voz por prefijo",
			gate.contains("startsWith(\"voice\")") && gate.contains("_param")
		)
	}

	@Test
	fun `restore clears before it writes so an import replaces and does not merge`() {
		// Es lo que hace que importar una copia sea reemplazar de
		// verdad. Si algún día se quita el `clear()`, un ajuste que la
		// copia no traiga se quedaría con el valor que hubiera, y el
		// usuario vería ajustes medio viejos y medio nuevos.
		val settings = text("Settings.kt")
		val restore = settings.substring(settings.indexOf("fun restorePrefs"))
			.substringBefore("\n\t}")
		assertTrue(
			"restorePrefs tiene que borrar antes de escribir, o una copia " +
				"mezcla en vez de reemplazar",
			restore.contains("prefs.edit().clear()")
		)
		assertTrue(
			"y tiene que volcar de verdad, no con apply(), porque un proceso " +
				"matado a mitad dejaría la reposición a medias",
			restore.contains("editor.commit()")
		)
	}
}
