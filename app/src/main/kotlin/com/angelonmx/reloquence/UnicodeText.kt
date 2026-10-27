package com.angelonmx.reloquence

import java.text.Normalizer

/**
 * Texto Unicode que llega de otras apps, antes de las reglas fijas
 * y del motor.
 *
 * El lector de pantalla recibe letras matemáticas, circuladas,
 * ligaduras y acentos descompuestos. El motor no conoce ninguna de
 * esas formas, así que se pasan a su equivalente Unicode normal
 * (NFKC): "𝐻𝑜𝑙𝑎" se convierte en "Hola", "①②③" en "123" y
 * "versio\u0301n" en "versión". La normalización va antes del
 * diccionario y de las transformaciones para que las reglas vean
 * las letras que esperan.
 *
 * Algunos símbolos tienen una descomposición NFKC que se diría peor
 * que su nombre ("™" sería "TM"). Esos se sustituyen primero por
 * las palabras que ya usa [WesternText].
 */
object UnicodeText {

	private val SPOKEN = mapOf(
		'\u2122' to " trademark ",
		'\u2116' to " number ",
		'\u2120' to " service mark ",
		'\u2103' to " degrees Celsius ",
		'\u2109' to " degrees Fahrenheit "
	)

	/** [text] en la forma que entienden las reglas y el motor. */
	fun normalize(text: String): String {
		if (text.isEmpty()) return text
		var out: StringBuilder? = null
		for (i in text.indices) {
			val c = text[i]
			val spoken = SPOKEN[c]
			if (spoken == null) {
				out?.append(c)
				continue
			}
			if (out == null) out = StringBuilder(text.length + 16).append(text, 0, i)
			out.append(spoken)
		}
		val protectedText = out?.toString() ?: text
		return Normalizer.normalize(protectedText, Normalizer.Form.NFKC)
	}
}
