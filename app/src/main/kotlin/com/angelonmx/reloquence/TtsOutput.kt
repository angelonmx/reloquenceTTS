package com.angelonmx.reloquence

import android.speech.tts.SynthesisCallback
import android.speech.tts.TextToSpeech
import timber.log.Timber

/**
 * 5.0: la pista de una emisión, con un solo final.
 *
 * El framework es muy estricto: `start`, `audioAvailable`, `done` y
 * `error` se llaman en ese orden, y después de `done` o `error` no se
 * toca el callback. Antes, una parada, un motor caído y una excepción
 * podían competir por cerrar la misma pista; ahora la primera llamada
 * gana y las demás no hacen nada.
 */
internal class TtsOutput(private val cb: SynthesisCallback) {
	private val born = System.nanoTime()

	private var began = false
	private var finished = false
	private var abandoned = false
	private var openedNs = 0L

	var handedBytes = 0L
		private set

	val started: Boolean get() = began
	val doneOrFailed: Boolean get() = finished
	val gone: Boolean get() = abandoned
	val maxBufferSize: Int get() = cb.maxBufferSize
	val openedAfterMs: Long
		get() = if (openedNs == 0L) 0 else (openedNs - born) / 1_000_000L

	/** Abre la pista una sola vez. */
	fun open(rateHz: Int): Boolean {
		if (finished || abandoned) return false
		if (began) return true
		val rate = rateHz.coerceIn(4_000, 192_000)
		return try {
			began = cb.start(rate, android.media.AudioFormat.ENCODING_PCM_16BIT, 1) ==
				TextToSpeech.SUCCESS
			if (began) {
				openedNs = System.nanoTime()
				true
			} else {
				// Un start rechazado deja el callback inservible; no se
				// le manda un error encima.
				finished = true
				abandoned = true
				false
			}
		} catch (e: Throwable) {
			finished = true
			abandoned = true
			Timber.w(e, "la pista rechazó el arranque")
			false
		}
	}

	/**
	 * Entrega PCM16 mono. Nunca entrega más de lo que el callback
	 * declara como máximo: el límite lo aplica quien llama.
	 */
	fun audio(buffer: ByteArray, offset: Int, count: Int): Boolean {
		if (finished || abandoned || !began) return false
		if (count <= 0) return true
		if (offset < 0 || count > buffer.size - offset) {
			Timber.e("audio fuera de rango: offset=%d count=%d size=%d", offset, count, buffer.size)
			return false
		}
		// PCM16: un byte suelto no es una muestra. El motor nunca lo
		// entrega, pero recortarlo aquí evita que un lector estricto
		// rechace el bloque entero.
		val size = count and 1.inv()
		if (size <= 0) return true
		return try {
			if (cb.audioAvailable(buffer, offset, size) == TextToSpeech.SUCCESS) {
				handedBytes += size
				true
			} else {
				finished = true
				abandoned = true
				false
			}
		} catch (e: Throwable) {
			finished = true
			abandoned = true
			Timber.w(e, "el framework soltó la pista")
			false
		}
	}

	/** Cierra una emisión correcta. Si no había audio, abre y cierra. */
	fun finishOk(rateHz: Int): Boolean {
		if (finished) return false
		if (!began && !open(rateHz)) return false
		finished = true
		return try {
			cb.done()
			true
		} catch (e: Throwable) {
			abandoned = true
			Timber.w(e, "no se pudo cerrar la pista")
			false
		}
	}

	/**
	 * Cierra con error. Si el framework ya soltó la pista, no se llama
	 * `error`: sería una segunda terminal sobre un callback muerto.
	 */
	fun fail(code: Int): Boolean {
		if (finished) return false
		finished = true
		if (abandoned) return false
		return try {
			cb.error(code)
			true
		} catch (e: Throwable) {
			abandoned = true
			Timber.w(e, "no se pudo cerrar la pista con error")
			false
		}
	}

	/** El framework pidió parar o ya no hay a quién contestar. */
	fun abandon() {
		if (finished) return
		finished = true
		abandoned = true
	}
}
