package com.angelonmx.reloquence

import android.content.Context

/**
 * 5.4.3: lo que el apartado de actualizaciones recuerda entre
 * aperturas.
 *
 * Son preferencias propias, no las del motor: nada de esto cambia
 * cómo suena una voz, así que no tiene por qué despertar al
 * servicio ni entrar en una copia de seguridad del diccionario.
 *
 * [checkOnLaunch] viene activado de fábrica. Es una comprobación de
 * un archivo pequeño y estático, sin token y sin datos de nadie; se
 * hace una sola vez al abrir la app y quien no la quiera la apaga
 * con el interruptor. No hay comprobaciones periódicas ni en
 * segundo plano.
 */
class UpdatePrefs(context: Context) {

	private val prefs = context.applicationContext
		.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

	init {
		dropObsoleteKeys()
	}

	/** Si se busca una actualización al iniciar la aplicación. */
	var checkOnLaunch: Boolean
		get() = prefs.getBoolean(KEY_AUTO, true)
		set(value) {
			prefs.edit().putBoolean(KEY_AUTO, value).commit()
		}

	/** Milisegundos desde la época de la última comprobación que
	 *  terminó, con éxito o sin él. Cero es "nunca". */
	var lastCheckAt: Long
		get() = prefs.getLong(KEY_LAST_CHECK, 0L)
		set(value) {
			prefs.edit().putLong(KEY_LAST_CHECK, value).commit()
		}

	/** La versión que el usuario dejó para después con "Ahora no".
	 *  La comprobación al iniciar la respeta; la búsqueda manual,
	 *  no: si alguien pide buscar, quiere ver lo que hay. */
	var skippedVersionCode: Int
		get() = prefs.getInt(KEY_SKIPPED, 0)
		set(value) {
			prefs.edit().putInt(KEY_SKIPPED, value).commit()
		}

	/**
	 * 5.4.2: el APK ya descargado y verificado que esperaba el
	 * permiso de instalar apps desconocidas. Antes solo vivía en
	 * memoria: si Android mataba el proceso mientras el usuario
	 * estaba en la pantalla de permisos, al volver había que
	 * descargarlo otra vez. La ruta se guarda aquí y se valida al
	 * leerla (dentro de la caché de updates, con el nombre y la
	 * versión esperados).
	 *
	 * Estas tres claves son transitorias y propias de este aparato:
	 * no viajan en una copia de seguridad porque una ruta de caché
	 * no significa nada en otro teléfono.
	 */
	var pendingApkPath: String?
		get() = prefs.getString(KEY_PENDING_PATH, null)
		set(value) {
			prefs.edit().putString(KEY_PENDING_PATH, value).commit()
		}

	var pendingVersionName: String
		get() = prefs.getString(KEY_PENDING_NAME, "").orEmpty()
		set(value) {
			prefs.edit().putString(KEY_PENDING_NAME, value).commit()
		}

	var pendingVersionCode: Int
		get() = prefs.getInt(KEY_PENDING_CODE, 0)
		set(value) {
			prefs.edit().putInt(KEY_PENDING_CODE, value).commit()
		}

	/** Olvida el APK pendiente. No borra el archivo: eso lo decide
	 *  quien llama, que es el único que sabe si ya se instaló. */
	fun clearPending() {
		prefs.edit()
			.remove(KEY_PENDING_PATH)
			.remove(KEY_PENDING_NAME)
			.remove(KEY_PENDING_CODE)
			.commit()
	}

	fun clearSkip() {
		prefs.edit().remove(KEY_SKIPPED).commit()
	}

	/**
	 * 5.4.1: la última versión que ya abrió la app. Es lo que
	 * distingue una instalación nueva (cero) de una actualización, y
	 * lo que hace que el registro de cambios se enseñe una sola vez
	 * después de instalar.
	 */
	var lastSeenVersionCode: Int
		get() = prefs.getInt(KEY_LAST_SEEN, 0)
		set(value) {
			prefs.edit().putInt(KEY_LAST_SEEN, value).commit()
		}

	/** Todo lo que una copia de seguridad debe llevar. El APK
	 *  pendiente y las claves retiradas se quedan fuera. */
	fun dumpPrefs(): Map<String, Any?> =
		prefs.all.filterKeys { it in BACKUP_KEYS }

	/**
	 * Repone lo importado y borra lo que no venga: una copia es un
	 * reemplazo, no una mezcla. Se espera al disco porque el
	 * registro de cambios lo lee al arrancar. El APK pendiente se
	 * descarta: la copia puede ser de otra versión y su ruta no vale
	 * en este aparato.
	 */
	fun restorePrefs(values: Map<String, Any?>) {
		val editor = prefs.edit().clear()
		for ((key, value) in values) {
			if (key !in BACKUP_KEYS) continue
			when (value) {
				is Boolean -> editor.putBoolean(key, value)
				is Int -> editor.putInt(key, value)
				is Long -> editor.putLong(key, value)
				is Float -> editor.putFloat(key, value)
				is String -> editor.putString(key, value)
			}
		}
		editor.commit()
	}

	/**
	 * Borra las claves de las comprobaciones periódicas, que ya no
	 * existen. No toca las del APK pendiente: la versión actual las
	 * sigue usando y se limpian solas al instalar o descartar.
	 */
	private fun dropObsoleteKeys() {
		val edit = prefs.edit()
		var changed = false
		for (key in OBSOLETE_KEYS) {
			if (prefs.contains(key)) {
				edit.remove(key)
				changed = true
			}
		}
		if (changed) edit.apply()
	}

	companion object {
		const val PREFS = "reloquence_updates"
		/** Se conserva el nombre por compatibilidad; ahora significa
		 *  "comprobar al iniciar". */
		const val KEY_AUTO = "auto_check"
		const val KEY_LAST_CHECK = "last_check_at"
		const val KEY_SKIPPED = "skipped_version_code"
		const val KEY_LAST_SEEN = "last_seen_version_code"
		const val KEY_PENDING_PATH = "pending_apk_path"
		const val KEY_PENDING_NAME = "pending_version_name"
		const val KEY_PENDING_CODE = "pending_version_code"

		/** Claves que una copia puede traer de este archivo. */
		val BOOL_KEYS = setOf(KEY_AUTO)
		val INT_KEYS = setOf(KEY_SKIPPED, KEY_LAST_SEEN)
		val LONG_KEYS = setOf(KEY_LAST_CHECK)
		val STRING_KEYS = emptySet<String>()

		/** Lo único que se guarda en una copia de seguridad. */
		val BACKUP_KEYS: Set<String> =
			BOOL_KEYS + INT_KEYS + LONG_KEYS + STRING_KEYS

		/** Claves de versiones anteriores. Las de la comprobación
		 *  periódica se borran del archivo; las del APK pendiente se
		 *  ignoran al leer una copia, pero siguen vivas mientras la
		 *  app las use. */
		val OBSOLETE_KEYS: Set<String> = setOf(
			"interval_hours",
			"last_notified_version_code"
		)

		val LEGACY_KEYS: Set<String> = OBSOLETE_KEYS + setOf(
			KEY_PENDING_PATH,
			KEY_PENDING_NAME,
			KEY_PENDING_CODE
		)
	}
}
