package com.angelonmx.reloquence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4.4: la entonación, que era lo que sonaba a cantado en español.
 *
 * El bug de fondo no era de voz, de velocidad ni de los presets: los
 * ocho parámetros de los tres idiomas coinciden casi entero (ver los
 * ficheros `<tag>.settings` de cada módulo, donde solo cambia el
 * volumen en tres voces). Era una anotación que se mandaba encendida
 * en cada
 * emisión y que el motor sí lee.
 *
 * ## Lo que hace `` `pp ``
 *
 * `` `pp `` es la predicción de frase. El motor la lee en tres
 * sitios de las reglas de cada idioma, y uno de ellos es el bloque
 * de `build_phrases` (`lang/eses/rules/ut_inton.dr:7256`), que es
 * `mark_potential_phrase_breaks` seguido de `insert_unpuncted_phrases`.
 *
 * Traducido: le dice al motor que **adivine dónde acaba una frase
 * donde no hay puntuación**, y que le ponga a cada una la entonación
 * que le toca. En un lector de pantalla casi todo lo que se pide son
 * fragmentos sin puntuación, así que la adivinanza casi nunca acierta
 * y aun así se oye.
 *
 * Por qué solo se oía en español, siendo el mismo código: las reglas
 * españolas hacen mucho más de ese trabajo. En `eses`,
 * `assign_unpuncted_phr_vals` ocupa 187 líneas frente a las 82 de
 * `enus`, e `insert_unpuncted_phrases` 572 frente a 508. En inglés el
 * efecto es pequeño; en español se convierte en una voz estirada.
 *
 * Lo que se apaga NO son las pausas de la puntuación: esas salen de
 * `delimit_sentence`, `period_phrase`, `comma_phrase`,
 * `question_phrase` y `insert_phrase_pause`, que no miran la bandera.
 */
class ProsodyTest {

	@Test
	fun `phrase prediction goes out off`() {
		// El valor de fábrica del motor es 1 (eci_env_defaults.c, y
		// `es_synthlife.c` abre con "`pp1"), así que decir pp0 es
		// decir lo contrario de lo que diría por no decir nada.
		assertEquals(
			"la predicción de frase tiene que salir apagada, no por defecto",
			"`pp0 ",
			Prosody.natural()
		)
	}

	@Test
	fun `the annotation that used to go out is gone from every emission`() {
		val on = "`pp1"
		assertFalse(
			"pp1 enciende la invención de fronteras; no puede salir por delante",
			Prosody.natural().contains(on)
		)
	}

	@Test
	fun `it is still an annotation and not a bare number`() {
		// Sin el acento grave ni el espacio el motor lo pronuncia en
		// vez de obedecerlo, y se oye una 'p'/'pe'/'p' con números.
		val out = Prosody.natural()
		assertTrue("tiene que empezar por acento grave", out.startsWith("`"))
		assertTrue("el código va pegado al número", out.startsWith("`pp0"))
		assertTrue("y detrás va un espacio, que separa el código", out.endsWith(" "))
	}

	@Test
	fun `what goes in front of a piece is reset plus the flat prosody`() {
		// Es lo que compone el servicio: el reinicio de voz del motor
		// (EvvEngine.prosodyReset) y, detrás, la prosodia. El orden
		// importa: la voz primero, porque `pp` es de las pocas que
		// sobreviven al encolado y se aplica sobre lo que haya.
		val piece = "`vs50 `vb60 " + Prosody.natural() + "hola"
		assertEquals("`vs50 `vb60 `pp0 hola", piece)
	}

	@Test
	fun `the voice codes the user may have written are still stripped`() {
		// pp0 es nuestro, pero un `pp1` escrito a mano en un
		// diccionario compartido volvería a encender la predicción de
		// frase. Por eso `pp` sigue en la lista cerrada de [Prosody.CODES]
		// y no solo los códigos de voz: es lo que deja el interruptor
		// "ignorar códigos" con la entonación neutra de verdad.
		val said = Prosody.stripVoiceCodes("`pp1 hola `vs50 mundo `vr22 mas")
		assertFalse("el pp del usuario tiene que irse", said.contains("pp"))
		assertFalse("y con él el resto de la voz", said.contains("`v"))
		assertTrue("el texto se dice entero", said.contains("hola"))
		assertTrue("palabra por palabra", said.contains("mundo") && said.contains("mas"))
	}

	@Test
	fun `a loose backquote still keeps the word after it`() {
		// La lista es cerrada a propósito: un patrón amplio se comería
		// "speak" de "`vspeak", que es una palabra y no un código.
		val said = Prosody.stripVoiceCodes("`vspeak ahora")
		assertTrue("lo que no es un código se queda", said.contains("speak"))
	}
}