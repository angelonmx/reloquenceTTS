package com.angelonmx.reloquence

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 4.3: que al motor nunca se le pida un idioma que no tiene.
 *
 * Esto no era un error de Kotlin, que es lo que lo escondió. Abrir
 * un idioma que el binario no trae devuelve una manija rota y
 * revienta más adentro, en el hilo de síntesis: el proceso se muere,
 * Android reinicia el servicio, y lo que ve el usuario es que la voz
 * "se para y vuelve". Sin rastro de por qué.
 *
 * Se daba con la autodetección encendida y la app o el aparato en un
 * idioma que el motor no trae. La lista de la interfaz tiene diez
 * idiomas y el motor nueve, así que hay cuatro (polaco, portugués,
 * ruso y turco) donde no hay nada que case, y la cadena de reserva
 * acababa en `eciNewEx(0)`.
 *
 * Estas pruebas leen el código porque lo que hay que comprobar es un
 * crash nativo, y un crash nativo no se puede provocar en la maquina.
 * Lo que sí se puede comprobar es que la
 * puerta esta puesta y que nadie la quita por debajo.
 */
class LanguageSafetyTest {

	private fun text(name: String): String {
		val file = File("src/main/kotlin/com/angelonmx/reloquence/$name")
		assertTrue("no encuentro ${file.path}", file.exists())
		return file.readText()
	}

	@Test
	fun `open refuses to hand the engine a language the binary does not have`() {
		val engine = text("EvvEngine.kt")
		val open = engine.substring(engine.indexOf("fun open("))
			.substringBefore("\n\t\t}")
		assertTrue(
			"EvvEngine.open es el punto único por el que pasa todo y tiene " +
				"que comprobar contra la lista de `available` antes de llamar " +
				"a create()",
			open.contains("available.contains(want)") || open.contains("available.contains(language)")
		)
		assertTrue(
			"...y tiene que registrar el caso, que es como se explica",
			open.contains("no está en este motor")
		)
	}

	@Test
	fun `the instance records the language it was really opened with`() {
		// Si el constructor guarda el idioma pedido en vez del abierto,
		// la caché de motores compara contra un idioma que nadie está
		// hablando y abre otro en cada frase.
		val engine = text("EvvEngine.kt")
		val open = engine.substring(engine.indexOf("fun open("))
			.substringBefore("\n\t\t}")
		assertTrue(
			"la instancia tiene que llevar el idioma con el que se ha " +
				"abierto de verdad (`want`), no el que se pidió",
			open.contains("EvvEngine(handle, want,")
		)
		assertTrue(
			"...y no puede llevar el pedido, que es lo que pasaba",
			!open.contains("EvvEngine(handle, language,")
		)
	}

	@Test
	fun `the service has a door before the engine as well`() {
		// `ensureEngine` es la puerta del servicio. Con la de
		// `open` ya no revienta, pero abrir un motor para un idioma que
		// no existe y guardarlo en el mapa es un fallo que se arrastra
		// a todas las emisiones siguientes.
		val service = text("EvvTtsService.kt")
		val ensure = service.substring(service.indexOf("private fun ensureEngine("))
			.substringBefore("\n\t}")
		assertTrue(
			"ensureEngine tiene que comprobar el idioma antes de abrir",
			ensure.contains("EvvEngine.available.contains(language)")
		)
	}

	@Test
	fun `the language chain cannot end in zero`() {
		// La cadena de reserva acababa en `callback.error` con un 0
		// por el medio, y ese 0 se iba a `eciNewEx`. Ahora cae al
		// primer idioma que el binario declare.
		val service = text("EvvTtsService.kt")
		val chain = service.substring(service.indexOf("val language = detected"))
			.substringBefore("val safe =")
		assertTrue(
			"la cadena tiene un último escalón antes de `safe` que no " +
				"puede dejar pasar un idioma que el motor no tenga",
			chain.contains("EvvEngine.available.firstOrNull()")
		)
	}

	@Test
	fun `every interface language is checked against a real module`() {
		// La lista de la interfaz tiene más idiomas que el motor, y eso
		// está bien y es normal: un usuario en polaco prefiere que le
		// hablen en algo antes que el silencio. Lo que no puede pasar es
		// que la diferencia no se compruebe en ningún sitio.
		val model = text("SettingsModel.kt")
		assertTrue(
			"la lista de idiomas que se ofrece sale de lo que el motor " +
				"declara, no de una lista escrita a mano",
			model.contains("fun languages(): List<Int> = EvvEngine.available.toList()")
		)
	}
}
