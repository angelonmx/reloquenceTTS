package com.angelonmx.reloquence

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 5.4: los cambios de cada versión, empaquetados en la app.
 *
 * "Ver cambios de la versión actual" no puede depender de la red:
 * tiene que funcionar recién instalada y sin conexión. Por eso el
 * changelog viaja en `assets/changelog.json` y no se descarga del
 * mismo sitio que las actualizaciones.
 *
 * Cada edición tiene su lista. La app lee la de
 * [BuildConfig.UPDATE_EDITION], busca su `versionCode` y enseña las
 * notas en el idioma de la interfaz, con inglés y español de
 * respaldo.
 */
@Serializable
data class Changelog(
	val schema: Int = 1,
	val editions: Map<String, List<ChangelogEntry>> = emptyMap()
)

@Serializable
data class ChangelogEntry(
	val versionName: String = "",
	val versionCode: Int = 0,
	val date: String = "",
	val notes: Map<String, List<String>> = emptyMap()
)

object ChangelogStore {

	private val json = Json {
		ignoreUnknownKeys = true
		explicitNulls = false
	}

	/** La entrada de la versión instalada, o la más cercana si el
	 *  archivo no trae ese código exacto. */
	fun current(context: Context): ChangelogEntry? {
		val all = all(context)
		return all.firstOrNull { it.versionCode == BuildConfig.VERSION_CODE }
			?: all.firstOrNull { it.versionName == BuildConfig.VERSION_NAME }
			?: all.firstOrNull()
	}

	fun all(context: Context): List<ChangelogEntry> = runCatching {
		val raw = context.assets.open(ASSET).bufferedReader().use { it.readText() }
		val changelog = json.decodeFromString(Changelog.serializer(), raw)
		changelog.editions[BuildConfig.UPDATE_EDITION].orEmpty()
	}.getOrDefault(emptyList())

	/** Las notas de una entrada en el idioma pedido. */
	fun notes(entry: ChangelogEntry?, language: String): List<String> =
		entry?.let { UpdateManifestParser.localizedNotes(it.notes, language) }.orEmpty()

	private const val ASSET = "changelog.json"
}

/**
 * 5.4.1: el registro que se enseña una sola vez después de instalar
 * una versión nueva.
 *
 * La primera vez que la app abre en un aparato no hay nada que
 * contar: se apunta la versión instalada y no se muestra nada. Si la
 * versión instalada es mayor que la apuntada, es una actualización y
 * se devuelve su entrada del changelog. La marca se escribe cuando
 * el usuario cierra el diálogo, no al enseñarlo, para que un cierre
 * forzado no se lleve el registro sin leer.
 */
object PostUpdateChangelog {

	fun pending(context: android.content.Context): ChangelogEntry? {
		val prefs = UpdatePrefs(context)
		val seen = prefs.lastSeenVersionCode
		val current = BuildConfig.VERSION_CODE
		if (seen == 0) {
			// 5.4.1: una instalación que viene de 5.4 no tiene marca
			// todavía. El sistema sabe si el paquete se actualizó
			// alguna vez: en una instalación nueva las dos fechas
			// coinciden; en una actualización, la última es bastante
			// posterior. Así los que ya tenían la app también ven el
			// registro de esta versión.
			if (wasUpdated(context)) return ChangelogStore.current(context)
			// Instalación nueva de verdad: no hay nada que contar.
			prefs.lastSeenVersionCode = current
			return null
		}
		if (current <= seen) {
			// Misma versión, o una instalación anterior (downgrade):
			// no hay nada nuevo que enseñar y se vuelve a apuntar la
			// versión que de verdad está puesta.
			if (current < seen) prefs.lastSeenVersionCode = current
			return null
		}
		return ChangelogStore.current(context)
	}

	fun acknowledge(context: android.content.Context) {
		UpdatePrefs(context).lastSeenVersionCode = BuildConfig.VERSION_CODE
	}

	/**
	 * Si el paquete pasó por una actualización. En una instalación
	 * nueva `firstInstallTime` y `lastUpdateTime` son el mismo
	 * instante (con milisegundos de diferencia); una actualización
	 * deja la segunda mucho después. El margen evita confundir una
	 * instalación normal con una actualización por relojes raros.
	 */
	private fun wasUpdated(context: android.content.Context): Boolean = runCatching {
		val info = context.packageManager.getPackageInfo(context.packageName, 0)
		info.lastUpdateTime - info.firstInstallTime > UPDATE_SLACK_MS
	}.getOrDefault(false)

	private const val UPDATE_SLACK_MS = 5 * 60 * 1000L
}
