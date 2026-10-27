package com.angelonmx.reloquence

/**
 * 5.0: ritmo de entrega.
 *
 * El reloj empieza con el primer trozo entregado, no al construir el
 * objeto. Antes se creaba antes de abrir el motor y la cuenta corría
 * mientras el motor cargaba: el servicio creía que iba retrasado y
 * encolaba audio de más.
 */
internal class TtsPacer(
	private val bytesPerSecond: Int,
	private val clock: () -> Long = System::nanoTime
) {
	private var started = false
	private var from = 0L
	private var bytes = 0L

	fun handed(more: Int) {
		if (more <= 0) return
		if (!started) {
			from = clock()
			started = true
		}
		bytes += more
	}

	fun handedMs(): Long =
		if (bytesPerSecond <= 0) 0 else bytes * 1000L / bytesPerSecond

	fun ahead(): Long {
		if (!started || bytesPerSecond <= 0) return 0
		val elapsed = (clock() - from) / 1_000_000L
		return handedMs() - elapsed
	}

	fun reset() {
		started = false
		from = 0L
		bytes = 0L
	}
}
