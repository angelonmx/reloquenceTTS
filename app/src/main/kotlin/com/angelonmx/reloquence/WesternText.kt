package com.angelonmx.reloquence

import java.text.Normalizer

/** Texto de Android convertido en lo que el motor lee.
 *
 *  El motor toma bytes simples en el juego de caracteres propio
 *  del idioma, que para cada idioma que IBM envió es el conjunto
 *  Occidental de Windows. UTF-16 no es una opción aquí: el motor
 *  permite el conjunto de caracteres amplio solo para chino,
 *  japonés y coreano, y pedirlo en inglés hace que el texto se
 *  lea como bytes y se detenga en el primer byte alto, que es
 *  un carácter de habla.
 *
 *  Así que cualquier cosa fuera de ese conjunto tiene que
 *  convertirse en algo dentro de él antes de que el motor la
 *  vea. Un lector de pantalla recibe comillas tipográficas,
 *  guiones, emoji y nombres en guiones que Eloquencia nunca
 *  conoció, y dejarlos caer en silencio es peor que decir lo
 *  más cercano.
 */
object WesternText {

	/** Los caracteres tipográficos que aparecen en texto normal
	 *  y tienen un equivalente sencillo digno de decir. */
	private val PLAIN = mapOf(
		'‘' to "'", '’' to "'", '‚' to "'", '‛' to "'",
		'“' to "\"", '”' to "\"", '„' to "\"", '‟' to "\"",
		'‐' to "-", '‑' to "-", '‒' to "-", '–' to "-",
		'—' to "-", '―' to "-", '−' to "-",
		'…' to "...", '•' to ".", '·' to ".",
		' ' to " ", ' ' to " ", ' ' to " ", '​' to "",
		'‌' to "", '‍' to "", '﻿' to "",
		'‹' to "<", '›' to ">", '«' to "\"", '»' to "\"",
		'′' to "'", '″' to "\"", '⁄' to "/", '∕' to "/",
		'×' to "x", '™' to " trademark ", '№' to " number ",
		'€' to " euro ", '£' to " pounds ", '¥' to " yen ",
		'≤' to " less than or equal to ", '≥' to " greater than or equal to ",
		'≠' to " not equal to ", '≈' to " about ", '∞' to " infinity ",
		'→' to " to ", '←' to " from ", '°' to " degrees "
	)

	private const val HIGHEST = 0xFF

	/**
	 * Los bytes para entregarle al motor, sin un terminador:
	 * el lado nativo añade uno, y un cero en cualquier lugar
	 * dentro terminaría el texto temprano, así que no hay
	 * ninguno en lo que vuelve.
	 */
	fun encode(text: String): ByteArray {
		// 5.1: Android puede entregar el texto descompuesto (NFD),
		// sobre todo cuando la tilde viene de otra app. El motor lee
		// un byte por carácter y no conoce las marcas combinantes, así
		// que "versio\u0301n" se convertía en "versio n" y perdía el
		// acento. Se compone primero y el motor recibe la letra que
		// espera, sin tocar las mayúsculas ni la puntuación.
		val composed = Normalizer.normalize(text, Normalizer.Form.NFC)
		val flat = StringBuilder(composed.length)
		var i = 0
		while (i < composed.length) {
			val c = composed[i]
			val plain = PLAIN[c]
			when {
				plain != null -> flat.append(plain)
				// 4.4: un cero termina el texto para el motor, así que
				// un cero suelto es la forma más corta de callarlo
				// entero. Ya se quitaba; ahora se quita con los demás
				// controles, porque el motor no los quiere en ninguna
				// parte.
				c.code == 0 -> flat.append(' ')
				// 4.4: los controles de ISO (0x01-0x1F y 0x7F) se
				// pasaban tal cual. [EmojiSpeech] ni los nombra, así
				// que llegaban enteros al motor sin que nadie los
				// hubiera tocado, y no hay forma de saber qué hace la
				// máquina con un texto que mezcla eso. Son bytes sin
				// letra detrás, que es exactamente lo que el módulo
				// no sabe analizar, así que un espacio es lo único
				// seguro. El tabulador y el salto de línea se
				// conservan como espacio también: separan palabras y
				// quitarlos juntaría dos que deben ir sueltas.
				Character.isISOControl(c) -> flat.append(' ')
				c.code <= HIGHEST -> flat.append(c)
				else -> flat.append(fallback(composed, i))
			}
			// 4.4: solo se avanza de dos en dos si el carácter es de
			// verdad un surrogate alto **seguido de uno bajo**. Antes
			// avanzaba dos en cuanto el primero fuese alto, y con un
			// alto suelto ("\uD83DA") eso se comía la A de después:
			// el texto no sonaba, se perdía. Un emoji de verdad
			// (alto + bajo) se avanza igual, porque el segundo carácter
			// es un bajo y la condición se cumple.
			i += if (isPairedHighSurrogate(composed, i)) 2 else 1
		}
		val out = ByteArray(flat.length)
		for (n in flat.indices) {
			val c = flat[n].code
			out[n] = if (c in 1..HIGHEST) c.toByte() else ' '.code.toByte()
		}
		return out
	}

	/** Un surrogate alto que sí lleva su bajo detrás, o sea un
	 *  carácter de más de un byte. */
	private fun isPairedHighSurrogate(text: String, at: Int): Boolean =
		Character.isHighSurrogate(text[at]) &&
			at + 1 < text.length &&
			Character.isLowSurrogate(text[at + 1])

	/**
	 * Qué decir por un carácter que el juego de caracteres
	 * no tiene lugar para. Un acento que se descompone cede
	 * su letra base, lo que convierte un nombre como Dvořák en
	 * uno que el motor puede leer; todo lo demás se convierte
	 * en un espacio en lugar de un ruido.
	 */
	private fun fallback(text: String, at: Int): String {
		val one = if (isPairedHighSurrogate(text, at)) {
			text.substring(at, at + 2)
		} else {
			text[at].toString()
		}
		val stripped = Normalizer.normalize(one, Normalizer.Form.NFD)
			.filter { it.code <= HIGHEST && !isCombining(it) }
		return if (stripped.isEmpty()) " " else stripped
	}

	private fun isCombining(c: Char): Boolean = when (Character.getType(c).toByte()) {
		Character.NON_SPACING_MARK,
		Character.COMBINING_SPACING_MARK,
		Character.ENCLOSING_MARK -> true
		else -> false
	}
}
