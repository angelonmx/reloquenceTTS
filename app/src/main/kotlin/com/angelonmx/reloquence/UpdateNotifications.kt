package com.angelonmx.reloquence

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 5.4: el aviso de que hay una versión nueva.
 *
 * Es un canal aparte del de servicio (`reloquence_service`) para
 * que quien quiera silenciar los avisos de actualización no
 * silencie también la notificación permanente del motor, y al
 * revés.
 *
 * Si el usuario no dio permiso de notificaciones, no se avisa: la
 * comprobación sigue estando en Ajustes y no se insiste.
 */
object UpdateNotifications {

	const val CHANNEL = "reloquence_updates"
	private const val ID_UPDATE = 5401
	private const val ID_FAILED = 5402

	fun updateAvailable(context: Context, info: UpdateInfo) {
		if (!AppPermissions.notificationsOn(context)) return
		ensureChannel(context)
		val text = context.getString(
			R.string.update_notification_text,
			info.versionName,
			BuildConfig.VERSION_NAME
		)
		val notification = NotificationCompat.Builder(context, CHANNEL)
			.setSmallIcon(R.drawable.ic_launcher_foreground)
			.setContentTitle(context.getString(R.string.update_notification_title))
			.setContentText(text)
			.setStyle(NotificationCompat.BigTextStyle().bigText(text))
			.setContentIntent(openUpdates(context, ID_UPDATE))
			.setAutoCancel(true)
			.setPriority(NotificationCompat.PRIORITY_DEFAULT)
			.build()
		runCatching {
			NotificationManagerCompat.from(context).notify(ID_UPDATE, notification)
		}
	}

	fun installFailed(context: Context) {
		if (!AppPermissions.notificationsOn(context)) return
		ensureChannel(context)
		val text = context.getString(R.string.update_notification_failed_text)
		val notification = NotificationCompat.Builder(context, CHANNEL)
			.setSmallIcon(R.drawable.ic_launcher_foreground)
			.setContentTitle(context.getString(R.string.update_notification_failed_title))
			.setContentText(text)
			.setStyle(NotificationCompat.BigTextStyle().bigText(text))
			.setContentIntent(openUpdates(context, ID_FAILED))
			.setAutoCancel(true)
			.setPriority(NotificationCompat.PRIORITY_DEFAULT)
			.build()
		runCatching {
			NotificationManagerCompat.from(context).notify(ID_FAILED, notification)
		}
	}

	private fun openUpdates(context: Context, request: Int): PendingIntent {
		val intent = Intent(context, SettingsActivity::class.java)
			.putExtra(SettingsActivity.EXTRA_OPEN_UPDATES, true)
			.addFlags(
				Intent.FLAG_ACTIVITY_NEW_TASK or
					Intent.FLAG_ACTIVITY_CLEAR_TOP or
					Intent.FLAG_ACTIVITY_SINGLE_TOP
			)
		return PendingIntent.getActivity(
			context,
			request,
			intent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)
	}

	private fun ensureChannel(context: Context) {
		if (Build.VERSION.SDK_INT < 26) return
		val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
			as? NotificationManager ?: return
		manager.createNotificationChannel(
			NotificationChannel(
				CHANNEL,
				context.getString(R.string.update_notification_channel),
				NotificationManager.IMPORTANCE_DEFAULT
			).apply {
				description = context.getString(R.string.update_notification_channel_hint)
			}
		)
	}
}
