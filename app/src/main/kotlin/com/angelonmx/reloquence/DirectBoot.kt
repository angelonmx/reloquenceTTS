package com.angelonmx.reloquence

import android.content.Context
import android.os.Build
import android.os.UserManager
import timber.log.Timber
import java.io.File

/**
 * Dónde guarda el motor lo que necesita antes de que
 * el teléfono se haya desbloqueado.
 *
 * Un lector de pantalla es lo primero que se quiere en un
 * teléfono bloqueado y lo último que puede esperar un PIN,
 * así que el servicio de voz está marcado como consciente
 * de arranque directo y arranca mientras el almacenamiento
 * propio del usuario aún está cifrado. El almacenamiento
 * ordinario de la app no está ahí todavía en ese punto:
 * leerlo falla, y los ajustes leídos a través de él volverían
 * con los valores por defecto, así que la voz sería la
 * equivocada hasta que alguien desbloquee el teléfono y
 * reinicie el motor.
 *
 * El almacenamiento protegido contra arranque es legible desde
 * el momento en que el teléfono arranca, y es donde vive todo
 * lo que el motor lee al iniciar. Es un directorio distinto
 * en lugar de un tipo de archivo distinto, así que lo que se
 * guarda allí se mueve una sola vez, la primera vez que una
 * compilación con esto corre tras un desbloqueo.
 */
object DirectBoot {

	/**
	 * 4.3: cuándo se miró por última vez si quedaba algo por traer.
	 *
	 * [storage] se llama en cada `Settings`, y hay varios `Settings`
	 * vivos (la pantalla, la vista previa, el servicio) más uno por
	 * cada importación. Eso iba en el hilo principal, en
	 * `attachBaseContext`, en cada vez que se abría la aplicación, y
	 * [bringDictionariesForward] lista un directorio y puede copiar
	 * archivos enteros.
	 *
	 * No hace falta en cada llamada. Es una mudanza que ocurre una vez
	 * por instalación, así que con mirar cada pocos segundos basta, y
	 * si la primera vez no se pudo hacer (el teléfono estaba
	 * bloqueado) el siguiente intento llega igual de pronto.
	 */
	private var lastMoveCheck = 0L

	private const val MOVE_CHECK_MS = 5_000L

	/** El contexto para leer los ajustes y los archivos
	 *  que nombra a través de él. */
	fun storage(context: Context): Context {
		val app = context.applicationContext
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return app
		val device = app.createDeviceProtectedStorageContext() ?: return app
		// El traslado de los ajustes es una operación sin efecto
		// una vez que no queda nada por traer.
		device.moveSharedPreferencesFrom(app, prefsName(app))
		val now = android.os.SystemClock.elapsedRealtime()
		if (lastMoveCheck == 0L || now - lastMoveCheck >= MOVE_CHECK_MS) {
			lastMoveCheck = now
			bringDictionariesForward(app, device)
		}
		return device
	}

	/** El directorio de diccionarios, donde sea que se esté
	 *  guardando. */
	fun dictionaries(context: Context): File =
		File(storage(context).filesDir, DICTIONARIES)

	private fun prefsName(app: Context) = "${app.packageName}_preferences"

	/**
	 * Mueve el diccionario elegido por una compilación
	 * anterior al almacenamiento protegido contra arranque
	 * y señala los ajustes a dónde fue.
	 *
	 * Nada puede moverse mientras el teléfono está bloqueado
	 * -- el directorio de donde se mueve es el que aún no es
	 * legible -- así que un arranque bloqueado deja esto para
	 * el siguiente desbloqueado. Hasta entonces el servicio
	 * encuentra el archivo ausente y habla sin él, que es
	 * lo que ya hace para un diccionario que se ha eliminado.
	 */
	private fun bringDictionariesForward(app: Context, device: Context) {
		if (!isUnlocked(app)) return
		val from = File(app.filesDir, DICTIONARIES)
		if (!from.isDirectory) return
		val into = File(device.filesDir, DICTIONARIES)
		val moved = mutableMapOf<String, String>()
		for (file in from.listFiles().orEmpty()) {
			if (!file.isFile) continue
			if (!into.isDirectory && !into.mkdirs()) return
			val there = File(into, file.name)
			try {
				// Los dos están en diferentes particiones,
				// así que un rename puede negarse y la copia
				// no es una alternativa para un caso raro sino
				// la forma habitual.
				if (!file.renameTo(there)) {
					file.copyTo(there, overwrite = true)
					file.delete()
				}
			} catch (stuck: Exception) {
				// Se deja donde está, y se deja en los ajustes
				// como donde está, así que el siguiente arranque
				// desbloqueado lo intenta de nuevo.
				Timber.e(stuck, "no se puede mover ${file.name} al almacenamiento protegido contra arranque")
				continue
			}
			moved[file.absolutePath] = there.absolutePath
		}
		from.delete()
		if (moved.isEmpty()) return
		val prefs = device.getSharedPreferences(prefsName(app), Context.MODE_PRIVATE)
		val was = prefs.getString(Settings.KEY_DICTIONARY_PATH, null) ?: return
		moved[was]?.let { prefs.edit().putString(Settings.KEY_DICTIONARY_PATH, it).apply() }
	}

	private fun isUnlocked(app: Context): Boolean {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true
		val users = app.getSystemService(UserManager::class.java) ?: return true
		return users.isUserUnlocked
	}

	private const val DICTIONARIES = "dictionaries"
}
