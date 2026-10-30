package com.angelonmx.reloquence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import timber.log.Timber

/**
 * 5.4: el final de una instalación.
 *
 * `PackageInstaller` no devuelve nada de forma síncrona: se le da un
 * `PendingIntent` y contesta aquí. Hay tres finales posibles:
 *
 *  - el sistema quiere enseñar su pantalla de confirmación
 *    (`STATUS_PENDING_USER_ACTION`), y hay que lanzar el Intent que
 *    viene dentro;
 *  - se instaló (`STATUS_SUCCESS`), y no hay nada que hacer porque
 *    el proceso de la app está a punto de ser reemplazado;
 *  - falló, y solo se anota en el registro: la actualización la pidió
 *    el usuario y la pantalla ya le enseña el error, así que no se
 *    añade una notificación que no pidió.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action != ACTION_INSTALL_RESULT) return
		when (val status = intent.getIntExtra(
			PackageInstaller.EXTRA_STATUS,
			PackageInstaller.STATUS_FAILURE
		)) {
			PackageInstaller.STATUS_PENDING_USER_ACTION -> {
				val confirm = IntentCompat.getParcelableExtra(
					intent,
					Intent.EXTRA_INTENT,
					Intent::class.java
				)
				if (confirm == null) {
					Timber.w("instalación sin Intent de confirmación")
					return
				}
				confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				runCatching { context.startActivity(confirm) }
					.onFailure { Timber.w(it, "no se pudo abrir la confirmación de instalación") }
			}

			PackageInstaller.STATUS_SUCCESS -> {
				Timber.i("actualización instalada")
			}

			else -> {
				val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
				Timber.w("instalación fallida (%d): %s", status, message)
			}
		}
	}

	companion object {
		const val ACTION_INSTALL_RESULT =
			"com.angelonmx.reloquence.action.INSTALL_RESULT"
	}
}
