package com.angelonmx.reloquence

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * Los nombres de emojis y símbolos que viajan con la app.
 *
 * Antes cada nombre estaba escrito a mano en [EmojiSpeech]. Las
 * tablas de CLDR traen los mismos nombres con las variantes que
 * faltaban y, sobre todo, lo que Unicode añade cada año sin que
 * haya que tocar el código. El archivo de cada idioma se lee una
 * sola vez y se queda en memoria; si no está, se usa el inglés y,
 * si tampoco, las tablas de siempre.
 *
 * [install] se llama desde el servicio y desde los ajustes. Sin
 * contexto (pruebas de la máquina) todo esto devuelve vacío y
 * [EmojiSpeech] sigue funcionando con sus tablas.
 */
internal object CldrData {

	@Volatile
	private var app: Context? = null

	private val emoji = ConcurrentHashMap<String, Map<String, String>>()
	private val names = ConcurrentHashMap<Int, String>()

	@Volatile
	private var namesLoaded = false

	fun install(context: Context) {
		app = context.applicationContext
	}

	/** Deja leída la tabla del idioma antes de la primera emisión.
	 *  El primer emoji no debe pagar el gzip en el hilo de síntesis. */
	fun prewarm(lang: String, unicode: Boolean) {
		if (app == null) return
		emojiTable(assetCode(lang))
		if (unicode) loadNames()
	}

	/** Un nombre CLDR para la secuencia que empieza en [at], o nulo. */
	fun emojiAt(text: String, at: Int, lang: String): Match? {
		val table = emojiTable(assetCode(lang)) ?: return null
		val cps = ArrayList<Int>(MAX_SEQUENCE)
		val ends = ArrayList<Int>(MAX_SEQUENCE)
		var i = at
		while (i < text.length && cps.size < MAX_SEQUENCE) {
			val cp = text.codePointAt(i)
			when {
				cp == 0xFE0E || cp == 0xFE0F -> Unit
				cp in 0x1F3FB..0x1F3FF -> Unit
				cp in 0xE0020..0xE007E -> Unit
				cp == 0xE007F -> Unit
				else -> {
					cps.add(cp)
					ends.add(i + Character.charCount(cp))
				}
			}
			i += Character.charCount(cp)
		}
		for (size in cps.size downTo 1) {
			val key = buildString(size * 5) {
				for (n in 0 until size) {
					if (n > 0) append(' ')
					append(Integer.toHexString(cps[n]).uppercase(java.util.Locale.ROOT))
				}
			}
			table[key]?.let { return Match(it, ends[size - 1]) }
		}
		return null
	}

	internal class Match(val name: String, val end: Int)

	/** El nombre oficial del símbolo [cp], o nulo si no está. */
	fun unicodeName(cp: Int): String? {
		if (!namesLoaded) loadNames()
		return names[cp]
	}

	private fun emojiTable(code: String): Map<String, String>? {
		emoji[code]?.let { return it }
		val context = app ?: return null
		var table = readEmoji(context, code)
		if (table == null && code != "en") table = readEmoji(context, "en")
		if (table == null) return null
		emoji[code] = table
		return table
	}

	private fun readEmoji(context: Context, code: String): Map<String, String>? {
		val lines = readLines(context, "cldr/emoji-$code.tsv.gz") ?: return null
		val table = HashMap<String, String>(lines.size * 2)
		for (line in lines) {
			val tab = line.indexOf('\t')
			if (tab <= 0) continue
			table[line.substring(0, tab)] = line.substring(tab + 1)
		}
		return table
	}

	private fun loadNames() {
		synchronized(this) {
			if (namesLoaded) return
			val context = app ?: return
			val lines = readLines(context, "unicode/names.tsv.gz")
			if (lines != null) {
				for (line in lines) {
					val tab = line.indexOf('\t')
					if (tab <= 0) continue
					val cp = line.substring(0, tab).toIntOrNull(16) ?: continue
					names[cp] = line.substring(tab + 1)
				}
			}
			namesLoaded = true
		}
	}

	/**
	 * AAPT descomprime los assets acabados en `.gz` y les quita la
	 * extensión antes de meterlos en el APK; en el árbol de fuentes
	 * siguen comprimidos. Se prueban los dos nombres y se mira el
	 * magic de gzip, para que funcione igual empaquetado o no.
	 */
	private fun readLines(context: Context, path: String): List<String>? {
		for (name in arrayOf(path, path.removeSuffix(".gz"))) {
			val lines = runCatching {
				context.assets.open(name).use { decode(it) }
			}.getOrNull()
			if (lines != null) return lines
		}
		return null
	}

	/** Lee texto plano o gzip según lo que digan los dos primeros
	 *  bytes. Separado para poder probarlo sin un APK. */
	internal fun decode(raw: java.io.InputStream): List<String> {
		val input = BufferedInputStream(raw)
		input.mark(2)
		val first = input.read()
		val second = input.read()
		input.reset()
		val stream = if (first == 0x1F && second == 0x8B) GZIPInputStream(input) else input
		return stream.use {
			BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader ->
				reader.readLines()
			}
		}
	}

	/** El código del archivo CLDR para la etiqueta del motor. */
	private fun assetCode(lang: String): String = when (lang) {
		"spa" -> "es"
		"eng" -> "en"
		"fra" -> "fr"
		"deu" -> "de"
		"ita" -> "it"
		"jpn" -> "ja"
		"por" -> "pt"
		"fin" -> "fi"
		"pol" -> "pl"
		"tur" -> "tr"
		"rus" -> "ru"
		"cmn" -> "zh"
		"yue" -> "yue"
		"kor" -> "ko"
		"nld" -> "nl"
		"nor" -> "no"
		"swe" -> "sv"
		"dan" -> "da"
		else -> "en"
	}

	private const val MAX_SEQUENCE = 16
}
