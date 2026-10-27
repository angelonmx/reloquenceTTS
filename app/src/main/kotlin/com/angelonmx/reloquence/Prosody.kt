package com.angelonmx.reloquence

/**
 * Lo que va por delante de una emisión para poner al motor
 * en el estado en que debe hablarla.
 *
 * ## 4.4: la predicción de frase va APAGADA, y antes iba encendida
 *
 * Este archivo afirmaba que `` `pp `` "no hace nada en este motor":
 * que `eci_text.c` lo guarda en `env[11]` y que nada lo lee jamás.
 * **Eso es falso, y era el origen de la entonación extraña en
 * español.**
 *
 * Está leído, y en tres sitios por idioma. La cadena completa es:
 *
 *  - `` `ppN `` llega a `eci_text.c:406-418`, que lo guarda en
 *    `env[11]`;
 *  - `ev_sendChangedEnvironment` (`src/eci/api/eci_env.c:504-507`)
 *    manda `env[11]` como el parámetro 13 del motor;
 *  - `eci_state.c:328-331` lo convierte otra vez en el texto
 *    `` `ppN ``, y así llega a las reglas del idioma;
 *  - y las reglas leen la bandera en tres puntos. En español, uno
 *    de ellos es el bloque de `build_phrases`
 *    (`lang/eses/rules/ut_inton.dr:7256-7284`), y ese bloque es
 *    justo `mark_potential_phrase_breaks` seguido de
 *    `insert_unpuncted_phrases`.
 *
 * O sea: `pp1` le dice al motor que **adivine las fronteras de
 * frase donde no hay puntuación**, y que después le dé a cada
 * frase la entonación que le corresponde. En un lector de pantalla
 * casi todas las emisiones son fragmentos sin puntuación, así que
 * esa adivinanza acierta poco y aun así se oye: de ahí el tono
 * estirado y expresivo.
 *
 * Por qué solo se quejaba el español, siendo el mismo código: las
 * reglas españolas hacen mucho más de ese trabajo. En `eses`,
 * `assign_unpuncted_phr_vals` ocupa 187 líneas frente a las 82 de
 * `enus`, e `insert_unpuncted_phrases` 572 frente a 508. El módulo
 * inglés apenas nota la diferencia; el español la convierte en una
 * voz que canta. En inglés nunca se THAT noticed porque ahí el
 * efecto es pequeño, no porque esté bien.
 *
 * ## Lo que NO se rompe al apagarlo
 *
 * Las pausas y los contornos que salen de la puntuación **no**
 * dependen de `pp`: viven en otras reglas, que se siguen ejecutando
 * igual. `delimit_sentence` (`ut_inton.dr:4`), `period_phrase`
 * (`:948`), `comma_phrase` (`:4867`), `question_phrase` (`:6688`),
 * `exclam_phrase` (`:5031`), `semicolon_phrase` (`:1171`) y
 * `insert_phrase_pause` (`:3459`) no miran la bandera. Lo que se
 * apaga es exclusivamente la invención de fronteras donde el
 * usuario no puso nada.
 *
 * Es justo lo que pedía el README desde el principio ("se apaga,
 * porque en un lector de pantalla la línea suele ser un fragmento y
 * la adivinanza es incorrecta") y lo que nunca se aplicó, porque se
 * añadió `pp1` explícito creyendo que era un no-op.
 *
 * El otro sitio de la bandera, `handle_non_phrasal_commas`
 * (`ut_inton.dr:276`), se apaga con ella: una coma que no era
 * frontera deja de serlo, que es lo pedido.
 *
 * ## Por qué va en cada trozo y no una vez
 *
 * El motor aplica las anotaciones **al encolar**, y la bandera
 * `pp` es de las pocas que sobrevive al encolado. Si una emisión
 * se corta a mitad, el `pp` de esa emisión sigue puesto para la
 * siguiente. Por eso va por delante de cada trozo, igual que el
 * reinicio de voz de [EvvEngine.prosodyReset].
 */
object Prosody {

	/** Lo que poner por delante de una emisión: `pp0`, la
	 *  predicción de frase apagada, y la voz tal cual. */
	fun natural(): String = OFF

