package com.angelonmx.reloquence

/**
 * La interfaz JNI. Cada llamada aquí es segura para hilos
 * excepto [speak] y [read], que pertenecen al hilo que
 * impulsa una emisión.
 *
 * [EvvBridge] se separa para que [EvvEngine] se pueda probar en la
 * máquina sin la biblioteca nativa: las pruebas inyectan un doble
 * de prueba y no necesitan `libevvjni`.
 */
internal interface EvvBridge {

	fun create(language: Int): Long

	fun destroy(handle: Long)

	/**
	 * 4.2: despierta a quien esté esperando dentro de la instancia
	 * (el hilo del motor y un lector en [read]) **sin soltar nada**.
	 * Es lo que permite cerrar una instancia que está siendo leída:
	 * primero se aborta, luego se espera a que el lector salga, y solo
	 * entonces [destroy] libera.
	 */
	fun abort(handle: Long)

	fun speak(handle: Long, text: ByteArray): Boolean

	/** Llena [dst] con bytes PCM. Contesta 0 al final de la emisión
	 *  y -1 cuando se detuvo o algo salió mal. */
	fun read(handle: Long, dst: ByteArray): Int

	fun stop(handle: Long)

	fun setParam(handle: Long, param: Int, value: Int): Int

	fun setVoiceParam(handle: Long, voice: Int, param: Int, value: Int): Int

	fun getVoiceParam(handle: Long, voice: Int, param: Int): Int

	fun copyVoice(handle: Long, from: Int, to: Int): Int

	fun teachWord(handle: Long, volume: Int, key: ByteArray, say: ByteArray): Int

	fun forgetDictionaries(handle: Long)

	fun languages(): IntArray

	fun version(): String
}

internal object EvvNative : EvvBridge {

	@Volatile
	var loadError: Throwable? = null
		private set

	val loaded: Boolean

	init {
		var ok = false
		try {
			System.loadLibrary("evvjni")
			ok = true
		} catch (e: UnsatisfiedLinkError) {
			loadError = e
		}
		loaded = ok
	}

	override external fun create(language: Int): Long

	override external fun destroy(handle: Long)

	override external fun abort(handle: Long)

	override external fun speak(handle: Long, text: ByteArray): Boolean

	override external fun read(handle: Long, dst: ByteArray): Int

	override external fun stop(handle: Long)

	override external fun setParam(handle: Long, param: Int, value: Int): Int

	override external fun setVoiceParam(handle: Long, voice: Int, param: Int, value: Int): Int

	override external fun getVoiceParam(handle: Long, voice: Int, param: Int): Int

	override external fun copyVoice(handle: Long, from: Int, to: Int): Int

	override external fun teachWord(handle: Long, volume: Int, key: ByteArray, say: ByteArray): Int

	override external fun forgetDictionaries(handle: Long)

	override external fun languages(): IntArray

	override external fun version(): String
}
