package com.angelonmx.reloquence

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import timber.log.Timber

/**
 * 5.0: primer plano y notificación en un solo sitio.
 *
 * Antes la lógica vivía repartida entre el arranque, el hilo de
 * síntesis y la tarea eliminada. Aquí se reintenta con espera
 * creciente, se toleran Android 12+ (arranque en segundo plano),
 * Android 13 (permiso de notificaciones) y Android 14/15 (tipos de
 * servicio y tiempo máximo).
 */
internal class TtsForeground(private val service: Service) {
	@Volatile
	var isUp = false
		private set

	private var tries = 0
	private var lastTry = 0L

	@Synchronized
	fun start(reason: String) {
		if (isUp) return
		lastTry = SystemClock.elapsedRealtime()
		val built = build() ?: return
		val up = runCatching {
			if (Build.VERSION.SDK_INT >= 29) {
				service.startForeground(
					EvvTtsService.NOTIF_ID,
					built,
					ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
				)
			} else {
				@Suppress("DEPRECATION")
				service.startForeground(EvvTtsService.NOTIF_ID, built)
			}
		}.onFailure {
			Timber.w(it, "primer plano rechazado (%s)", reason)
		}
		isUp = up.isSuccess
		if (isUp) tries = 0
	}

	/** Se llama desde una emisión: no insiste más rápido que el plan. */
	@Synchronized
	fun ensure(reason: String) {
		if (isUp) return
		val now = SystemClock.elapsedRealtime()
		val wait = EvvTtsService.FOREGROUND_RETRY_STEPS_MS[
			tries.coerceIn(0, EvvTtsService.FOREGROUND_RETRY_STEPS_MS.size - 1)
		]
		if (tries > 0 && now - lastTry < wait) return
		if (tries < EvvTtsService.FOREGROUND_RETRY_STEPS_MS.size) tries++
		start("emisión")
	}

	@Synchronized
	fun stop() {
		if (!isUp) return
		isUp = false
		runCatching {
			if (Build.VERSION.SDK_INT >= 24) {
				service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
			} else {
				@Suppress("DEPRECATION")
				service.stopForeground(true)
			}
		}.onFailure { Timber.w(it, "no se pudo quitar el primer plano") }
	}

	fun timedOut() {
		isUp = false
		Timber.w("Android retiró el primer plano por tiempo; se deja de insistir")
	}

	private fun build(): Notification? = runCatching {
		val channel = "reloquence_service"
		if (Build.VERSION.SDK_INT >= 26) {
			val manager = service.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as? NotificationManager
			manager?.createNotificationChannel(
				NotificationChannel(
					channel,
					service.getString(R.string.notif_channel),
					NotificationManager.IMPORTANCE_LOW
				).apply {
					setShowBadge(false)
					description = service.getString(R.string.notif_text_ok)
				}
			)
		}
		val missing = AppPermissions.missing(service)
		val text = if (missing.isEmpty()) {
			service.getString(R.string.notif_text_ok)
		} else {
			service.getString(
				R.string.notif_text_missing,
				missing.joinToString(", ") { AppPermissions.label(service, it) }
			)
		}
		val open = PendingIntent.getActivity(
			service, 0,
			Intent(service, SettingsActivity::class.java)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)
		val builder = if (Build.VERSION.SDK_INT >= 26) {
			Notification.Builder(service, channel)
		} else {
			@Suppress("DEPRECATION")
			Notification.Builder(service)
		}
		builder
			.setContentTitle(service.getString(R.string.notif_title))
			.setContentText(text)
			.setSmallIcon(R.drawable.ic_launcher_foreground)
			.setContentIntent(open)
			.setCategory(Notification.CATEGORY_SERVICE)
			.setOnlyAlertOnce(true)
			.setShowWhen(false)
			.setOngoing(true)
		if (Build.VERSION.SDK_INT >= 31) {
			builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
		}
		builder.build()
	}.onFailure { Timber.w(it, "no se pudo armar la notificación") }.getOrNull()
}
