package com.angelonmx.reloquence

import android.app.Application
import timber.log.Timber

/**
 * 5.4: el punto donde la app arranca.
 *
 * Antes no había una clase Application: la pantalla de ajustes
 * plantaba Timber y el servicio hacía lo suyo. La comprobación
 * automática de actualizaciones necesita un sitio que corra tanto
 * si se abre la pantalla como si el sistema arranca el servicio de
 * voz, así que ese sitio pasa a ser este.
 *
 * Programar aquí no descarga nada: solo deja el trabajo periódico
 * apuntado en WorkManager para cuando toque.
 */
class ReloquenceApp : Application() {

	override fun onCreate() {
		super.onCreate()
		if (Timber.forest().isEmpty()) Timber.plant(Timber.DebugTree())
		UpdateScheduler.sync(this)
	}
}
