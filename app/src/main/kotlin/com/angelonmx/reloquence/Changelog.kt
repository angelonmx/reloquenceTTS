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
