package com.angelonmx.reloquence

import android.content.Context

/**
 * 5.4: lo que el apartado de actualizaciones recuerda entre
 * aperturas.
 *
 * Son preferencias propias, no las del motor: nada de esto cambia
 * cómo suena una voz, así que no tiene por qué despertar al
 * servicio ni entrar en una copia de seguridad del diccionario.
 *
 * [autoCheck] viene activado de fábrica. Es una comprobación de un
 * archivo pequeño y estático, sin token y sin datos de nadie; quien
 * no la quiera la apaga con el interruptor y WorkManager deja de
 * programarla.
 */
class UpdatePrefs(context: Context) {

	private val prefs = context.applicationContext
		.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

	var autoCheck: Boolean
		get() = prefs.getBoolean(KEY_AUTO, true)
		set(value) {
			prefs.edit().putBoolean(KEY_AUTO, value).commit()
		}

	/** Una de [INTERVALS]. Si el archivo trae otra cosa, se vuelve a
	 *  las 24 horas en vez de programar algo que WorkManager no
	 *  acepta. */
	var intervalHours: Int
		get() = prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL)
			.takeIf { it in INTERVALS } ?: DEFAULT_INTERVAL
		set(value) {
			val safe = value.takeIf { it in INTERVALS } ?: DEFAULT_INTERVAL
			prefs.edit().putInt(KEY_INTERVAL, safe).commit()
		}

	/** Milisegundos desde la época de la última comprobación que
	 *  terminó, con éxito o sin él. Cero es "nunca". */
	var lastCheckAt: Long
		get() = prefs.getLong(KEY_LAST_CHECK, 0L)
		set(value) {
			prefs.edit().putLong(KEY_LAST_CHECK, value).commit()
		}

	/** La última versión por la que ya se avisó. Evita que la
	 *  comprobación periódica repita la misma notificación cada dos
	 *  horas. */
	var lastNotifiedVersionCode: Int
		get() = prefs.getInt(KEY_NOTIFIED, 0)
		set(value) {
			prefs.edit().putInt(KEY_NOTIFIED, value).commit()
		}

	/** La versión que el usuario dejó para después con "Ahora no".
	 *  La comprobación automática la respeta; la búsqueda manual,
	 *  no: si alguien pide buscar, quiere ver lo que hay. */
	var skippedVersionCode: Int
		get() = prefs.getInt(KEY_SKIPPED, 0)
		set(value) {
			prefs.edit().putInt(KEY_SKIPPED, value).commit()
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

	/** Todo lo que guarda este archivo, para la copia de seguridad. */
	fun dumpPrefs(): Map<String, Any?> = prefs.all.toMap()

	/**
	 * Repone lo importado y borra lo que no venga: una copia es un
	 * reemplazo, no una mezcla. Se espera al disco porque el
	 * registro de cambios y el programador lo leen al arrancar.
	 */
	fun restorePrefs(values: Map<String, Any?>) {
		val editor = prefs.edit().clear()
		for ((key, value) in values) {
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

	companion object {
		const val PREFS = "reloquence_updates"
		const val KEY_AUTO = "auto_check"
		const val KEY_INTERVAL = "interval_hours"
		const val KEY_LAST_CHECK = "last_check_at"
		const val KEY_NOTIFIED = "last_notified_version_code"
		const val KEY_SKIPPED = "skipped_version_code"
		const val KEY_LAST_SEEN = "last_seen_version_code"

		/** Claves y tipos que una copia puede traer de este archivo. */
		val BOOL_KEYS = setOf(KEY_AUTO)
		val INT_KEYS = setOf(KEY_INTERVAL, KEY_NOTIFIED, KEY_SKIPPED, KEY_LAST_SEEN)
		val LONG_KEYS = setOf(KEY_LAST_CHECK)

		/** El mínimo de WorkManager son 15 minutos, pero una
		 *  actualización cada dos horas ya es más de lo que
		 *  necesita una app que no cambia todas las semanas. */
		val INTERVALS = listOf(2, 6, 12, 24, 48, 72)
		const val DEFAULT_INTERVAL = 24
	}
}
