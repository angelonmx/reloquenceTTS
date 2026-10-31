package com.angelonmx.reloquence

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 5.4: buscar, descargar y comprobar una actualización.
 *
 * La app no habla con la API de GitHub: descarga un JSON pequeño y
 * estático de `updates/<edición>.json`, compara `versionCode` y se
 * queda con el APK de su ABI. Eso funciona igual en un repositorio
 * público, en GitHub Pages o en un servidor propio, sin token y sin
 * límite de peticiones.
 *
 * Nada de lo que llega se instala a ciegas:
 *
 *  1. el manifiesto tiene que ser de esta app y de esta edición;
 *  2. el APK tiene que ser más nuevo que el instalado;
 *  3. su SHA-256 tiene que coincidir con el del manifiesto;
 *  4. su `applicationId` y su `versionCode` tienen que ser los que
 *     dice el manifiesto;
 *  5. su certificado tiene que ser el mismo que el de la app
 *     instalada.
 *
 * Si cualquiera falla, no se abre el instalador. Android, además,
 * siempre pide confirmación: una app normal no puede instalar en
 * silencio.
 */
object UpdateChecker {

	private const val MAX_MANIFEST_BYTES = 512 * 1024
	private const val MAX_APK_BYTES = 300L * 1024 * 1024
	private const val CONNECT_TIMEOUT_MS = 15_000
	private const val READ_TIMEOUT_MS = 30_000
	private const val USER_AGENT = "reloquenceTTS-updater"

	/** El resultado de una comprobación, ya sin red de por medio. */
	sealed interface Result {
		data class Available(val info: UpdateInfo) : Result
		data object UpToDate : Result
		data class Failed(val failure: UpdateFailure) : Result
	}

	fun manifestUrl(): String =
		"${BuildConfig.UPDATE_BASE_URL}/${BuildConfig.UPDATE_EDITION}.json"

	/**
	 * Comprueba si hay una versión nueva para esta instalación.
	 *
	 * No lanza: todo lo que puede salir mal se devuelve como
	 * [Result.Failed] para que la pantalla lo traduzca y el
	 * trabajador de fondo decida si reintenta.
	 */
	fun check(
		context: Context,
		currentVersionCode: Int = BuildConfig.VERSION_CODE,
		applicationId: String = BuildConfig.APPLICATION_ID,
		edition: String = BuildConfig.UPDATE_EDITION,
		sdkInt: Int = Build.VERSION.SDK_INT,
		supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList()
	): Result = try {
		val manifest = fetchManifest()
		val failure = UpdateManifestParser.validate(
			manifest,
			currentVersionCode,
			applicationId,
			edition,
			sdkInt
		)
		when (failure) {
			null -> {
				val apk = UpdateManifestParser.chooseApk(manifest.apks, supportedAbis)
				if (apk == null) {
					Result.Failed(UpdateFailure.NoApk)
				} else {
					Result.Available(
						UpdateInfo(
							versionName = manifest.versionName,
							versionCode = manifest.versionCode,
							publishedAt = manifest.publishedAt,
							notes = manifest.notes,
							apk = apk
						)
					)
				}
			}
			UpdateFailure.NotNewer -> Result.UpToDate
			else -> Result.Failed(failure)
		}
	} catch (e: UpdateException) {
		Result.Failed(e.failure)
	} catch (e: CancellationException) {
		throw e
	} catch (e: UnknownHostException) {
		Result.Failed(UpdateFailure.Network)
	} catch (e: SocketTimeoutException) {
		Result.Failed(UpdateFailure.Network)
	} catch (e: MalformedURLException) {
		Result.Failed(UpdateFailure.Server)
	} catch (e: IOException) {
		Result.Failed(UpdateFailure.Network)
	} catch (e: Exception) {
		Result.Failed(UpdateFailure.Unknown)
	}

	/** Descarga y valida el manifiesto. Lanza [UpdateException]. */
	fun fetchManifest(): UpdateManifest {
		val url = manifestUrl()
		if (!UpdateManifestParser.isHttps(url)) {
			throw UpdateException(UpdateFailure.InvalidManifest, "la base no es HTTPS")
		}
		val raw = readText(url, MAX_MANIFEST_BYTES)
		return try {
			UpdateManifestParser.parse(raw)
		} catch (e: Exception) {
			throw UpdateException(UpdateFailure.InvalidManifest, "JSON inválido", e)
		}
	}

