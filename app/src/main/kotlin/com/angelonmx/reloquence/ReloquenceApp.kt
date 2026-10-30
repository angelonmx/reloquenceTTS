package com.angelonmx.reloquence

import android.app.Application
import timber.log.Timber

/**
 * 5.4: el punto donde la app arranca.
 *
 * Antes no había una clase Application: la pantalla de ajustes
 * plantaba Timber y el servicio hacía lo suyo. La comprobación de
 * actualizaciones vive ahora en el modelo de Ajustes y se hace una
 * sola vez al abrir la pantalla, así que aquí solo queda el registro.
 */
class ReloquenceApp : Application() {

	override fun onCreate() {
		super.onCreate()
		if (Timber.forest().isEmpty()) Timber.plant(Timber.DebugTree())
	}
}
