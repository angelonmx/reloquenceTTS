package com.angelonmx.reloquence

import timber.log.Timber

/**
 * 5.0: el texto se transforma por ventanas, no entero de golpe.
 *
 * Las transformaciones (emojis, paréntesis, puntuación, números,
 * correcciones) se hacían sobre todo el texto antes de entregar el
 * primer byte. En un correo largo eso retrasaba el arranque y podía
 * duplicar la memoria. Ahora cada ventana paga solo su parte, así la
 * primera frase suena antes y el pico de memoria no crece con el
 * tamaño de la entrada.
 */
internal class TtsTextPipeline(
	private val emojis: Boolean,
	private val unicodeSymbols: Boolean,
	private val punctOn: Boolean,
	private val punctLevel: Int,
	private val skipParens: Boolean,
	private val numbersOn: Boolean,
	private val numberGroup: Int,
	private val silentPunct: Boolean,
	private val pauseMs: Int,
	private val tag: String
) {
	fun apply(text: String): String {
		if (text.isEmpty()) return text
		var out = UnicodeText.normalize(text)
		if (emojis) out = step(out) { EmojiSpeech.describeLang(it, tag) }
		if (unicodeSymbols) out = step(out) { UnicodeSpeech.describeLang(it, tag) }
		if (skipParens) out = step(out) { TextFixes.withoutParens(it) }
		if (punctOn) out = step(out) { Punctuation.apply(it, true, punctLevel, tag) }
		if (silentPunct) out = step(out) { Punctuation.silence(it, pauseMs) }
		out = step(out) { TextFixes.apply(it, tag) }
		out = step(out) { Numbers.apply(it, numbersOn, numberGroup) }
		return out
	}

	private inline fun step(input: String, transform: (String) -> String): String {
		if (input.isEmpty()) return input
		return runCatching { transform(input) }
			.onFailure { Timber.w(it, "texto: falló un paso; se usa la entrada") }
			.getOrDefault(input)
	}
}
