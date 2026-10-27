package com.angelonmx.reloquence

import java.util.concurrent.atomic.AtomicInteger

/**
 * 5.0: una emisión, con su propia marca de cancelación.
 *
 * Antes había un `stopped` global. Si `onStop` llegaba justo cuando
 * empezaba la emisión siguiente, la nueva nacía cancelada y el
 * framework se quedaba esperando un final. Con una sesión por
 * emisión, una parada solo puede alcanzar a la emisión que la vio
 * nacer.
 */
internal class TtsSession(val id: Long) {
	private val state = AtomicInteger(ACTIVE)

	val cancelled: Boolean get() = state.get() != ACTIVE
	val watchdog: Boolean get() = state.get() == WATCHDOG
	val alive: Boolean get() = state.get() == ACTIVE

	@Volatile
	var lastProgressNs = System.nanoTime()
		private set

	fun progress() {
		lastProgressNs = System.nanoTime()
	}

	fun cancel(fromWatchdog: Boolean = false): Boolean {
		val target = if (fromWatchdog) WATCHDOG else CANCELLED
		while (true) {
			if (state.get() != ACTIVE) return false
			if (state.compareAndSet(ACTIVE, target)) return true
		}
	}

	private companion object {
		const val ACTIVE = 0
		const val CANCELLED = 1
		const val WATCHDOG = 2
	}
}
