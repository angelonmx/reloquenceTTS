package com.angelonmx.reloquence

import java.util.concurrent.atomic.AtomicLong

/**
 * 5.0: contadores del motor, sin texto hablado ni datos personales.
 *
 * Los lee la pantalla de diagnóstico y los escribe el hilo de
 * síntesis. Todo es atómico o volátil para que una lectura desde la
 * interfaz no tenga que esperar a que termine una emisión.
 */
object TtsHealth {
	data class Snapshot(
		val utterances: Long,
		val completed: Long,
		val cancelled: Long,
		val failures: Long,
		val recoveries: Long,
		val watchdogTrips: Long,
		val lastLatencyMs: Long,
		val lastError: String,
		val engineVersion: String,
		val ready: Boolean,
		val speaking: Boolean,
		val activeLanguage: Int
	)

	private val utterances = AtomicLong()
	private val completed = AtomicLong()
	private val cancelled = AtomicLong()
	private val failures = AtomicLong()
	private val recoveries = AtomicLong()
	private val watchdogTrips = AtomicLong()

	@Volatile
	private var lastLatencyMs = 0L

	@Volatile
	private var lastError = ""

	@Volatile
	private var engineVersion = ""

	@Volatile
	private var ready = false

	@Volatile
	private var speaking = false

	@Volatile
	private var activeLanguage = 0

	fun utterance(): Long = utterances.incrementAndGet()

	fun completed() = completed.incrementAndGet()

	fun cancelled() = cancelled.incrementAndGet()

	fun recovered() = recoveries.incrementAndGet()

	fun watchdog() = watchdogTrips.incrementAndGet()

	fun failed(error: String) {
		lastError = error.take(160)
		failures.incrementAndGet()
	}

	fun latency(ms: Long) {
		if (ms in 0..600_000) lastLatencyMs = ms
	}

	fun engineVersion(value: String) {
		engineVersion = value
	}

	fun service(ready: Boolean) {
		this.ready = ready
	}

	fun speaking(value: Boolean, language: Int = activeLanguage) {
		speaking = value
		if (value) activeLanguage = language
	}

	fun snapshot(): Snapshot = Snapshot(
		utterances.get(),
		completed.get(),
		cancelled.get(),
		failures.get(),
		recoveries.get(),
		watchdogTrips.get(),
		lastLatencyMs,
		lastError,
		engineVersion,
		ready,
		speaking,
		activeLanguage
	)
}
