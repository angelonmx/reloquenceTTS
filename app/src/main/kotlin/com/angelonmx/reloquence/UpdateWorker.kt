package com.angelonmx.reloquence

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import timber.log.Timber

/**
 * 5.4: la comprobación periódica.
 *
 * WorkManager la despierta con la frecuencia que el usuario eligió,
 * solo cuando hay red, y sobrevive a reinicios y a que el sistema
 * cierre la app. Si la comprobación falla por red, se reintenta con
 * espera creciente; si falla por otra cosa, no se insiste hasta la
 * siguiente vuelta.
 *
 * El trabajador no descarga ni instala nada: solo mira el manifiesto
 * y, si hay algo nuevo, deja una notificación. La descarga y la
 * instalación siempre las pide una persona desde Ajustes.
 */
class UpdateWorker(
	appContext: Context,
	params: WorkerParameters
) : CoroutineWorker(appContext, params) {

	override suspend fun doWork(): Result {
		val prefs = UpdatePrefs(applicationContext)
		if (!prefs.autoCheck) return Result.success()

		val result = UpdateChecker.check(applicationContext)
		prefs.lastCheckAt = System.currentTimeMillis()
		return when (result) {
			is UpdateChecker.Result.Available -> {
				val info = result.info
				if (prefs.skippedVersionCode != info.versionCode &&
					prefs.lastNotifiedVersionCode != info.versionCode &&
					AppPermissions.notificationsOn(applicationContext)
				) {
					UpdateNotifications.updateAvailable(applicationContext, info)
					prefs.lastNotifiedVersionCode = info.versionCode
				}
				Result.success()
			}

			UpdateChecker.Result.UpToDate -> {
				prefs.lastNotifiedVersionCode = 0
				prefs.skippedVersionCode = 0
				Result.success()
			}

			is UpdateChecker.Result.Failed -> {
				Timber.w("comprobación de actualización fallida: %s", result.failure)
				when (result.failure) {
					UpdateFailure.Network, UpdateFailure.Server -> Result.retry()
					else -> Result.success()
				}
			}
		}
	}
}
