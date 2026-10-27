package com.angelonmx.reloquence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 5.4: volver a programar la comprobación después de un reinicio.
 *
 * WorkManager conserva el trabajo que ya existía, pero si el proceso
 * arrancó en modo directo antes de desbloquear, [UpdateScheduler.sync]
 * no pudo programarlo: su base de datos vive en el almacenamiento
 * protegido. `BOOT_COMPLETED` llega con el usuario ya desbloqueado;
 * `MY_PACKAGE_REPLACED` cubre una instalación o actualización de la
 * app, y `USER_UNLOCKED` el caso de un arranque directo.
 */
class UpdateBootReceiver : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		when (intent.action) {
			Intent.ACTION_BOOT_COMPLETED,
			Intent.ACTION_MY_PACKAGE_REPLACED,
			Intent.ACTION_USER_UNLOCKED -> UpdateScheduler.sync(context)
		}
	}
}
