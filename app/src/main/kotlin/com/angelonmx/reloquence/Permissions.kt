package com.angelonmx.reloquence

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat

/**
 * Los permisos que mantienen vivo el servicio: batería sin
 * optimizar y notificaciones (la permanente del servicio).
 * El overlay es opcional y ya no se exige: un TTS no lo
 * necesita y pedirlo en cada apertura era ruido.
 */
object AppPermissions {

	const val BATTERY = "battery"
	const val NOTIFICATIONS = "notifications"
	const val OVERLAY = "overlay"

	/** Si la batería ya no optimiza a la app. */
	fun batteryIgnored(context: Context): Boolean {
		val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
			?: return true
		return pm.isIgnoringBatteryOptimizations(context.packageName)
	}

	/** Si se pueden mostrar notificaciones (incluida la permanente
	 *  del servicio). */
	fun notificationsOn(context: Context): Boolean {
		if (Build.VERSION.SDK_INT >= 33) {
			return ContextCompat.checkSelfPermission(
				context, Manifest.permission.POST_NOTIFICATIONS
			) == PackageManager.PERMISSION_GRANTED
		}
		val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
			as? android.app.NotificationManager
		return nm?.areNotificationsEnabled() ?: true
	}

	/** Si puede mostrarse sobre otras apps. */
	fun overlayOn(context: Context): Boolean =
		Settings.canDrawOverlays(context)

	/** Lo que falta, en el orden en que se pide. El overlay no
	 *  entra aquí: es opcional y vive en su apartado. */
	fun missing(context: Context): List<String> {
		val out = ArrayList<String>(2)
		if (!batteryIgnored(context)) out.add(BATTERY)
		if (!notificationsOn(context)) out.add(NOTIFICATIONS)
		return out
	}

	/** Si falta algo opcional (overlay), para avisarlo sin bloquear. */
	fun missingOptional(context: Context): List<String> {
		val out = ArrayList<String>(1)
		if (!overlayOn(context)) out.add(OVERLAY)
		return out
	}

	/** Etiqueta corta de cada permiso, para la alerta. */
	fun label(context: Context, key: String): String = when (key) {
		BATTERY -> context.getString(R.string.perm_battery)
		NOTIFICATIONS -> context.getString(R.string.perm_notifications)
		else -> context.getString(R.string.perm_overlay)
	}

	/** Abre el apartado del permiso [key]. Las notificaciones en
	 *  Android 13+ se piden en el momento con [notifyAsk]; lo demás
	 *  abre sus ajustes del sistema. Todo protegido: si el sistema
	 *  no trae ese apartado, no pasa nada. */
	fun open(context: Context, notifyAsk: ActivityResultLauncher<String>?, key: String) {
		when (key) {
			BATTERY -> runCatching {
				context.startActivity(Intent(
					Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
					Uri.parse("package:${context.packageName}")
				).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
			}.recoverCatching {
				context.startActivity(Intent(
					Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
				).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
			}
			NOTIFICATIONS -> {
				if (Build.VERSION.SDK_INT >= 33 && notifyAsk != null) {
					runCatching { notifyAsk.launch(Manifest.permission.POST_NOTIFICATIONS) }
				} else {
					runCatching {
						val intent = if (Build.VERSION.SDK_INT >= 26) {
							Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
								.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
						} else {
							Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
								.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
								.setData(Uri.parse("package:${context.packageName}"))
						}
						context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
					}
				}
			}
			else -> runCatching {
				context.startActivity(Intent(
					Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
					Uri.parse("package:${context.packageName}")
				).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
			}
		}
	}
}
