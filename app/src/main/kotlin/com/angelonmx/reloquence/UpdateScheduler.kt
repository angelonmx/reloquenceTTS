package com.angelonmx.reloquence

import android.content.Context
import android.os.Build
import android.os.UserManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 5.4: programar la comprobación automática.
 *
 * Se usa un trabajo periódico único con nombre fijo. Cambiar la
 * frecuencia o apagar el interruptor no crea trabajos nuevos: el
 * mismo se actualiza o se cancela. Así no se acumulan copias cuando
 * alguien toca el interruptor varias veces.
 *
 * En el arranque directo (antes de desbloquear) no se toca
 * WorkManager: su base de datos vive en el almacenamiento protegido
 * por el desbloqueo. El servicio de voz sí puede arrancar ahí, así
 * que la comprobación se programa cuando el usuario ya desbloqueó.
 */
object UpdateScheduler {

	const val WORK_NAME = "reloquence-update-check"
	private const val TAG = "reloquence-updates"

	/** Deja WorkManager de acuerdo con lo que dicen las
	 *  preferencias. Se llama al arrancar la app y cada vez que se
	 *  cambia el interruptor o la frecuencia.
	 *
	 *  [update] distingue los dos casos: al arrancar se respeta el
	 *  trabajo que ya existe (`KEEP`) para no reiniciar el plazo en
	 *  cada apertura; al cambiar un ajuste se actualiza (`UPDATE`)
	 *  para que la frecuencia nueva entre en vigor. */
	fun sync(context: Context, update: Boolean = false) {
		val app = context.applicationContext
		if (!storageAvailable(app)) return
		val prefs = UpdatePrefs(app)
		val manager = WorkManager.getInstance(app)
		if (!prefs.autoCheck) {
			manager.cancelUniqueWork(WORK_NAME)
			return
		}
		val request = PeriodicWorkRequestBuilder<UpdateWorker>(
			prefs.intervalHours.toLong(),
			TimeUnit.HOURS
		)
			.setConstraints(
				Constraints.Builder()
					.setRequiredNetworkType(NetworkType.CONNECTED)
					.build()
			)
			.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
			.setInitialDelay(15, TimeUnit.MINUTES)
			.addTag(TAG)
			.build()
		manager.enqueueUniquePeriodicWork(
			WORK_NAME,
			if (update) ExistingPeriodicWorkPolicy.UPDATE
			else ExistingPeriodicWorkPolicy.KEEP,
			request
		)
	}

	private fun storageAvailable(context: Context): Boolean {
		if (Build.VERSION.SDK_INT < 24) return true
		val users = context.getSystemService(Context.USER_SERVICE) as? UserManager
			?: return true
		return users.isUserUnlocked
	}
}
