package com.angelonmx.reloquence

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 5.2: el registro de errores.
 *
 * Es un TXT de líneas, una por suceso, con la forma
 *
 *     <fecha ISO-8601> <categoría> <clave>=<valor> ...
 *
 * y una cabecera fija con lo que hace falta para entender el
 * aparato y la edición. Lo que se escribe son **solo** sucesos del
 * ciclo de vida, del motor y de la prosodia: nunca el texto que se
 * está leyendo, ni el contenido de una notificación, ni una ruta
 * personal, ni datos de identidad.
 *
 * El registro está apagado de fábrica y se enciende a mano desde
 * Ajustes. Mientras está apagado no se escribe nada.
 *
 * Límites, para que no se coma el almacenamiento:
 *
 *  - 5000 líneas en memoria (las más recientes);
 *  - el archivo se recorta a la mitad más reciente cuando pasa de
 *    1 MB;
 *  - vive en `filesDir/diagnostico/reloquence-log.txt`, dentro del
 *    almacenamiento propio de la app, así que no hace falta ningún
 *    permiso de almacenamiento.
 *
 * La exportación no envía el archivo interno: arma el TXT completo
 * (cabecera + secciones + línea final) y lo entrega a
 * [Share.prepare], que lo copia a la caché y lo pasa por el
 * FileProvider con [android.content.Intent.ACTION_SEND].
 */
object Diagnostics {

	/** Preferencias propias: no se mezclan con los ajustes de voz. */
	const val PREFS = "reloquence_diagnostics"
	const val KEY_ENABLED = "diagnostics_enabled"
	const val KEY_STARTED = "diagnostics_started"

	/**
	 * 5.3: el registro es más largo que antes porque ahora apunta
	 * también la vida del motor, el plan de cada emisión, los
	 * efectos y los diccionarios. Los topes siguen puestos: 5000
	 * líneas en memoria y 1 MB en disco, y el archivo se recorta a
	 * la mitad más reciente cuando pasa.
	 */
	private const val MAX_LINES = 5000
	private const val MAX_BYTES = 1024 * 1024
	private const val DIR = "diagnostico"
	private const val FILE = "reloquence-log.txt"

	/** La última línea del TXT. Es la promesa de que se puede
	 *  compartir tal cual. */
	internal const val FINAL_LINE = "# No user content included. Safe to share."

	private val lock = Any()
	private val memory = ArrayDeque<String>()

	/**
	 * 5.2.1: las escrituras van por su propio hilo.
	 *
	 * El registro se apunta desde el hilo de síntesis, que es el que
	 * el sistema mide para decidir si el motor responde. Escribir el
	 * archivo ahí --y peor, recortarlo leyendo 200 KB-- era una
	 * pausa audible en cada emisión cuando el registro estaba
	 * encendido. Ahora [log] solo formatea, guarda la línea en
	 * memoria y encola; el disco lo toca este hilo y nunca la
	 * síntesis.
	 */
	private val writer = Executors.newSingleThreadExecutor { task ->
		Thread(task, "evv-diagnostics").apply { isDaemon = true }
	}

	/** Copia del interruptor para no leer preferencias por cada
	 *  línea. La actualiza [setEnabled]; se carga una vez por
	 *  proceso. */
	@Volatile
	private var enabledCache: Boolean? = null

	private fun prefs(context: Context) =
		context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

	/** Si el usuario activó el registro. */
	fun isEnabled(context: Context): Boolean {
		enabledCache?.let { return it }
		val value = prefs(context).getBoolean(KEY_ENABLED, false)
		enabledCache = value
		return value
	}

	/**
	 * Enciende o apaga el registro. Al encenderlo desde apagado se
	 * empieza una sesión nueva (archivo y memoria vacíos, hora de
	 * inicio nueva): lo exportado corresponde a lo que se acaba de
	 * reproducir. Apagarlo no borra nada; para eso está [clear].
	 */
	fun setEnabled(context: Context, enabled: Boolean) {
		val p = prefs(context)
		val was = isEnabled(context)
		if (was == enabled) return
		enabledCache = enabled
		val edit = p.edit().putBoolean(KEY_ENABLED, enabled)
		if (enabled) {
			edit.putLong(KEY_STARTED, System.currentTimeMillis())
			synchronized(lock) { memory.clear() }
			// El borrado y la primera línea van a la misma cola, en
			// ese orden: el archivo viejo no puede sobrevivir a la
			// sesión nueva ni colarse después de ella.
			val app = context.applicationContext
			writer.execute {
				synchronized(lock) { runCatching { file(app).delete() } }
			}
		}
		edit.apply()
		if (enabled) log(context, "lifecycle", "event" to "logging_enabled")
	}

