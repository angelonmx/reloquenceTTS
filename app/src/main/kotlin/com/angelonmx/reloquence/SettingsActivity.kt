package com.angelonmx.reloquence

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import timber.log.Timber

/** El idioma de la interfaz: vacío sigue al sistema, o una
 *  etiqueta BCP-47 ("es", "en", "fr"...). */
object AppLocale {

	val SUPPORTED = listOf("", "es", "en", "fr", "de", "it", "pt", "ja", "pl", "tr", "ru")

	fun wrap(context: android.content.Context, tag: String): android.content.Context {
		if (tag.isEmpty()) return context
		val locale = java.util.Locale.forLanguageTag(tag)
		java.util.Locale.setDefault(locale)
		val config = android.content.res.Configuration(context.resources.configuration)
		config.setLocale(locale)
		return context.createConfigurationContext(config)
	}

	fun displayName(tag: String): String {
		if (tag.isEmpty()) return ""
		return java.util.Locale.forLanguageTag(tag)
			.getDisplayName(java.util.Locale.forLanguageTag(tag))
			.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
	}
}

/**
 * 4.4: el nombre de un idioma del motor, en el idioma de la
 * interfaz.
 *
 * Los nombres de la variante de español están en los recursos
 * porque el motor no nombra un país: `eses` es el español de
 * España y `esus` es el español latinoamericano de IBM, no el de
 * México. Con `Locale.getDisplayName` salía "español (México)",
 * que es lo que estaba mal escrito, y además cambiaba con el
 * idioma del aparato: en inglés "Spanish (Mexico)".
 *
 * Para los demás idiomas el recurso no existe y se usa el nombre
 * del sistema, traduciéndolo con [AppLocale].
 */
object EngineLocale {

	fun displayName(context: android.content.Context, language: Int): String {
		val res = Eci.nameRes(language)
		return if (res != 0) context.getString(res) else Eci.displayName(language)
	}
}

/** Los ajustes propios del motor, desde la pantalla de
 *  texto a voz del sistema y desde el lanzador. */
class SettingsActivity : ComponentActivity() {

	// 4.1: ViewModel real (librería lifecycle): sobrevive a la
	// rotación sin reabrir el motor ni releer el diccionario.
	private val model: SettingsModel by viewModels {
		object : ViewModelProvider.Factory {
			@Suppress("UNCHECKED_CAST")
			override fun <T : ViewModel> create(modelClass: Class<T>): T =
				SettingsModel(application) as T
		}
	}

	// 5.4: el estado de actualizaciones sobrevive a la rotación y a
	// una descarga en curso.
	private val updates: UpdatesModel by viewModels {
		object : ViewModelProvider.Factory {
			@Suppress("UNCHECKED_CAST")
			override fun <T : ViewModel> create(modelClass: Class<T>): T =
				UpdatesModel(application) as T
		}
	}
	private var askingPerms = false

	companion object {
		@Volatile
		private var permsShownFor: Set<String>? = null
	}

	private val notifAsk =
		registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

	override fun attachBaseContext(newBase: android.content.Context) {
		val tag = Settings(newBase).appLocale.takeIf { it in AppLocale.SUPPORTED } ?: ""
		super.attachBaseContext(AppLocale.wrap(newBase, tag))
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		if (Timber.forest().isEmpty()) Timber.plant(Timber.DebugTree())
		setContent {
			MaterialTheme {
				Surface {
					SettingsScreen(model, updates)
				}
			}
		}
	}

	override fun onResume() {
		super.onResume()
		// 5.4: si el instalador del sistema se cerró sin instalar,
		// la pantalla se quedaba en "Instalando..." y no dejaba
		// reintentar. Al volver ya no hay instalación en curso.
		updates.onScreenResumed()
		updates.syncFromDisk()
		// 4.3: algo pudo cambiar mientras esta actividad no estaba
		// delante: un diccionario o una copia importados con el
		// "Abrir con" desde el gestor de archivos o el correo, que
		// importan en otra actividad. La pantalla se enteraba al
		// reiniciar el servicio, y no antes; con esto se entera al
		// volver. Es una comparación de un contador: si no cambió
		// nada, no se relee nada.
		model.syncFromDisk()
		// 4.0: se vuelve a preguntar si la lista cambió (autorizó
		// uno y falta otro), pero no se insiste con la misma lista
		// que ya se dejó para después.
		val missing = AppPermissions.missing(this)
		if (missing.isEmpty() || askingPerms) return
		if (missing.toSet() == permsShownFor) return
		permsShownFor = missing.toSet()
		askPermissions(missing)
	}

	/** Pide [missing] (batería/notificaciones). El overlay ya
	 *  no se exige y vive en su apartado. */
	private fun askPermissions(missing: List<String>) {
		if (askingPerms || missing.isEmpty()) return
		askingPerms = true
		val labels = missing.map { AppPermissions.label(this, it) }.toTypedArray()
		android.app.AlertDialog.Builder(this)
			.setTitle(getString(R.string.perm_dialog_title))
			.setMessage(getString(R.string.perm_dialog_text))
			.setItems(labels) { _, which ->
				AppPermissions.open(this, notifAsk, missing[which])
			}
			.setPositiveButton(getString(R.string.perm_authorize)) { _, _ ->
				missing.firstOrNull()?.let { AppPermissions.open(this, notifAsk, it) }
			}
			.setNegativeButton(getString(R.string.perm_later), null)
			.setOnDismissListener { askingPerms = false }
			.show()
	}

	override fun onStop() {
		super.onStop()
		// Salir de la pantalla no debe dejar una muestra hablando
		// sobre lo que venga después.
		model.stopSpeaking()
	}
}