	/**
	 * Descarga el APK a la caché y devuelve el archivo. Comprueba el
	 * SHA-256 mientras escribe, así que un archivo corrupto no llega
	 * a [verifyApk].
	 *
	 * [onProgress] se llama desde el hilo de E/S; la pantalla es la
	 * que decide cada cuánto redibujar.
	 */
	fun download(
		context: Context,
		info: UpdateInfo,
		onProgress: (read: Long, total: Long) -> Unit = { _, _ -> }
	): File {
		if (!UpdateManifestParser.isHttps(info.apk.url)) {
			throw UpdateException(UpdateFailure.InvalidManifest, "el APK no es HTTPS")
		}
		val dir = File(context.cacheDir, "updates").apply { mkdirs() }
		val name = "reloquence-${BuildConfig.UPDATE_EDITION}-${info.versionCode}.apk"
		val target = File(dir, name)
		val temp = File(dir, "$name.part")
		runCatching { temp.delete() }
		runCatching { target.delete() }

		val connection = open(info.apk.url)
		try {
			val code = connection.responseCode
			if (code !in 200..299) {
				throw UpdateException(UpdateFailure.Download, "HTTP $code")
			}
			val declared = connection.contentLength.toLong().takeIf { it > 0 } ?: info.apk.size
			if (declared > MAX_APK_BYTES) {
				throw UpdateException(UpdateFailure.Download, "APK demasiado grande")
			}
			val digest = MessageDigest.getInstance("SHA-256")
			var readTotal = 0L
			temp.outputStream().use { output ->
				connection.inputStream.use { input ->
					val buffer = ByteArray(64 * 1024)
					while (true) {
						val count = input.read(buffer)
						if (count < 0) break
						readTotal += count
						if (readTotal > MAX_APK_BYTES) {
							throw UpdateException(UpdateFailure.Download, "APK demasiado grande")
						}
						digest.update(buffer, 0, count)
						output.write(buffer, 0, count)
						onProgress(readTotal, declared)
					}
				}
			}
			val actual = digest.digest().joinToString("") { "%02x".format(it) }
			if (!actual.equals(info.apk.sha256, ignoreCase = true)) {
				throw UpdateException(UpdateFailure.Checksum, "SHA-256 no coincide")
			}
			if (info.apk.size > 0 && readTotal != info.apk.size) {
				throw UpdateException(UpdateFailure.Checksum, "tamaño no coincide")
			}
			if (!temp.renameTo(target)) {
				temp.copyTo(target, overwrite = true)
				temp.delete()
			}
			return target
		} catch (e: UpdateException) {
			temp.delete()
			throw e
		} catch (e: IOException) {
			temp.delete()
			throw UpdateException(UpdateFailure.Download, e.message, e)
		} finally {
			connection.disconnect()
		}
	}

	/**
	 * 5.4.4: las ABIs que contiene un APK, mirando `lib/<abi>/` en su
	 * ZIP. Devuelve vacío si el archivo no se puede leer; la pantalla
	 * lo enseña como "—" en vez de inventarse una arquitectura.
	 */
	fun apkAbis(file: File): Set<String> {
		if (!file.isFile) return emptySet()
		return try {
			ZipFile(file).use { zip ->
				ApkAbi.fromEntries(
					zip.entries().asSequence().map { it.name }.asIterable()
				)
			}
		} catch (e: Exception) {
			emptySet()
		}
	}

	/**
	 * 5.4.4: las ABIs de la instalación actual. Se miran el APK base
	 * y sus splits: una instalación universal trae varias carpetas
	 * `lib/`, una de arquitectura trae una sola. Así se puede decir si
	 * lo instalado es "versión de la arquitectura" u "original".
	 */
	fun installedAbis(context: Context): Set<String> {
		val info = try {
			context.packageManager.getPackageInfo(context.packageName, 0)
		} catch (e: PackageManager.NameNotFoundException) {
			return emptySet()
		}
		val application = info.applicationInfo ?: return emptySet()
		val files = ArrayList<File>(1 + (application.splitSourceDirs?.size ?: 0))
		files.add(File(application.sourceDir))
		application.splitSourceDirs?.forEach { files.add(File(it)) }
		val out = linkedSetOf<String>()
		for (file in files) out += apkAbis(file)
		return out
	}