	/** La velocidad y el tono para hablar una emisión a, en
	 *  los números propios del motor. Una anotación mal formada
	 *  se pronuncia en lugar de obedecer, así que ambos se
	 *  limitan a lo que el motor aceptará. */
	fun voice(speed: Int, pitch: Int): String =
		"`vs${Eci.clampVoice(Eci.VOICE_SPEED, speed)} " +
			"`vb${Eci.clampVoice(Eci.VOICE_PITCH_BASELINE, pitch)} "

	private const val OFF = "`pp0 "

	/**
	 * 4.3: las anotaciones de voz del motor, y solo ellas.
	 *
	 * La lista no es inventada: sale de `es_paramToEngine` en
	 * `src/eci/api/eci_state.c`, que es donde el motor dice qué
	 * código es cada parámetro. `p` y `pp` son las pausas y la
	 * prosodia; `ts`, `ty` y `da` son marcas del texto; `v` con una
	 * letra es cada parámetro de la voz, y `v` con un número es
	 * cambiar de voz ([Dictionaries.hasVoiceSelect]).
	 *
	 * 4.4: `pp` sigue en la lista a propósito. Quitarlo de aquí no
	 * tocaría la voz, pero dejaría pasar un `pp` escrito a mano que
	 * volviera a encender la predicción de frase y desatara otra vez
	 * la entonación que [natural] acaba de apagar. La lista es de
	 * lo que hay que quitarle al usuario, y `pp` forma parte de eso.
	 *
	 * Deliberadamente es una lista cerrada y no "cualquier cosa
	 * detrás de un acento grave". Un patrón amplio se comería el
	 * texto que vaya detrás: en "`vspeak" se llevaría por delante
	 * "speak", que es una palabra y no un código. Lo que no esté en
	 * la lista se queda como estaba, que es lo que hacía el motor
	 * antes de que existiera este interruptor.
	 *
	 * 4.4: **la lista era cerrada en las letras, pero no exigía el
	 * número**, y eso lo ha cogido una prueba. Cerrada sí, pero el
	 * patrón terminaba en `\s*`, que admite cero espacios, así que en
	 * `` `vspeak `` casaba "`vs" y se comía la ele de "speak":
	 * quedaba "peak".
	 *
	 * La causa es que un código de estos **siempre lleva un número**
	 * detrás (`pp0`, `vs50`, `p80`, `v3`), así que se puede pedir el
	 * número en el patrón en vez de confiar en lo que haya detrás. La
	 * `p` es la única que puede ir sola --`Pauses.AT_THE_END` la
	 * escribe así-- y por eso es la única con `\d*` en vez de `\d+`.
	 *
	 * Con esto, "`vspeak" se queda entero y "`vs50 " sigue siendo un
	 * código. La promesa del README era correcta; el patrón no la
	 * cumplía.
	 */
	private val CODES = Regex(
		"`(?:(?:pp|ts|ty|da|vs|vb|vf|vh|vr|vy|vv|vg)\\d+|v\\d+|p\\d*|eciDictionary)\\s*"
	)

	/**
	 * 4.3: [text] sin las anotaciones de voz, para quien no quiere
	 * que un texto o un diccionario cambien la voz.
	 *
	 * Un acento grave es un código de elocución para el motor, y el
	 * motor lo aplica **al encolar**: uno escrito a mano en el texto,
	 * o dentro de una entrada de diccionario compartida, mueve la
	 * voz a partir de ahí y la deja movida para lo que sigue. Es una
	 * capacidad real y es lo que el complemento de NVDA ofrece
	 * poder apagar; aquí también.
	 *
	 * Lo que se quita es el código, no el texto: la palabra sigue
	 * ahí y se dice como es. Quien lo quiere puesto lo deja puesto,
	 * que es lo que hace el interruptor apagado.
	 *
	 * Solo se llama sobre el texto del usuario y sobre lo que
	 * devuelven las reglas del diccionario, nunca sobre el reinicio
	 * de prosodia que esta misma app pone por delante ([voice] y
	 * [EvvEngine.prosodyReset]): esos son nuestros y se quedan.
	 */
	fun stripVoiceCodes(text: String): String {
		if ('`' !in text) return text
		return CODES.replace(text, " ")
	}
}