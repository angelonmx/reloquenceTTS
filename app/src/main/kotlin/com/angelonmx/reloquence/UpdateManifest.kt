package com.angelonmx.reloquence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 5.4: lo que publica `updates/<edición>.json`.
 *
 * Es un archivo pequeño y estático: la app lo descarga, compara
 * `versionCode` y, si hay algo nuevo, se queda con el APK de su ABI.
 * No se consulta la API de GitHub ni se necesita un token, así que
 * funciona igual en un repositorio público, en GitHub Pages o en un
 * servidor propio.
 *
 * [notes] va por idioma ("es", "en"...) y es una lista de líneas para
 * enseñar en el diálogo de actualización. Si el idioma no está, se
 * usa inglés y después español.
 */
@Serializable
data class UpdateManifest(
	val schema: Int = 1,
	val applicationId: String = "",
	val edition: String = "",
	val versionName: String = "",
	val versionCode: Int = 0,
	val publishedAt: String = "",
	val minSdk: Int = 23,
	val notes: Map<String, List<String>> = emptyMap(),
	val apks: List<UpdateApk> = emptyList()
)

/** Un APK concreto del manifiesto, ya con su huella. */
@Serializable
data class UpdateApk(
	val abi: String,
	val url: String,
	val sha256: String,
	val size: Long = 0
)

/** La actualización elegida para este dispositivo. */
data class UpdateInfo(
	val versionName: String,
	val versionCode: Int,
	val publishedAt: String,
	val notes: Map<String, List<String>>,
	val apk: UpdateApk
)

/**
 * Lo que puede salir mal. La pantalla lo traduce a una frase; el
 * trabajador de fondo solo distingue los casos en los que vale la
 * pena reintentar.
 */
enum class UpdateFailure {
	Network,
	Server,
	InvalidManifest,
	WrongApplication,
	WrongEdition,
	NotNewer,
	Unsupported,
	NoApk,
	InvalidApk,
	Download,
	Checksum,
	Signature,
	Permission,
	Install,
	Unknown
}

class UpdateException(
	val failure: UpdateFailure,
	message: String? = null,
	cause: Throwable? = null
) : Exception(message, cause)

/**
 * Las reglas del manifiesto, separadas de la red y de Android para
 * poder probarlas en la máquina. Todo lo que decide si una
 * actualización es aceptable pasa por aquí.
 */
object UpdateManifestParser {

	private val json = Json {
		ignoreUnknownKeys = true
		explicitNulls = false
	}

	fun parse(raw: String): UpdateManifest =
		json.decodeFromString(UpdateManifest.serializer(), raw)

	/**
	 * Devuelve el primer problema, o `null` si el manifiesto sirve
	 * para esta instalación. [NotNewer] no es un error de formato:
	 * es la respuesta normal de "ya estás al día".
	 */
	fun validate(
		manifest: UpdateManifest,
		currentVersionCode: Int,
		applicationId: String,
		edition: String,
		sdkInt: Int
	): UpdateFailure? {
		if (manifest.schema != 1) return UpdateFailure.InvalidManifest
		if (manifest.applicationId != applicationId) return UpdateFailure.WrongApplication
		if (manifest.edition != edition) return UpdateFailure.WrongEdition
		if (manifest.versionName.isBlank()) return UpdateFailure.InvalidManifest
		if (manifest.versionCode <= 0 || manifest.versionCode > 2_100_000_000) {
			return UpdateFailure.InvalidManifest
		}
		if (manifest.versionCode <= currentVersionCode) return UpdateFailure.NotNewer
		if (manifest.minSdk > sdkInt) return UpdateFailure.Unsupported
		if (manifest.apks.isEmpty()) return UpdateFailure.NoApk
		for (apk in manifest.apks) {
			if (apk.abi.isBlank()) return UpdateFailure.InvalidManifest
			if (!isHttps(apk.url)) return UpdateFailure.InvalidManifest
			if (!isSha256(apk.sha256)) return UpdateFailure.InvalidManifest
			if (apk.size < 0) return UpdateFailure.InvalidManifest
		}
		return null
	}

	/**
	 * El APK que le toca a este teléfono, en el orden en que Android
	 * declara sus ABIs. Si no hay uno exacto, se usa el universal;
	 * si tampoco, no hay actualización posible sin engañar a nadie.
	 */
	fun chooseApk(apks: List<UpdateApk>, supportedAbis: List<String>): UpdateApk? {
		val canonical = apks.map { it to canonicalAbi(it.abi) }
		for (supported in supportedAbis) {
			val want = canonicalAbi(supported)
			canonical.firstOrNull { it.second == want }?.let { return it.first }
		}
		return canonical.firstOrNull { it.second == "universal" }?.first
	}

	/** Una huella SHA-256 se escribe con 64 dígitos hexadecimales. */
	fun isSha256(value: String): Boolean =
		value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

	/** HTTPS y con host: no se acepta una URL relativa ni `file:`. */
	fun isHttps(value: String): Boolean {
		val url = value.trim()
		if (!url.startsWith("https://", ignoreCase = true)) return false
		return try {
			val uri = java.net.URI(url)
			uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
		} catch (e: Exception) {
			false
		}
	}

	/** El idioma que se enseña, con inglés y español como respaldo. */
	fun localizedNotes(notes: Map<String, List<String>>, language: String): List<String> {
		val key = language.lowercase().substringBefore('-').substringBefore('_')
		return notes[key]
			?: notes["en"]
			?: notes["es"]
			?: notes.values.firstOrNull()
			?: emptyList()
	}

	private fun canonicalAbi(abi: String): String = when (abi.trim().lowercase()) {
		"arm64", "arm64-v8a", "aarch64" -> "arm64-v8a"
		"arm", "armv7", "armv7a", "armeabi", "armeabi-v7a" -> "armeabi-v7a"
		"x86_64", "x86-64" -> "x86_64"
		"x86", "i686" -> "x86"
		"universal", "all" -> "universal"
		else -> abi.trim().lowercase()
	}
}