	/**
	 * Comprueba que el archivo descargado es de verdad esta app, de
	 * esta versión y firmado con la misma clave. Se llama antes de
	 * abrir PackageInstaller; si algo no cuadra, no se instala.
	 */
	fun verifyApk(context: Context, file: File, info: UpdateInfo) {
		val manager = context.packageManager
		val flags = packageFlags()
		val archive = manager.getPackageArchiveInfo(file.absolutePath, flags)
			?: throw UpdateException(UpdateFailure.InvalidApk, "no se pudo leer el APK")
		if (archive.packageName != BuildConfig.APPLICATION_ID) {
			throw UpdateException(UpdateFailure.WrongApplication)
		}
		val archiveCode = PackageInfoCompat.getLongVersionCode(archive)
		if (archiveCode != info.versionCode.toLong()) {
			throw UpdateException(UpdateFailure.InvalidApk, "versionCode distinto")
		}
		if (archiveCode <= BuildConfig.VERSION_CODE.toLong()) {
			throw UpdateException(UpdateFailure.NotNewer)
		}

		val installed = try {
			manager.getPackageInfo(context.packageName, flags)
		} catch (e: PackageManager.NameNotFoundException) {
			throw UpdateException(UpdateFailure.InvalidApk, e.message, e)
		}
		val archiveCerts = certificates(archive)
		val installedCerts = certificates(installed)
		if (archiveCerts.isEmpty() || installedCerts.isEmpty()) {
			throw UpdateException(UpdateFailure.Signature, "sin certificado")
		}
		val same = archiveCerts.any { candidate ->
			installedCerts.any { it.contentEquals(candidate) }
		}
		if (!same) {
			throw UpdateException(UpdateFailure.Signature, "certificado distinto")
		}
	}

	private fun packageFlags(): Int =
		if (Build.VERSION.SDK_INT >= 28) {
			PackageManager.GET_SIGNING_CERTIFICATES
		} else {
			@Suppress("DEPRECATION")
			PackageManager.GET_SIGNATURES
		}

	private fun certificates(info: PackageInfo): List<ByteArray> {
		if (Build.VERSION.SDK_INT >= 28) {
			val signing = info.signingInfo ?: return emptyList()
			val signers = if (signing.hasMultipleSigners()) {
				signing.apkContentsSigners
			} else {
				signing.signingCertificateHistory
			}
			return signers?.map { it.toByteArray() } ?: emptyList()
		}
		@Suppress("DEPRECATION")
		return info.signatures?.map { it.toByteArray() } ?: emptyList()
	}

	private fun open(url: String): HttpURLConnection {
		val connection = URL(url).openConnection() as? HttpURLConnection
			?: throw UpdateException(UpdateFailure.Server, "no es HTTP")
		connection.connectTimeout = CONNECT_TIMEOUT_MS
		connection.readTimeout = READ_TIMEOUT_MS
		connection.instanceFollowRedirects = true
		connection.useCaches = false
		connection.setRequestProperty("User-Agent", USER_AGENT)
		connection.setRequestProperty("Accept-Encoding", "identity")
		return connection
	}

	private fun readText(url: String, maxBytes: Int): String {
		val connection = open(url)
		try {
			val code = connection.responseCode
			if (code !in 200..299) {
				throw UpdateException(UpdateFailure.Server, "HTTP $code")
			}
			val out = ByteArrayOutputStream()
			connection.inputStream.use { input ->
				val buffer = ByteArray(16 * 1024)
				while (true) {
					val count = input.read(buffer)
					if (count < 0) break
					if (out.size() + count > maxBytes) {
						throw UpdateException(UpdateFailure.InvalidManifest, "manifiesto demasiado grande")
					}
					out.write(buffer, 0, count)
				}
			}
			return out.toString(Charsets.UTF_8.name())
		} catch (e: UpdateException) {
			throw e
		} catch (e: IOException) {
			throw UpdateException(UpdateFailure.Network, e.message, e)
		} finally {
			connection.disconnect()
		}
	}
}
