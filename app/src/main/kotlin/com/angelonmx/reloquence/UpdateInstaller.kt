package com.angelonmx.reloquence

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File

/**
 * 5.4: abrir el instalador de Android con un APK ya comprobado.
 *
 * Se usa `PackageInstaller` y no un `Intent.ACTION_VIEW` al archivo:
 * así no hace falta FileProvider, no se le enseña al usuario un
 * selector de aplicaciones y el sistema sabe que la instalación
 * viene de esta app.
 *
 * Android no deja que una app normal instale en silencio. En
 * Android 8+ hay que tener el permiso de "instalar apps
 * desconocidas" para esta app, y después el sistema muestra su
 * propia pantalla de confirmación. Eso no se puede saltar y no se
 * intenta.
 */
object UpdateInstaller {

	/** Si esta app puede pedirle al sistema que instale un APK. */
	fun canInstall(context: Context): Boolean =
		Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

	/**
	 * Abre el apartado del sistema donde se autoriza a esta app a
	 * instalar APK. El usuario tiene que encenderlo él; la app no
	 * puede hacerlo sola.
	 */
	fun requestInstallPermission(context: Context) {
		if (Build.VERSION.SDK_INT < 26) return
		runCatching {
			context.startActivity(
				Intent(
					Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
					Uri.parse("package:${context.packageName}")
				).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			)
		}
	}

	/**
	 * Copia [file] a una sesión de PackageInstaller y la confirma.
	 * El resultado llega a [UpdateInstallReceiver], que es quien
	 * lanza la pantalla de confirmación del sistema o avisa del
	 * fallo.
	 *
	 * Lanza [UpdateException] con [UpdateFailure.Permission] si
	 * falta el permiso y con [UpdateFailure.Install] si el sistema
	 * rechaza la sesión.
	 */
	fun install(context: Context, file: File) {
		if (!canInstall(context)) {
			throw UpdateException(UpdateFailure.Permission)
		}
		if (!file.isFile || file.length() <= 0L) {
			throw UpdateException(UpdateFailure.InvalidApk, "APK vacío")
		}

		val installer = context.packageManager.packageInstaller
		val params = PackageInstaller.SessionParams(
			PackageInstaller.SessionParams.MODE_FULL_INSTALL
		).apply {
			setAppPackageName(context.packageName)
			setSize(file.length())
		}
		val sessionId = try {
			installer.createSession(params)
		} catch (e: Exception) {
			throw UpdateException(UpdateFailure.Install, e.message, e)
		}

		try {
			installer.openSession(sessionId).use { session ->
				file.inputStream().use { input ->
					session.openWrite("base.apk", 0L, file.length()).use { output ->
						input.copyTo(output)
						session.fsync(output)
					}
				}
				val result = Intent(context, UpdateInstallReceiver::class.java)
					.setAction(UpdateInstallReceiver.ACTION_INSTALL_RESULT)
				val flags = PendingIntent.FLAG_UPDATE_CURRENT or
					if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
				val pending = PendingIntent.getBroadcast(
					context,
					sessionId,
					result,
					flags
				)
				session.commit(pending.intentSender)
			}
		} catch (e: SecurityException) {
			runCatching { installer.abandonSession(sessionId) }
			throw UpdateException(UpdateFailure.Permission, e.message, e)
		} catch (e: Exception) {
			runCatching { installer.abandonSession(sessionId) }
			throw if (e is UpdateException) e
			else UpdateException(UpdateFailure.Install, e.message, e)
		}
	}
}
