package com.angelonmx.reloquence

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 5.4: el estado del apartado de actualizaciones.
 *
 * Vive en un ViewModel para que girar el teléfono no cancele una
 * descarga ni vuelva a preguntar al servidor. La red y el
 * instalador corren en [Dispatchers.IO]; lo que la pantalla lee
 * cambia en el hilo principal.
 */
class UpdatesModel(private val app: Application) : ViewModel() {

	private val prefs = UpdatePrefs(app)

	var status by mutableStateOf<UpdateStatus>(UpdateStatus.Idle)
		private set

	var available by mutableStateOf<UpdateInfo?>(null)
		private set

	var showDialog by mutableStateOf(false)
		private set

	var showChangelog by mutableStateOf(false)
		private set

	var autoCheck by mutableStateOf(prefs.autoCheck)
		private set

	var intervalHours by mutableStateOf(prefs.intervalHours)
		private set

	var lastCheckAt by mutableStateOf(prefs.lastCheckAt)
		private set

	/** La entrada del changelog de la versión instalada. Se lee una
	 *  vez: el archivo viaja en los assets y no cambia en caliente. */
	val changelog: ChangelogEntry? = ChangelogStore.current(app)

	/**
	 * Buscar a mano. Siempre enseña el diálogo si hay algo nuevo,
	 * aunque antes se hubiera elegido "Ahora no": quien pide buscar
	 * quiere ver lo que hay.
	 */
	fun search() {
		if (busy) return
		viewModelScope.launch {
			status = UpdateStatus.Checking
			available = null
			showDialog = false
			val result = withContext(Dispatchers.IO) { UpdateChecker.check(app) }
			prefs.lastCheckAt = System.currentTimeMillis()
			lastCheckAt = prefs.lastCheckAt
			when (result) {
				is UpdateChecker.Result.Available -> {
					available = result.info
					status = UpdateStatus.Available(result.info.versionName)
					showDialog = true
				}

				UpdateChecker.Result.UpToDate -> {
					prefs.clearSkip()
					prefs.lastNotifiedVersionCode = 0
					status = UpdateStatus.UpToDate
				}

				is UpdateChecker.Result.Failed -> {
					status = UpdateStatus.Failed(result.failure)
				}
			}
		}
	}

	/** "Ahora no": cerrar el diálogo y no volver a avisar de esta
	 *  misma versión desde la comprobación automática. */
	fun dismiss() {
		available?.let { prefs.skippedVersionCode = it.versionCode }
		showDialog = false
	}

	/**
	 * Descargar, comprobar y abrir el instalador. Si falta el
	 * permiso de instalar apps desconocidas, se abre su pantalla y
	 * el diálogo se queda abierto para poder reintentar al volver.
	 */
	fun install() {
		val info = available ?: return
		if (busy) return
		if (!UpdateInstaller.canInstall(app)) {
			status = UpdateStatus.Permission
			UpdateInstaller.requestInstallPermission(app)
			return
		}
		showDialog = false
		viewModelScope.launch {
			var file: File? = null
			try {
				status = UpdateStatus.Downloading(null)
				var lastPercent = -1
				val downloaded = withContext(Dispatchers.IO) {
					UpdateChecker.download(app, info) { read, total ->
						if (total > 0) {
							val percent = ((read * 100) / total).toInt().coerceIn(0, 100)
							if (percent != lastPercent) {
								lastPercent = percent
								viewModelScope.launch {
									if (status is UpdateStatus.Downloading) {
										status = UpdateStatus.Downloading(percent)
									}
								}
							}
						}
					}
				}
				file = downloaded
				status = UpdateStatus.Verifying
				withContext(Dispatchers.IO) {
					UpdateChecker.verifyApk(app, downloaded, info)
				}
				status = UpdateStatus.Installing
				withContext(Dispatchers.IO) {
					UpdateInstaller.install(app, downloaded)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: UpdateException) {
				status = UpdateStatus.Failed(e.failure)
			} catch (e: Exception) {
				status = UpdateStatus.Failed(UpdateFailure.Unknown)
			} finally {
				file?.delete()
			}
		}
	}

	fun changeAutoCheck(on: Boolean) {
		autoCheck = on
		prefs.autoCheck = on
		UpdateScheduler.sync(app, update = true)
	}

	fun setInterval(hours: Int) {
		if (hours !in UpdatePrefs.INTERVALS) return
		intervalHours = hours
		prefs.intervalHours = hours
		UpdateScheduler.sync(app, update = true)
	}

	/**
	 * Al volver a Ajustes, una instalación que quedó pendiente ya no
	 * está en curso: o el sistema reemplazó la app, o el usuario
	 * canceló la confirmación. Sin esto la pantalla se queda en
	 * "Instalando..." y no deja reintentar.
	 */
	fun onScreenResumed() {
		if (status is UpdateStatus.Installing) status = UpdateStatus.Idle
	}

	fun openChangelog() {
		showChangelog = true
	}

	fun closeChangelog() {
		showChangelog = false
	}

	/** Si hay una comprobación, descarga o instalación en curso. */
	val busy: Boolean
		get() = when (status) {
			UpdateStatus.Checking,
			UpdateStatus.Verifying,
			UpdateStatus.Installing,
			is UpdateStatus.Downloading -> true
			else -> false
		}
}

/** Lo que la pantalla cuenta debajo de los botones. */
sealed interface UpdateStatus {
	data object Idle : UpdateStatus
	data object Checking : UpdateStatus
	data object UpToDate : UpdateStatus
	data class Available(val versionName: String) : UpdateStatus
	data class Failed(val failure: UpdateFailure) : UpdateStatus
	data class Downloading(val percent: Int?) : UpdateStatus
	data object Verifying : UpdateStatus
	data object Installing : UpdateStatus
	data object Permission : UpdateStatus
}