	/** Prepara la carpeta y el archivo si el registro está activo. */
	fun install(context: Context) {
		if (!isEnabled(context)) return
		val app = context.applicationContext
		writer.execute {
			synchronized(lock) { runCatching { ensureFile(app) } }
		}
	}

	/** Las preferencias de diagnóstico, para que la copia de
	 *  seguridad las lleve y las restaure: si la casilla de registro
	 *  estaba activa, la copia la deja activa al importar. */
	fun dumpPrefs(context: Context): Map<String, Any?> {
		val p = prefs(context)
		return mapOf(
			KEY_ENABLED to p.getBoolean(KEY_ENABLED, false),
			KEY_STARTED to p.getLong(KEY_STARTED, 0L)
		)
	}

	/** Repone las preferencias de diagnóstico importadas. El estado
	 *  de la casilla (activado/inactivo) queda tal como venía en la
	 *  copia; si se activa, la siguiente sesión de registro empieza
	 *  con esa marca. */
	fun restorePrefs(context: Context, values: Map<String, Any?>) {
		val edit = prefs(context).edit()
		for ((key, value) in values) {
			when (value) {
				is Boolean -> edit.putBoolean(key, value)
				is Long -> edit.putLong(key, value)
				else -> continue
			}
		}
		edit.apply()
		val enabled = values[KEY_ENABLED] as? Boolean
		if (enabled != null) enabledCache = enabled
	}

	/** El archivo interno, exista o no. */
	fun file(context: Context): File =
		File(context.applicationContext.filesDir, "$DIR/$FILE")

	private fun ensureFile(context: Context): File {
		val f = file(context)
		f.parentFile?.mkdirs()
		if (!f.exists()) f.createNewFile()
		return f
	}

	/**
	 * Apunta un suceso. [category] es una palabra sin espacios
	 * (`lifecycle`, `engine`, `prosody`, `error`) y [values] son
	 * pares clave/valor. Los espacios de un valor se cambian por
	 * guiones bajos para que la línea siga siendo analizable.
	 */
	fun log(context: Context, category: String, vararg values: Pair<String, String>) {
		if (!isEnabled(context)) return
		val line = formatLine(nowIso(), category, values.toList())
		synchronized(lock) {
			memory.addLast(line)
			while (memory.size > MAX_LINES) memory.removeFirst()
		}
		val app = context.applicationContext
		writer.execute {
			synchronized(lock) {
				runCatching {
					val f = ensureFile(app)
					f.appendText(line + "\n")
					trim(f)
				}
			}
		}
	}

	/**
	 * Apunta una excepción sin copiar su mensaje: el mensaje puede
	 * traer texto del usuario, una ruta personal o el contenido de
	 * una notificación. Lo que sí queda es la clase y las primeras
	 * líneas de la pila, que es lo que hace falta para localizar el
	 * fallo.
	 */
	fun logError(
		context: Context,
		event: String,
		error: Throwable,
		vararg values: Pair<String, String>
	) {
		if (!isEnabled(context)) return
		val frames = error.stackTrace.take(8).joinToString(",") { frame ->
			val where = frame.fileName?.let { "$it:${frame.lineNumber}" } ?: "?"
			"${frame.className.substringAfterLast('.')}.${frame.methodName}($where)"
		}
		val extra = ArrayList<Pair<String, String>>(values.size + 3)
		extra.add("event" to event)
		extra.add("class" to error.javaClass.simpleName)
		extra.add("frames" to frames)
		extra.addAll(values)
		log(context, "error", *extra.toTypedArray())
	}

	/**
	 * Espera (con tope) a que la cola de escritura vacíe lo apuntado.
	 *
	 * Lo usa [EvvTtsService.onDestroy]: el framework suelta el
	 * servicio y el proceso puede morir justo después, y las
	 * escrituras van por su propio hilo. Sin esta espera, la línea
	 * de cierre y las últimas del motor se quedaban en la cola: el
	 * registro real tenía 35 `service_created` y ningún
	 * `service_destroyed`. El tope evita que un disco lento retrase
	 * la destrucción más de lo razonable.
	 */
	fun flush(timeoutMs: Long = 250L) {
		runCatching {
			writer.submit(java.lang.Runnable { }).get(timeoutMs, TimeUnit.MILLISECONDS)
		}
	}

	/** Borra el archivo y la memoria. La hora de inicio vuelve a
	 *  ahora, para que el siguiente registro empiece limpio. */
	fun clear(context: Context) {
		synchronized(lock) { memory.clear() }
		prefs(context).edit().putLong(KEY_STARTED, System.currentTimeMillis()).apply()
		val app = context.applicationContext
		writer.execute {
			synchronized(lock) { runCatching { file(app).delete() } }
		}
	}

	/**
	 * El TXT completo listo para compartir. Si el archivo no se
	 * pudo leer, se usa la memoria (las últimas 5000 líneas).
	 *
	 * Antes de leer se vacía la cola de escritura (con tope): si no,
	 * lo apuntado en el último instante se quedaría fuera del TXT.
	 */
	fun export(context: Context): ByteArray {
		runCatching { writer.submit(java.lang.Runnable { }).get(2, TimeUnit.SECONDS) }
		val text = synchronized(lock) {
			val stored = runCatching { file(context).readLines() }.getOrDefault(emptyList())
			val lines = if (stored.isEmpty()) memory.toList() else stored
			render(context, lines)
		}
		return text.toByteArray(Charsets.UTF_8)
	}

	/**
	 * Una línea con la forma pedida. Es pura para poder probarla
	 * sin Android: la fecha ya viene formateada.
	 */
	fun formatLine(
		timestamp: String,
		category: String,
		values: List<Pair<String, String>>
	): String = buildString {
		append(timestamp).append(' ').append(category)
		for ((key, value) in values) {
			append(' ').append(key).append('=').append(clean(value))
		}
	}

	private fun render(context: Context, lines: List<String>): String {
		val started = prefs(context).getLong(KEY_STARTED, System.currentTimeMillis())
		val header = listOf(
			"app" to appName(context),
			"version" to BuildConfig.VERSION_NAME,
			"edition" to if (BuildConfig.LITE) "lite" else "full",
			"abi" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "?"),
			"manufacturer" to Build.MANUFACTURER,
			"model" to Build.MODEL,
			"android" to Build.VERSION.RELEASE,
			"sdk" to Build.VERSION.SDK_INT.toString(),
			"start" to iso(started),
			"end" to nowIso()
		)
		return render(header, lines)
	}

	/**
	 * El TXT completo, puro: la cabecera ya viene armada y las líneas
	 * son las del registro. Se agrupa por categoría para que las
	 * secciones sean legibles, conservando el orden dentro de cada
	 * una. Está separado de [render] para poder probarlo sin Android.
	 */
	fun render(header: List<Pair<String, String>>, lines: List<String>): String {
		val out = StringBuilder()
		out.append("# reloquenceTTS diagnostics\n")
		for ((key, value) in header) {
			out.append(clean(key)).append('=').append(clean(value)).append('\n')
		}
		val groups = LinkedHashMap<String, MutableList<String>>()
		for (line in lines) {
			if (line.isBlank()) continue
			val category = line.split(' ', limit = 3).getOrNull(1) ?: "log"
			groups.getOrPut(category) { ArrayList() }.add(line)
		}
		for ((category, group) in groups) {
			out.append('\n').append("--- ").append(category).append(" ---").append('\n')
			for (line in group) out.append(line).append('\n')
		}
		out.append('\n').append(FINAL_LINE).append('\n')
		return out.toString()
	}

	private fun appName(context: Context): String =
		runCatching { context.getString(R.string.app_name) }.getOrDefault("reloquenceTTS")

	/** Lo más reciente del archivo cuando pasa del tope. */
	private fun trim(f: File) {
		if (f.length() <= MAX_BYTES) return
		val all = runCatching { f.readLines() }.getOrNull() ?: return
		if (all.size <= 1) return
		val keep = all.takeLast((all.size + 1) / 2)
		f.writeText(keep.joinToString("\n", postfix = "\n"))
	}

	/** Un valor nunca puede partir la línea ni llevar saltos. */
	private fun clean(value: String): String =
		value.replace(Regex("\\s+"), "_").take(200)

	/** Fecha y hora local en ISO-8601, con su desplazamiento. */
	private fun nowIso(): String = iso(System.currentTimeMillis())

	private fun iso(millis: Long): String {
		val zone = TimeZone.getDefault()
		val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
		format.timeZone = zone
		val offset = zone.getOffset(millis)
		val sign = if (offset >= 0) '+' else '-'
		val abs = kotlin.math.abs(offset)
		val hours = abs / 3_600_000
		val minutes = (abs % 3_600_000) / 60_000
		return format.format(Date(millis)) + String.format(Locale.US, "%c%02d:%02d", sign, hours, minutes)
	}
}
