package com.angelonmx.reloquence

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Lo que muestra la pantalla de ajustes, y el motor que puede
 * escucharse a sí mismo.
 *
 * 4.1: es un ViewModel (sobrevive a la rotación sin reabrir el
 * motor ni releer el diccionario) y el trabajo de fondo va con
 * corrutinas en vez de Thread+Handler a mano.
 *
 * La pantalla edita números del motor y muestra porcentajes, y
 * aquí es donde las dos cosas se encuentran. Todo lo que se escribe
 * aquí va directamente a [Settings], que es lo que lee el servicio
 * de voz, así que un cambio está en vigor antes de que la
 * muestra que lo demuestre haya terminado.
 */
class SettingsModel(private val app: Application) : ViewModel() {

	private val settings = Settings(app)

	/**
	 * 5.3: una línea por cambio de ajuste. Solo el nombre y el
	 * valor numérico o booleano: nunca texto del usuario, ni el
	 * nombre de un archivo, ni una ruta. Si el registro está
	 * apagado, [Diagnostics.log] no escribe nada.
	 */
	private fun diag(category: String, vararg values: Pair<String, String>) =
		Diagnostics.log(app, category, *values)

	init {
		// 5.0: las tablas CLDR viven en los assets. La vista previa
		// también las usa, no solo el servicio.
		CldrData.install(app)
	}

	private var preview: EvvPreview? =
		resolveLanguage().let { if (it != 0) EvvPreview(it) else null }

	/** 4.3: hasta donde se ha leido de los ajustes. Se comparan con
	 *  los contadores de [SettingsSignal] para saber si hay algo nuevo
	 *  que mirar sin releer nada antes de tiempo. */
	private var seenEpoch: Long = SettingsSignal.epoch
	private var seenDictionaryRevision: Long = SettingsSignal.dictionaryRevision

	private var pendingSync: kotlinx.coroutines.Job? = null
	private var pendingDictionary = false

	/**
	 * 4.3: la pantalla esta atenta a los cambios de los ajustes, y no
	 *  solo a los que ella misma hace.
	 *
	 * Sin esto, importar una copia o un diccionario desde el "Abrir
	 * con" (que es otra actividad) se oia pero no se veia, y los
	 * deslizadores seguian enseñando lo de antes hasta reiniciar el
	 * servicio. Los avisos llegan ya al hilo principal, asi que aqui
	 * lo unico que hay que hacer es esperar a que se calle lo que se
	 * esta cambiando: un deslizador que se arrastra escribe en cada
	 * paso, y releer sesenta valores mas los perfiles en cada uno
	 * seria trabajo de mas. Se cancela y se vuelve a esperar en cada
	 * aviso, asi que un arrastre entero cuesta una sola lectura, la
	 * de cuando se suelta.
	 */
	private val onSettingsChanged: (SettingsSignal.Change) -> Unit = { change ->
		pendingDictionary = pendingDictionary || change.dictionary
		pendingSync?.cancel()
		pendingSync = viewModelScope.launch {
			delay(SYNC_QUIET_MS)
			pendingSync = null
			val withDictionary = pendingDictionary
			pendingDictionary = false
			seenEpoch = SettingsSignal.epoch
			seenDictionaryRevision = SettingsSignal.dictionaryRevision
			reloadAll(withDictionary)
		}
	}

	/** Idioma de la vista previa actual. Normalmente es el del
	 *  ajuste; la detección automática al probar lo puede mover. */
	private var previewLang: Int = resolveLanguage()

	override fun onCleared() {
		// 4.3: fuera de la lista de los que miran los cambios. Sin esto
		// el ViewModel se queda apuntado y sus releer siguen llegando
		// despues de destruido, tocando estado que ya no se dibuja.
		SettingsSignal.removeWatcher(onSettingsChanged)
		pendingSync?.cancel()
		pendingSync = null
		preview?.close()
		preview = null
	}

	var voice by mutableStateOf(settings.voice)
		private set

	var language by mutableStateOf(previewLanguage())
		private set

	var abbreviations by mutableStateOf(settings.abbreviations)
		private set

	/** 4.3: si se ignoran los códigos de voz del texto y de los
	 *  diccionarios. Apagado es lo de fábrica. */
	var ignoreVoiceTags by mutableStateOf(settings.ignoreVoiceTags)
		private set

	var sampleRateHz by mutableStateOf(settings.sampleRateHz)
		private set

	var pauses by mutableStateOf(settings.pauses)
		private set

	var pauseDurationMs by mutableStateOf(settings.pauseDurationMs)
		private set

	var volume by mutableStateOf(settings.volume)
		private set

	var eqEnabled by mutableStateOf(settings.eqEnabled)
		private set

	var eqBands by mutableStateOf(settings.eqBands().toList())
		private set

	var reverbEnabled by mutableStateOf(settings.reverbEnabled)
		private set

	var reverbLevel by mutableStateOf(settings.reverbLevel)
		private set

	var bassDb by mutableStateOf(settings.bassDb)
		private set

	var presenceDb by mutableStateOf(settings.presenceDb)
		private set

	var echoEnabled by mutableStateOf(settings.echoEnabled)
		private set

	var echoLevel by mutableStateOf(settings.echoLevel)
		private set

	var echoTimeMs by mutableStateOf(settings.echoTimeMs)
		private set

	var compEnabled by mutableStateOf(settings.compressorEnabled)
		private set

	var boostPercent by mutableStateOf(settings.boostPercent)
		private set

	var gateEnabled by mutableStateOf(settings.gateEnabled)
		private set

	var tremoloLevel by mutableStateOf(settings.tremoloLevel)
		private set

	var tremoloRate by mutableStateOf(settings.tremoloRate)
		private set

	var phaserEnabled by mutableStateOf(settings.phaserEnabled)
		private set

	var vibratoLevel by mutableStateOf(settings.vibratoLevel)
		private set

	var ringmodLevel by mutableStateOf(settings.ringmodLevel)
		private set

	var fuzzLevel by mutableStateOf(settings.fuzzLevel)
		private set

	var wahLevel by mutableStateOf(settings.wahLevel)
		private set

	var maximizerLevel by mutableStateOf(settings.maximizerLevel)
		private set

	/** 4.3: los cinco niveles nuevos de efectos. */
	var tapeMix by mutableStateOf(settings.tapeMix)
		private set

	var subBassMix by mutableStateOf(settings.subBassMix)
		private set

	var reverbSize by mutableStateOf(settings.reverbSize)
		private set

	var crusherBits by mutableStateOf(settings.crusherBits)
		private set

	var deesserDepth by mutableStateOf(settings.deesserDepth)
		private set

	var autoDetectLanguage by mutableStateOf(settings.autoDetectLanguage)
		private set

	/** Variante elegida por idioma detectado (motor, 0 = primera). */
	var autoVariantEn by mutableStateOf(settings.autoVariantEn)
		private set

	var autoVariantEs by mutableStateOf(settings.autoVariantEs)
		private set

	var autoVariantFr by mutableStateOf(settings.autoVariantFr)
		private set

	var telephoneEnabled by mutableStateOf(settings.telephoneEnabled)
		private set

	var normalizerEnabled by mutableStateOf(settings.normalizerEnabled)
		private set

	var highpassEnabled by mutableStateOf(settings.highpassEnabled)
		private set

	var driveEnabled by mutableStateOf(settings.driveEnabled)
		private set

	var chorusEnabled by mutableStateOf(settings.chorusEnabled)
		private set

	var crusherEnabled by mutableStateOf(settings.crusherEnabled)
		private set

	var flangerEnabled by mutableStateOf(settings.flangerEnabled)
		private set

	var exciterLevel by mutableStateOf(settings.exciterLevel)
		private set

	var warmthEnabled by mutableStateOf(settings.warmthEnabled)
		private set

	var deesserEnabled by mutableStateOf(settings.deesserEnabled)
		private set

	var hdEnhance by mutableStateOf(settings.hdEnhance)
		private set

	var appLocale by mutableStateOf(settings.appLocale)
		private set

	var processEmojis by mutableStateOf(settings.processEmojis)
		private set

	var readUnicodeSymbols by mutableStateOf(settings.readUnicodeSymbols)
		private set

	var punctEnabled by mutableStateOf(settings.punctEnabled)
		private set

	var punctLevel by mutableStateOf(settings.punctLevel)
		private set

	var skipParentheses by mutableStateOf(settings.skipParentheses)
		private set

	var numberForceEnabled by mutableStateOf(settings.numberForceEnabled)
		private set

	var numberGroup by mutableStateOf(settings.numberGroup)
		private set

	var mixEnabled by mutableStateOf(settings.mixEnabled)
		private set

	var mixPartner by mutableStateOf(settings.mixPartner)
		private set

	var mixInterval by mutableStateOf(settings.mixInterval)
		private set

	var bodyEnabled by mutableStateOf(settings.bodyEnabled)
		private set

	/** Si hay importación o exportación en curso (en fondo). */
	var dictBusy by mutableStateOf(false)
		private set

	/** Error del último intento de importar diccionario, listo
	 *  para mostrar. 4.0: distingue copia vs diccionario. */
	var dictError: String? by mutableStateOf(null)
		private set

	fun clearDictError() {
		dictError = null
	}

	/** Si hay copia de seguridad en curso (en fondo). */
	var backupBusy by mutableStateOf(false)
		private set

	/** Resultado de la última copia: mensaje listo para mostrar. */
	var backupResult: String? by mutableStateOf(null)
		private set

	/** Si tras importar conviene recrear la pantalla (cambió el
	 *  idioma de la app). */
	var backupNeedsRestart = false
		private set

	/** Si hay una copia recién guardada para compartir. */
	var backupCanShare by mutableStateOf(false)
		private set

	/** Archivo listo para el selector de compartir del sistema. */
	var shareTarget by mutableStateOf<Share.Target?>(null)
		private set

	fun enqueueShare(folder: String, name: String, mime: String, bytes: ByteArray) {
		shareTarget = Share.prepare(app, folder, name, mime, bytes)
	}

	fun clearShareTarget() {
		shareTarget = null
	}

	/**
	 * 5.2: si el registro de errores está activo. Vive en sus
	 * propias preferencias, así que no pasa por [Settings] ni por
	 * el aviso de cambios del motor.
	 */
	var diagnosticsEnabled by mutableStateOf(Diagnostics.isEnabled(app))
		private set

	/**
	 * 5.4.1: el registro de cambios que se enseña una sola vez
	 * después de instalar una versión nueva. Nulo cuando no hay
	 * nada pendiente. Se consulta al crear la pantalla: si esta
	 * instalación es más nueva que la última que abrió la app, se
	 * enseña; en una instalación nueva no hay nada que contar.
	 */
	var postUpdateChangelog by mutableStateOf<ChangelogEntry?>(
		PostUpdateChangelog.pending(app)
	)
		private set

	fun closePostUpdateChangelog() {
		PostUpdateChangelog.acknowledge(app)
		postUpdateChangelog = null
	}

	fun chooseDiagnostics(on: Boolean) {
		diagnosticsEnabled = on
		Diagnostics.setEnabled(app, on)
		diag("lifecycle", "event" to "diagnostics_toggle", "enabled" to on.toString())
	}

	/** Arma el TXT completo y lo deja al selector de compartir. */
	fun exportDiagnostics() {
		val bytes = Diagnostics.export(app)
		diag("diagnostics", "event" to "export", "bytes" to bytes.size.toString())
		enqueueShare("diagnostico", "reloquence-diagnostico.txt", "text/plain", bytes)
	}

	fun clearDiagnostics() {
		Diagnostics.clear(app)
		diag("diagnostics", "event" to "clear")
	}

	/** Una copia pendiente de confirmar su importación. */
	var pendingBackup: BackupInfo? by mutableStateOf(null)
		private set

	private var pendingParsed: BackupStore.ParsedBackup? = null
	private var lastBackupBytes: ByteArray? = null
	private var lastBackupName: String = ""

	fun clearBackupResult() {
		backupResult = null
		backupNeedsRestart = false
	}

	fun cancelPendingBackup() {
		pendingBackup = null
		pendingParsed = null
	}

	/** La última copia guardada, lista para compartir. */
	fun shareLastBackup(context: android.content.Context): android.net.Uri? {
		val bytes = lastBackupBytes ?: return null
		return runCatching {
			val dir = java.io.File(context.cacheDir, "backups").apply { mkdirs() }
			val name = BackupStore.fileName(lastBackupName)
			val file = java.io.File(dir, name)
			file.writeBytes(bytes)
			androidx.core.content.FileProvider.getUriForFile(
				context, "${context.packageName}.fileprovider", file
			)
		}.getOrNull()
	}

	/** Lo que una copia trae, en líneas listas para mostrar. */
	data class BackupInfo(
		val name: String,
		val lines: List<String>,
		val warnings: List<String> = emptyList(),
		val importable: Boolean = true
	)

	var fxProfile by mutableStateOf(settings.fxProfile)
		private set

	var fxProfiles by mutableStateOf(settings.fxProfiles)
		private set

	var voiceProfile by mutableStateOf(settings.voiceProfile)
		private set

	var voiceProfiles by mutableStateOf(settings.voiceProfiles)
		private set

	private val shape = mutableStateMapOf<Int, Int>().apply { putAll(settings.shape(settings.voice)) }

	var dictionary: String by mutableStateOf(
		settings.dictionaryForLanguage(settings.activeDictionaryLanguage)?.name
			?: app.getString(R.string.dictionary_none)
	)
		private set

	/** Todos los diccionarios guardados, de todos los idiomas. */
	var dictionaryLibrary by mutableStateOf(settings.dictionaries)
		private set

	/** Entradas del archivo del diccionario, en orden. Se leen en
	 *  fondo: un archivo grande en el hilo principal cuelga la
	 *  pantalla justo al abrir este apartado. */
	var fileEntries: List<Pair<String, String>> by mutableStateOf(emptyList())
		private set

	/** Si el archivo del diccionario aún se está leyendo. */
	var dictLoading by mutableStateOf(true)
		private set

	/** Todas las entradas ya ordenadas y listas para mostrar.
	 *  Se arma una vez por cambio, no en cada dibujado: con miles
	 *  de palabras eso también colgaba la lista. */
	var listedEntries: List<Pair<String, String>> by mutableStateOf(emptyList())
		private set

	/** Orden de la lista: 0 archivo, 1 alfabético, 2 larga
	 *  primero, 3 corta primero. */
	var dictOrder by mutableStateOf(settings.dictOrder)
		private set

	fun chooseDictOrder(order: Int) {
		dictOrder = order.coerceIn(0, 3)
		settings.dictOrder = dictOrder
		rebuildListed()
	}

	val gender: Int get() = shape[Eci.VOICE_GENDER] ?: 0

	init {
		// 4.3: la pantalla se apunta a los cambios de los ajustes. Va
		// despues de leer todo, porque lo que hay aqui ya esta al dia.
		SettingsSignal.addWatcher(onSettingsChanged)
		preview?.open { refreshShape() }
		refreshFileEntries()
	}

	/** Lee el archivo en fondo y refresca lo mostrado. */
	private fun refreshFileEntries() {
		viewModelScope.launch(Dispatchers.IO) {
			val parsed = currentFileEntries()
			withContext(Dispatchers.Main) {
				fileEntries = parsed
				dictLoading = false
				rebuildListed()
			}
		}
	}

	private fun rebuildListed() {
		// 4.2: una sola entrada por palabra, y entre las repetidas
		// del archivo gana la primera, que es lo que hace el motor.
		// Las palabras propias del editor van encima, que es lo que
		// ya fazia la exportación. Antes esto era `toMap()`, que
		// deja la última: la pantalla y lo que se oía no eran lo
		// mismo, y por eso "editar" una palabra repetida no cambiaba
		// lo que se oía.
		val merged = LinkedHashMap<String, String>()
		for ((key, say) in fileEntries) merged.putIfAbsent(key, say)
		for ((key, say) in wordEntries) merged[key] = say
		val list = merged.toList()
		listedEntries = when (dictOrder) {
			2 -> list.sortedWith(compareByDescending<Pair<String, String>> { it.first.length }.thenBy { it.first })
			3 -> list.sortedWith(compareBy<Pair<String, String>> { it.first.length }.thenBy { it.first })
			0 -> list
			else -> list.sortedBy { it.first }
		}
	}

	fun stopSpeaking() {
		preview?.stopSpeaking()
	}

	fun close() {
		preview?.close()
	}

	/** Los idiomas que trae esta compilación, como palabras ECI. */
	fun languages(): List<Int> = EvvEngine.available.toList()

	fun chooseVoice(which: Int) {
		voice = which
		settings.voice = which
		markVoiceManual()
		// 4.0: no se escribe nada al cambiar. Lo no tocado suena
		// como el preset y se muestra desde el motor.
		shape.clear()
		shape.putAll(settings.shape(which))
		diag("settings", "event" to "voice", "voice" to which.toString())
	}

	/** Cambia el idioma del motor y de la vista previa.
	 *  Cero significa seguir el idioma del sistema. */
	fun chooseLanguage(lang: Int) {
		if (lang != 0 && !EvvEngine.available.contains(lang)) return
		language = lang
		settings.defaultLanguage = lang
		switchPreview(resolveLanguage())
		diag("settings", "event" to "language", "engine" to (Eci.bcp47Of(lang) ?: "?"))
	}

	/** Cambia solo la vista previa a [lang], sin tocar el ajuste.
	 *  Lo usa la detección automática al probar: la muestra suena
	 *  en el idioma detectado y la pantalla lo refleja. */
	private fun switchPreview(lang: Int) {
		if (lang == previewLang) return
		previewLang = lang
		language = lang
		preview?.close()
		preview = if (lang != 0) EvvPreview(lang).also { it.open { refreshShape() } }
		else null
	}

	fun setShape(param: Int, value: Int) {
		val settled = Eci.clampVoice(param, value)
		if (shape[param] == settled) return
		shape[param] = settled
		settings.setShapeValue(voice, param, settled)
		markVoiceManual()
		diag(
			"settings", "event" to "shape",
			"voice" to voice.toString(),
			"param" to VoicePlan.nameOf(param),
			"value" to settled.toString()
		)
	}

	/** Restablece todo un apartado a sus valores iniciales y
	 *  refresca la pantalla. 4.3: además pide reiniciar el motor,
	 *  porque un apartado entero devuelto a sus valores no es un
	 *  cambio cualquiera y la voz que había en el motor era la
	 *  anterior. */
	fun resetVoiceCategory() {
		settings.resetVoiceCategory()
		settings.engineReload("voz devuelta a sus valores")
		reloadAll()
		diag("settings", "event" to "reset", "section" to "voice")
	}

	fun resetReadingCategory() {
		settings.resetReadingCategory()
		settings.engineReload("lectura devuelta a sus valores")
		reloadAll()
		diag("settings", "event" to "reset", "section" to "reading")
	}

	fun resetFxCategory() {
		settings.resetFxCategory()
		settings.engineReload("efectos devueltos a sus valores")
		reloadAll()
		diag("settings", "event" to "reset", "section" to "fx")
	}

	fun chooseAbbreviations(on: Boolean) {
		abbreviations = on
		settings.abbreviations = on
		diag("settings", "event" to "abbreviations", "on" to on.toString())
	}

	/**
	 * 4.3: ignora (o no) los códigos de voz del texto y de los
	 * diccionarios. Encendido se oye en la siguiente frase, sin
	 * reiniciar el motor: el texto se limpia al pasar por delante.
	 */
	fun chooseIgnoreVoiceTags(on: Boolean) {
		ignoreVoiceTags = on
		settings.ignoreVoiceTags = on
		diag("settings", "event" to "ignore_voice_tags", "on" to on.toString())
	}

	// ---- diccionario ----------------------------------------------------

/**
 * Toma una copia del archivo que el selector devolvió.
 *
 * Un documento elegido es un URI de contenido que pertenece a
 * cualquier app que lo haya suministrado, legible ahora y
 * quizás no mañana, y el servicio de voz es un proceso distinto
 * que lee este al arrancar. Así que lo que se guarda es una
 * copia de la nuestra en vez de una referencia a la de otro,
 * conservada en almacenamiento protegido contra arranque para
 * que el servicio pueda leerla aún con el teléfono bloqueado.
 *
	 * Se aceptan archivos .cfd (el diccionario de Eloquence) y
	 * .reloqdic (cabecera propia y cuerpo tabulado).
	 */
	fun chooseDictionary(uri: android.net.Uri) {
		if (dictBusy) return
		dictBusy = true
		dictError = null
		viewModelScope.launch(Dispatchers.IO) {
			// 4.0: si es copia, avisar en vez de decir "vacío".
			if (BackupStore.looksLikeBackup(app.contentResolver, uri)) {
				withContext(Dispatchers.Main) {
					dictBusy = false
					dictError = app.getString(R.string.dict_is_backup)
				}
				return@launch
			}
			val into = DirectBoot.dictionaries(app).apply { mkdirs() }
			val picked = nameOf(uri) ?: "dictionary.reloqdic"
			val stamp = System.nanoTime()
			val tmp = java.io.File(into, "import-$stamp.tmp")
			try {
				app.contentResolver.openInputStream(uri).use { source ->
					if (source == null) {
						tmp.delete()
						withContext(Dispatchers.Main) {
							dictBusy = false
							dictError = app.getString(R.string.dict_import_error)
						}
						return@launch
					}
					tmp.outputStream().use { sink -> source.copyTo(sink) }
				}
			} catch (unreadable: Exception) {
				Timber.e(unreadable, "no se puede leer el diccionario elegido")
				tmp.delete()
				withContext(Dispatchers.Main) {
					dictBusy = false
					dictError = app.getString(R.string.dict_import_error)
				}
				return@launch
			}
			// 4.0: el tmp podría ser copia renombrada.
			if (Dictionaries.entries(tmp).isEmpty() && BackupStore.looksLikeBackup(app.contentResolver, uri)) {
				tmp.delete()
				withContext(Dispatchers.Main) {
					dictBusy = false
					dictError = app.getString(R.string.dict_is_backup)
				}
				return@launch
			}
			val entries = Dictionaries.entries(tmp)
			// 5.1: las repetidas se resuelven dentro del archivo. El
			// diccionario importado es una pieza nueva, no se mezcla
			// con el que estuviera activo en ese idioma.
			val grouped = LinkedHashMap<String, MutableList<String>>()
			for ((key, say) in entries) grouped.getOrPut(key) { ArrayList(1) }.add(say)
			val dups = grouped.count { it.value.size > 1 }
			val near = Dictionaries.nearDuplicates(entries)
			val format = Dictionaries.formatOf(tmp)
			withContext(Dispatchers.Main) {
				dictBusy = false
				if (entries.isEmpty()) {
					tmp.delete()
					dictError = app.getString(R.string.dict_import_error)
					diag("dictionary", "event" to "import_empty")
					return@withContext
				}
				dictError = null
				diag(
					"dictionary", "event" to "import_preview",
					"entries" to entries.size.toString(),
					"format" to format,
					"duplicates" to dups.toString()
				)
				val groups = grouped.filterValues { it.size > 1 }.map { (key, says) ->
					DupGroup(
						key = key,
						current = emptyList(),
						incoming = distinct(says)
					)
				}
				// 5.1: siempre hay vista previa, también cuando el
				// archivo no trae repetidas. Es la única forma de
				// elegir idioma y nombre antes de guardarlo.
				pendingImport = PendingImport(
					tmpPath = tmp.absolutePath,
					pickedName = picked,
					entries = entries,
					duplicates = dups,
					near = near,
					groups = groups,
					preview = importPreviewLines(entries, format, app),
					suggestedName = dictionaryNameFrom(picked),
					language = settings.activeDictionaryLanguage
				)
			}
		}
	}

	private fun dictionaryNameFrom(picked: String): String {
		val base = picked.substringBeforeLast('.').trim()
		return base.take(60).ifEmpty { "diccionario" }
	}

	private fun distinct(says: List<String>): List<String> {
		val out = LinkedHashSet<String>()
		for (s in says) {
			val clean = s.trim()
			if (clean.isNotEmpty()) out.add(clean)
		}
		return out.toList()
	}

	/**
	 * 4.2: lo que trae el archivo, en líneas para enseñar antes de
	 * importar: cuántas entradas, en qué formato y las primeras
	 * palabras con su pronunciación. Es la misma vista previa que la
	 * de la copia de seguridad, y sirve para confirmar que se eligió
	 * el archivo correcto y no otro con nombre parecido.
	 */
	private fun importPreviewLines(
		entries: List<Pair<String, String>>,
		format: String,
		ctx: android.content.Context
	): List<String> {
		val out = ArrayList<String>(PREVIEW_LINES + 3)
		out.add(ctx.getString(R.string.dict_preview_entries, entries.size))
		out.add(ctx.getString(R.string.dict_preview_format, format))
		out.add(ctx.getString(R.string.dict_preview_head))
		val shown = minOf(entries.size, PREVIEW_LINES)
		for (at in 0 until shown) {
			val (key, say) = entries[at]
			out.add(ctx.getString(R.string.dict_preview_pair, key, say))
		}
		// 4.2: el archivo puede traer la misma palabra dos veces, y
		// eso es justo lo que hay que resolver, así que la vista
		// previa lo dice en vez de dejar que se descubra luego.
		var repeated = 0
		val seen = HashSet<String>()
		for ((key, _) in entries) if (!seen.add(key)) repeated++
		if (repeated > 0) out.add(ctx.getString(R.string.dict_preview_repeated, repeated))
		if (entries.size > shown) {
			out.add(ctx.getString(R.string.dict_preview_more, entries.size - shown))
		}
		return out
	}

	/** Una importación en espera de decidir qué hacer con las
	 *  palabras repetidas. */
	var pendingImport: PendingImport? by mutableStateOf(null)
		private set

	data class PendingImport(
		val tmpPath: String,
		val pickedName: String,
		val entries: List<Pair<String, String>>,
		val duplicates: Int,
		val near: List<Pair<String, String>> = emptyList(),
		/** 4.2: una entrada por palabra repetida, con las
		 *  pronunciaciones que hay en juego: las que ya estaban y
		 *  las que trae el archivo. Es lo que se va preguntando una
		 *  a una cuando se elige decidir. */
		val groups: List<DupGroup> = emptyList(),
		/** Las primeras entradas, para enseñarlas antes de
		 *  importar. */
		val preview: List<String> = emptyList(),
		val suggestedName: String = "",
		val language: Int = 0
	)

	/**
	 * 4.2: una palabra repetida y sus pronunciaciones candidatas.
	 *
	 * [current] es lo que ya está en el diccionario (puede estar
	 * vacío: la palabra es nueva pero el archivo la trae dos
	 * veces) e [incoming] lo que trae el archivo, en su orden. Se
	 * muestran todas juntas porque la pregunta es cuál se deja, no
	 * cuál archivo gana.
	 */
	data class DupGroup(val key: String, val current: List<String>, val incoming: List<String>)

	/**
	 * 4.2: la palabra repetida que se está decidiendo ahora, o -1
	 * si no se está eligiendo una a una. El diálogo se abre con
	 * [beginResolvingOneByOne] y va avanzando sola con cada
	 * elección, así que_importar un archivo con 300 repetidas son
	 * 300 pantallas y no 300 avisos.
	 */
	var resolvingIndex by mutableStateOf(-1)
		private set

	/** Lo elegido en la resolución que va en curso. */
	private val chosen = LinkedHashMap<String, String>()

	/** Guarda el idioma y el nombre elegidos en la vista previa. */
	fun setPendingImportTarget(language: Int, name: String) {
		val pending = pendingImport ?: return
		pendingImport = pending.copy(
			language = if (language == 0) settings.resolvedLanguage() else language,
			suggestedName = name.trim().take(60)
		)
	}

	/** Empieza a preguntar una a una. */
	fun beginResolvingOneByOne() {
		val pending = pendingImport ?: return
		if (pending.groups.isEmpty()) return
		chosen.clear()
		resolvingIndex = 0
	}

	/** Se queda con [say] para la palabra de ahora y pasa a la
	 *  siguiente. Al terminar, importa. */
	fun pickDuplicate(say: String) {
		val pending = pendingImport ?: return
		val group = pending.groups.getOrNull(resolvingIndex) ?: return
		chosen[group.key] = say
		nextDuplicate()
	}

	/** Pasa de esta palabra sin decidirla: se queda la que ya
	 *  estaba, o la primera del archivo si era nueva. */
	fun skipDuplicate() {
		val pending = pendingImport ?: return
		val group = pending.groups.getOrNull(resolvingIndex) ?: return
		chosen.putIfAbsent(group.key, group.current.firstOrNull() ?: group.incoming.first())
		nextDuplicate()
	}

	private fun nextDuplicate() {
		val pending = pendingImport ?: return
		if (resolvingIndex + 1 < pending.groups.size) {
			resolvingIndex++
			return
		}
		val decided = LinkedHashMap<String, String>(chosen)
		resolvingIndex = -1
		chosen.clear()
		finishImport(
			pending.tmpPath, pending.pickedName,
			applyDecisions(pending.entries, pending.groups, decided),
			pending.language, pending.suggestedName
		)
	}

	/**
	 * 4.2: el archivo con una sola entrada por palabra repetida,
	 * en la pronunciación elegida, y colocado en el sitio de su
	 * primera aparición para que el orden del archivo se respete.
	 * Las palabras que no se decidieron se quedan con la que
	 * tenían.
	 */
	private fun applyDecisions(
		entries: List<Pair<String, String>>,
		groups: List<DupGroup>,
		decided: Map<String, String>
	): List<Pair<String, String>> {
		if (groups.isEmpty()) return entries
		val winner = HashMap<String, String>(decided)
		for (g in groups) {
			if (winner.containsKey(g.key)) continue
			winner[g.key] = g.current.firstOrNull() ?: g.incoming.first()
		}
		val out = ArrayList<Pair<String, String>>(entries.size)
		val placed = HashSet<String>()
		for ((key, say) in entries) {
			val chosenSay = winner[key]
			if (chosenSay == null) {
				out.add(key to say)
				continue
			}
			if (placed.add(key)) out.add(key to chosenSay)
		}
		return out
	}

	/** Completa la importación: si [merge] es verdadero se deja una
	 *  sola pronunciación por palabra, si no entran todas. */
	fun finishPendingImport(
		merge: Boolean,
		language: Int = pendingImport?.language ?: settings.activeDictionaryLanguage,
		name: String = pendingImport?.suggestedName.orEmpty()
	) {
		val pending = pendingImport ?: return
		val target = if (language == 0) settings.resolvedLanguage() else language
		if (!EvvEngine.available.contains(target)) return
		pendingImport = null
		val entries = if (merge) onePerWord(pending.entries) else pending.entries
		finishImport(pending.tmpPath, pending.pickedName, entries, target, name)
	}

	fun cancelPendingImport() {
		resolvingIndex = -1
		chosen.clear()
		pendingImport?.let { java.io.File(it.tmpPath).delete() }
		pendingImport = null
	}

	/** El diccionario que el motor usaría para el idioma activo. */
	fun currentDictionaryEntry(): Settings.DictionaryEntry? =
		settings.dictionaryForLanguage(settings.activeDictionaryLanguage)

	/** Activa [id] para su idioma. Puede haber uno activo por idioma. */
	fun selectDictionary(id: String) {
		val entry = settings.setActiveDictionary(id) ?: return
		dictionaryLibrary = settings.dictionaries
		dictionary = entry.name
		dictError = null
		refreshFileEntries()
		settings.dictionaryRewritten("diccionario activo")
	}

	fun renameDictionary(id: String, name: String): Boolean {
		if (!settings.renameDictionary(id, name)) return false
		dictionaryLibrary = settings.dictionaries
		currentDictionaryEntry()?.let { dictionary = it.name }
		return true
	}

	fun deleteDictionary(id: String) {
		settings.removeDictionaryEntry(id) ?: return
		dictionaryLibrary = settings.dictionaries
		dictionary = currentDictionaryEntry()?.name
			?: app.getString(R.string.dictionary_none)
		dictError = null
		refreshFileEntries()
		settings.dictionaryRewritten("diccionario borrado")
	}

	private fun finishImport(
		tmpPath: String,
		picked: String,
		entries: List<Pair<String, String>>,
		language: Int,
		suggestedName: String
	) {
		pendingImport = null
		val into = DirectBoot.dictionaries(app).apply { mkdirs() }
		val tmp = java.io.File(tmpPath)
		// La extensión manda, y el contenido cuando no dice nada:
		// un ELOQMAIN.CFD original (.CFD, .txt, sin extensión)
		// se guarda como .cfd para que siga siendo .cfd al exportar.
		val ext = when {
			picked.substringAfterLast('.', "").equals("reloqdic", ignoreCase = true) -> "reloqdic"
			picked.substringAfterLast('.', "").equals("cfd", ignoreCase = true) -> "cfd"
			Dictionaries.sniffFormat(tmp) == "cfd" -> "cfd"
			else -> "reloqdic"
		}
		val cleanName = suggestedName.trim().take(60)
			.ifEmpty { dictionaryNameFrom(picked) }
		val file = java.io.File(
			into,
			"dict-${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(8)}.$ext"
		)
		// 4.2: se escribe lo que quedó resuelto, no se mueve el
		// archivo viejo tal cual. Antes el parámetro [entries] solo
		// servía para el contador del mensaje, así que decidir qué
		// pronunciación se deja no cambiaba nada en el archivo y lo
		// que se compartía después salía con las repetidas dentro.
		val resolved = onePerWord(entries)
		val written = try {
			if (ext == "cfd") {
				// Los campos del .cfd original se leen antes de
				// borrarlo, que es donde están.
				val original = Dictionaries.cfdEntries(tmp)
				val byWord = LinkedHashMap<String, String>()
				for ((key, say) in resolved) byWord.putIfAbsent(key, say)
				Dictionaries.writeCfdResolved(file, original, byWord)
			} else {
				Dictionaries.writeEntries(file, resolved, "reloqdic")
			}
		} catch (unreadable: Exception) {
			Timber.e(unreadable, "no se puede guardar el diccionario")
			false
		}
		tmp.delete()
		if (!written) {
			file.delete()
			dictError = app.getString(R.string.dict_import_error)
			return
		}
		val entry = settings.addDictionary(cleanName, file.absolutePath, language, active = true)
		dictionaryLibrary = settings.dictionaries
		dictionary = entry.name
		fileEntries = resolved
		dictLoading = false
		rebuildListed()
		// 4.3: el archivo se ha reescrito, así que el motor tiene que
		// soltar lo suyo antes de la siguiente frase, o seguiría
		// hablando con el diccionario anterior. [addDictionary] ya
		// avisa por su cuenta; esto además deja el contador del
		// diccionario al día para que la vista previa no relea el
		// archivo entero otra vez.
		settings.dictionaryRewritten("diccionario importado")
		seenDictionaryRevision = SettingsSignal.dictionaryRevision
	}

	/**
	 * 4.2: una sola entrada por palabra y **gana la primera**, que es
	 * lo mismo que hace el motor cuando busca (Dictionaries.SubCache
	 * se queda con la primera que ve). Antes esta lista se armaba
	 * con `toMap()`, que deja la última, así que la palabra que
	 * aparecía en la pantalla no era la que se oía.
	 */
	private fun onePerWord(entries: List<Pair<String, String>>): List<Pair<String, String>> {
		val out = LinkedHashMap<String, String>()
		for ((key, say) in entries) out.putIfAbsent(key, say)
		return out.toList()
	}

	/**
	 * 4.2: cuántas palabras repetidas tiene el diccionario en uso.
	 * Solo para poder avisar antes de limpiar.
	 */
	fun duplicateCount(): Int {
		val seen = HashSet<String>()
		var repeated = 0
		for ((key, _) in fileEntries) {
			if (!seen.add(key)) repeated++
		}
		return repeated
	}

	/**
	 * 4.2: quita las palabras repetidas del diccionario en uso y
	 * **escribe el archivo**, dejando una sola pronunciación por
	 * palabra: la primera, que es la que oye el motor. Deja el
	 * aviso en [dictNotice] y no cambia nada en pantalla si el
	 * archivo no se pudo escribir, para no mentir sobre lo que
	 * quedó guardado.
	 */
	fun removeDuplicates() {
		val path = activeDictionaryPath()
		val file = path?.let { java.io.File(it) }
		if (file == null || !file.exists()) {
			dictNotice = app.getString(R.string.dict_dupes_none)
			return
		}
		val raw = currentFileEntries()
		val resolved = onePerWord(raw)
		val removed = raw.size - resolved.size
		if (removed <= 0) {
			dictNotice = app.getString(R.string.dict_dupes_none)
			return
		}
		val written = runCatching {
			if (Dictionaries.formatOf(file) == "cfd") {
				// Los campos del .cfd original se leen antes de
				// escribir encima, que es donde están.
				val original = Dictionaries.cfdEntries(file)
				val byWord = LinkedHashMap<String, String>()
				for ((key, say) in resolved) byWord.putIfAbsent(key, say)
				Dictionaries.writeCfdResolved(file, original, byWord)
			} else {
				Dictionaries.writeEntries(file, resolved, "reloqdic")
			}
		}.getOrElse {
			Timber.e(it, "no se pueden quitar las repetidas")
			false
		}
		if (!written) {
			dictNotice = app.getString(R.string.dict_dupes_error)
			return
		}
		fileEntries = resolved
		val current = currentDictionaryEntry()
		val said = app.getString(
			R.string.dictionary_entries,
			current?.name.orEmpty(), resolved.size
		)
		settings.setDictionary(file.absolutePath, current?.name)
		dictionary = said
		dictionaryLibrary = settings.dictionaries
		rebuildListed()
		// 4.3: el archivo del diccionario cambió en el disco, y eso no
		// toca ninguna clave de los ajustes, así que hay que decirlo.
		settings.dictionaryRewritten("repetidas quitadas")
		dictNotice = app.getString(R.string.dict_dupes_done, removed)
	}

	/** Un aviso con lo que se quitó, para después del diálogo. */
	var dictNotice: String? by mutableStateOf<String?>(null)
		private set

	fun clearDictNotice() {
		dictNotice = null
	}

	fun removeDictionary() {
		val active = currentDictionaryEntry() ?: return
		deleteDictionary(active.id)
	}

	/** Vacía la biblioteca entera: archivos importados y palabras
	 *  propias. La lista de sustitución se rehace en la siguiente
	 *  emisión, que recarga y encuentra nada. */
	fun clearAllDictionary() {
		val held = settings.dictionaries.size
		for (entry in settings.dictionaries) {
			runCatching { java.io.File(entry.path).delete() }
		}
		settings.dictionaries = emptyList()
		settings.setDictionary(null, null)
		settings.dictionaryWords = emptyMap()
		dictionaryLibrary = emptyList()
		dictionary = app.getString(R.string.dictionary_none)
		fileEntries = emptyList()
		wordEntries = emptyMap()
		dictLoading = false
		rebuildListed()
		// 4.3: el servicio conserva la lista cargada; sin este aviso
		// seguiría sustituciendo las palabras hasta el siguiente
		// reinicio del servicio.
		settings.dictionaryRewritten("diccionario vaciado")
		diag("dictionary", "event" to "clear_all", "files" to held.toString())
	}

	/** Si hay algo que vaciar: archivo o palabras propias. */
	fun hasDictionaryContent(): Boolean =
		fileEntries.isNotEmpty() || wordEntries.isNotEmpty()

	/** Todas las entradas: archivo más palabras propias (las propias
	 *  ganan si repiten clave), ordenadas. */
	fun allEntries(): List<Pair<String, String>> =
		(fileEntries.toMap() + wordEntries).toList().sortedBy { it.first }

	fun editWord(oldKey: String, newKey: String, newSay: String): Boolean {
		val word = cleanEntry(newKey)
		val pron = cleanEntry(newSay)
		if (word.isEmpty() || pron.isEmpty()) return false
		if (word.length > Dictionaries.MAX_KEY || pron.length > Dictionaries.MAX_SAY) return false
		if (wordEntries.containsKey(oldKey)) {
			val updated = settings.dictionaryWords.toMutableMap()
			updated.remove(oldKey)
			updated[word] = pron
			settings.dictionaryWords = updated
			wordEntries = updated
			rebuildListed()
			return true
		}
		val path = activeDictionaryPath() ?: return false
		val file = java.io.File(path)
		if (!file.exists()) return false
		// Reescribir miles de entradas en el hilo principal se
		// nota; en archivos grandes va en fondo.
		if (fileEntries.size >= 500) {
			viewModelScope.launch(Dispatchers.IO) {
				if (Dictionaries.updateEntry(file, oldKey, word, pron)) {
					val parsed = Dictionaries.entries(file)
					withContext(Dispatchers.Main) {
						fileEntries = parsed
						rebuildListed()
						// 4.3: el archivo cambió y el motor tiene que
						// enterarse; sin esto la palabra editada se
						// veía al instante pero no se oía hasta el
						// siguiente reinicio del servicio.
						settings.dictionaryRewritten("palabra editada")
					}
				}
			}
			return true
		}
		if (!Dictionaries.updateEntry(file, oldKey, word, pron)) return false
		fileEntries = currentFileEntries()
		rebuildListed()
		settings.dictionaryRewritten("palabra editada")
		return true
	}

	fun removeEntry(key: String) {
		removeEntries(setOf(key))
	}

	/** Borra varias claves de una vez con una sola reescritura del
	 *  archivo. Las propias van por ajustes y el resto por archivo. */
	fun removeEntries(keys: Set<String>) {
		if (keys.isEmpty()) return
		val own = wordEntries.keys.intersect(keys)
		if (own.isNotEmpty()) {
			val updated = settings.dictionaryWords.toMutableMap()
			for (key in own) updated.remove(key)
			settings.dictionaryWords = updated
			wordEntries = updated
		}
		val rest = keys - own
		if (rest.isNotEmpty()) {
			val path = activeDictionaryPath()
			val file = path?.let { java.io.File(it) }
			if (file != null && file.exists()) {
				val rewrite = {
					if (Dictionaries.formatOf(file) == "cfd") {
						Dictionaries.writeCfd(file, Dictionaries.cfdEntries(file).filter { it.word !in rest })
					} else {
						Dictionaries.writeEntries(
							file,
							Dictionaries.entries(file).filter { it.first !in rest },
							Dictionaries.formatOf(file)
						)
					}
				}
				if (fileEntries.size >= 500) {
					viewModelScope.launch(Dispatchers.IO) {
						rewrite()
						val parsed = Dictionaries.entries(file)
						withContext(Dispatchers.Main) {
							fileEntries = parsed
							rebuildListed()
							// 4.3: el archivo se reescribió fuera de
							// los ajustes; el motor tiene que soltarlo.
							settings.dictionaryRewritten("entradas quitadas")
						}
					}
					rebuildListed()
					return
				}
				rewrite()
				fileEntries = currentFileEntries()
				settings.dictionaryRewritten("entradas quitadas")
			}
		}
		rebuildListed()
	}

	/**
	 * Exporta todas las entradas a [uri] en el formato pedido:
	 * "cfd" (forma propia de Eloquence, UTF-16 con BOM) o
	 * "reloqdic" (cabecera y tabulado). [name] es el que se le
	 * pidió antes de abrir el selector, y es con el que se ofrece
	 * compartir después.
	 *
	 * 4.2: se guarda en memoria además de en [uri], para que al
	 * terminar salga el mismo diálogo que el de la copia de
	 * seguridad, con su botón de compartir. Corre en fondo para no
	 * congelar la pantalla con diccionarios grandes.
	 */
	fun exportDictionary(format: String, name: String) {
		if (dictBusy) return
		val entries = listedEntries
		if (entries.isEmpty()) return
		dictBusy = true
		dictError = null
		dictResult = null
		diag(
			"dictionary", "event" to "export",
			"format" to format,
			"entries" to entries.size.toString()
		)
		val cfd = if (format == "cfd") cfdExportEntries() else null
		val ext = if (format == "cfd") "cfd" else "reloqdic"
		viewModelScope.launch(Dispatchers.IO) {
			val bytes = runCatching {
				val sink = java.io.ByteArrayOutputStream()
				if (format == "cfd" && cfd != null) {
					Dictionaries.writeCfdTo(sink, cfd)
				} else {
					Dictionaries.writeReloqdicTo(sink, entries)
				}
				sink.toByteArray()
			}.getOrNull()
			val target = bytes?.let {
				Share.prepare(app, "dicts", "$name.$ext", "application/octet-stream", it)
			}
			if (target == null) Timber.e("no se pudo preparar el diccionario")
			withContext(Dispatchers.Main) {
				if (target != null) shareTarget = target
				dictBusy = false
				if (target == null) dictResult = app.getString(R.string.dict_export_error)
			}
		}
	}

	fun clearDictResult() {
		dictResult = null
	}

	/** Nombre sugerido al exportar el diccionario. */
	fun defaultDictName(): String {
		val current = currentDictionaryEntry()?.name?.substringBefore(",")?.trim().orEmpty()
		if (current.isNotEmpty()) return current.take(60)
		val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
			.format(java.util.Date())
		return "diccionario-$stamp"
	}

	var dictResult by mutableStateOf<String?>(null)
		private set

	/** Todo lo exportable como .cfd conservando los campos propios
	 *  del archivo (los originales de Eloquence traen sus flags):
	 *  archivo primero, palabras propias encima. */
	private fun cfdExportEntries(): List<Dictionaries.CfdEntry> {
		val path = activeDictionaryPath()
		val base = if (path != null) {
			val file = java.io.File(path)
			if (file.exists()) Dictionaries.cfdEntries(file) else emptyList()
		} else emptyList()
		val merged = LinkedHashMap<String, Dictionaries.CfdEntry>()
		for (e in base) merged[e.word] = e
		for ((k, s) in wordEntries) {
			val keep = merged[k]
			merged[k] = if (keep != null) keep.copy(say = s) else Dictionaries.CfdEntry(k, s)
		}
		for ((k, s) in fileEntries) merged.putIfAbsent(k, Dictionaries.CfdEntry(k, s))
		return merged.values.sortedBy { it.word }
	}

	// ---- copia de seguridad (.reloqbkp) -----------------------------

	/** Nombre sugerido para una copia nueva. */
	fun defaultBackupName(): String {
		val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
			.format(java.util.Date())
		return "copia-$stamp.${BackupStore.EXTENSION}"
	}

	/** Prepara una copia completa y abre el selector del sistema. */
	fun shareBackup(name: String) {
		if (backupBusy || dictBusy) return
		backupBusy = true
		backupResult = null
		backupNeedsRestart = false
		backupCanShare = false
		val fileName = BackupStore.fileName(name.ifBlank { defaultBackupName() })
		val diagnosticsPrefs = Diagnostics.dumpPrefs(app)
		viewModelScope.launch(Dispatchers.IO) {
			val bytes = runCatching {
				BackupStore.buildBackup(name, settings, diagnosticsPrefs, app)
			}.getOrNull()
			val target = bytes?.let {
				Share.prepare(app, "backups", fileName, "application/json", it)
			}
			withContext(Dispatchers.Main) {
				if (bytes != null && target != null) {
					lastBackupBytes = bytes
					lastBackupName = name
					backupCanShare = true
					shareTarget = target
				}
				backupBusy = false
				if (target == null) backupResult = app.getString(R.string.backup_error)
			}
		}
	}

	/** Guarda todo (ajustes, perfiles, velocidad, diccionario) en
	 *  [uri] con el nombre [name]. Corre en fondo y deja la copia
	 *  lista para compartirla. */
	fun exportBackup(uri: android.net.Uri, name: String) {
		if (backupBusy || dictBusy) return
		backupBusy = true
		backupResult = null
		backupNeedsRestart = false
		backupCanShare = false
		diag("backup", "event" to "export_begin")
		val diagnosticsPrefs = Diagnostics.dumpPrefs(app)
		viewModelScope.launch(Dispatchers.IO) {
			val bytes = runCatching {
				BackupStore.buildBackup(name, settings, diagnosticsPrefs, app)
			}.getOrNull()
			var ok = false
			if (bytes != null) {
				ok = runCatching {
					app.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@runCatching false
					true
				}.getOrDefault(false)
			}
			withContext(Dispatchers.Main) {
				if (ok && bytes != null) {
					lastBackupBytes = bytes
					lastBackupName = name
					backupCanShare = true
				}
				backupBusy = false
				backupResult = if (ok) app.getString(R.string.backup_ok, name)
				else app.getString(R.string.backup_error)
				diag(
					"backup", "event" to "export_end",
					"ok" to ok.toString(),
					"bytes" to (bytes?.size ?: 0).toString()
				)
			}
		}
	}

	/** Lee una copia de [uri] y muestra qué trae para confirmar.
	 *  Corre en fondo; importar de verdad es [confirmPendingBackup]. */
	fun importBackup(uri: android.net.Uri) {
		if (backupBusy || dictBusy) return
		backupBusy = true
		backupResult = null
		backupNeedsRestart = false
		backupCanShare = false
		pendingBackup = null
		pendingParsed = null
		diag("backup", "event" to "import_begin")
		viewModelScope.launch(Dispatchers.IO) {
			val parsed = BackupStore.parseBackup(app.contentResolver, uri)
			val info = parsed?.let {
				BackupInfo(
					it.name.ifEmpty { "backup" },
					backupInfoLines(it),
					BackupStore.issueMessages(app, it),
					it.compatible
				)
			}
			// 4.0: si no es copia pero sí diccionario, decirlo.
			val wrongKind = if (parsed == null) {
				BackupStore.detectKind(app.contentResolver, uri) == BackupStore.Kind.DICT
			} else false
			withContext(Dispatchers.Main) {
				pendingParsed = parsed
				pendingBackup = info
				backupBusy = false
				if (parsed == null) backupResult = if (wrongKind) {
					app.getString(R.string.backup_is_dict)
				} else {
					app.getString(R.string.backup_error)
				}
				diag(
					"backup", "event" to "import_parsed",
					"ok" to (parsed != null).toString(),
					"compatible" to (parsed?.compatible == true).toString(),
					"warnings" to (info?.warnings?.size ?: 0).toString()
				)
			}
		}
	}

	/** Importa la copia ya confirmada. */
	fun confirmPendingBackup() {
		val parsed = pendingParsed ?: return
		pendingParsed = null
		pendingBackup = null
		if (backupBusy || dictBusy) return
		backupBusy = true
		viewModelScope.launch(Dispatchers.IO) {
			val done = BackupStore.applyParsed(parsed, settings, app)
			withContext(Dispatchers.Main) {
				backupNeedsRestart = done.localeChanged
				// 4.3: el motor se reinicia antes de redibujar. La
				// copia puede traer cualquier ajuste, incluido el
				// diccionario, y lo que se oyera hasta el siguiente
				// cambio de ajuste era la voz anterior.
				settings.engineReload("copia importada")
				reloadAll()
				backupBusy = false
				backupResult = done.message
				diag(
					"backup", "event" to "import_applied",
					"locale_changed" to done.localeChanged.toString()
				)
			}
		}
	}

	private fun backupInfoLines(parsed: BackupStore.ParsedBackup): List<String> {
		val out = ArrayList<String>()
		val prefs = parsed.prefs
		(prefs["voice_preset"] as? Int)?.let { v ->
			out.add(app.getString(R.string.labelled_value,
				app.getString(R.string.voice_label),
				Eci.PRESET_NAMES.getOrElse(v) { "?" }))
		}
		(prefs["default_language"] as? Int)?.let { lang ->
			val name = if (lang == 0) app.getString(R.string.language_system)
			else EngineLocale.displayName(app, lang)
			out.add(app.getString(R.string.labelled_value,
				app.getString(R.string.language_label), name))
		}
		(prefs["volume"] as? Int)?.let { v ->
			out.add(app.getString(R.string.labelled_value,
				app.getString(R.string.volume_label), "$v%"))
		}
		(prefs["sample_rate_hz"] as? Int)?.let { hz ->
			out.add(app.getString(R.string.labelled_value,
				app.getString(R.string.sample_rate_label),
				app.getString(R.string.hertz, hz)))
		}
		val fx = ArrayList<String>()
		if (prefs["eq_enabled"] == true) fx.add(app.getString(R.string.eq_label))
		if (prefs["reverb_enabled"] == true) fx.add(app.getString(R.string.reverb_label))
		if (prefs["echo_enabled"] == true) fx.add(app.getString(R.string.echo_label))
		if (prefs["compressor"] == true) fx.add(app.getString(R.string.compressor_label))
		if (prefs["gate"] == true) fx.add(app.getString(R.string.gate_label))
		if (prefs["chorus"] == true) fx.add(app.getString(R.string.chorus_label))
		if (prefs["phaser"] == true) fx.add(app.getString(R.string.phaser_label))
		if ((prefs["vibrato"] as? Int ?: 0) > 0) fx.add(app.getString(R.string.vibrato_label))
		if ((prefs["ringmod"] as? Int ?: 0) > 0) fx.add(app.getString(R.string.ringmod_label))
		if ((prefs["fuzz"] as? Int ?: 0) > 0) fx.add(app.getString(R.string.fuzz_label))
		if ((prefs["wah"] as? Int ?: 0) > 0) fx.add(app.getString(R.string.wah_label))
		if ((prefs["maximizer"] as? Int ?: 0) > 0) fx.add(app.getString(R.string.maximizer_label))
		if (prefs["telephone"] == true) fx.add(app.getString(R.string.telephone_label))
		if (prefs["normalizer"] == true) fx.add(app.getString(R.string.normalizer_label))
		if (prefs["highpass"] == true) fx.add(app.getString(R.string.highpass_label))
		if (prefs["drive"] == true) fx.add(app.getString(R.string.drive_label))
		if ((prefs["exciter"] as? Int ?: 0) > 0) fx.add(app.getString(R.string.exciter_label))
		if (prefs["warmth"] == true) fx.add(app.getString(R.string.warmth_label))
		if (prefs["deesser"] == true) fx.add(app.getString(R.string.deesser_label))
		if ((prefs["tremolo"] as? Int ?: 0) > 0) fx.add(app.getString(R.string.tremolo_label))
		if ((prefs["boost"] as? Int ?: 100) != 100) fx.add(app.getString(R.string.boost_label))
		out.add(app.getString(R.string.labelled_value,
			app.getString(R.string.category_fx),
			if (fx.isEmpty()) app.getString(R.string.switch_off) else fx.joinToString(", ")))
		(prefs["fx_profiles"] as? String)?.let { raw ->
			val count = raw.lines().count { it.contains('|') }
			out.add(app.getString(R.string.labelled_value,
				app.getString(R.string.backup_info_profiles), count.toString()))
		}
		(prefs["voice_profiles"] as? String)?.let { raw ->
			val count = "\"baseVoice\"".toRegex().findAll(raw).count()
			out.add(app.getString(R.string.labelled_value,
				app.getString(R.string.backup_info_voice_profiles), count.toString()))
		}
		if (parsed.libraryPresent) {
			// 5.4.1: la copia trae la biblioteca entera. El detalle de
			// cada archivo se ve al importar; aquí basta con el total.
			out.add(app.getString(R.string.labelled_value,
				app.getString(R.string.backup_info_dictionaries),
				parsed.dictionaries.size.toString()))
		} else {
			val bytes = parsed.dictBytes
			if (bytes != null && !parsed.dictExt.isNullOrEmpty()) {
				val count = runCatching {
					val tmp = java.io.File.createTempFile("bkp", ".${parsed.dictExt}", app.cacheDir)
					try {
						tmp.writeBytes(bytes)
						Dictionaries.entries(tmp).size
					} finally {
						tmp.delete()
					}
				}.getOrDefault(-1)
				val label = if (count >= 0) {
					app.getString(R.string.dictionary_entries, parsed.dictName.ifEmpty { "dict" }, count)
				} else {
					parsed.dictName.ifEmpty { "dict" }
				}
				out.add(app.getString(R.string.labelled_value,
					app.getString(R.string.category_dictionary), label))
			} else {
				out.add(app.getString(R.string.labelled_value,
					app.getString(R.string.category_dictionary),
					app.getString(R.string.dictionary_none)))
			}
		}
		(prefs["app_locale"] as? String)?.let { tag ->
			val name = if (tag.isEmpty()) app.getString(R.string.app_language_system)
			else AppLocale.displayName(tag)
			out.add(app.getString(R.string.labelled_value,
				app.getString(R.string.app_language_label), name))
		}
		return out
	}

	/**
	 * Relee todo de los ajustes a la pantalla. Corre en el hilo
	 * principal.
	 *
	 * 4.3: antes solo se llamaba desde dentro de la propia pantalla
	 * (importar una copia, devolver un apartado a sus valores), y por
	 * eso una importación hecha desde **otra** actividad -- el
	 * "Abrir con" del gestor de archivos o del correo, que importa en
	 * [ImportDictionaryActivity] -- se oía pero no se veía: la voz
	 * cambiaba porque el motor relee los ajustes en cada emisión, y la
	 * pantalla no, porque su estado es una copia en memoria. Lo que
	 * había que reiniciar para que volviera a verse era la actividad
	 * entera.
	 *
	 * [withDictionary] decide si además se relee el archivo del
	 * diccionario, que es lo caro de esta lista: si lo que cambió fue
	 * otra cosa, releerlo sería tirar el trabajo de segundo plano
	 * para nada.
	 */
	private fun reloadAll(withDictionary: Boolean = true) {
		voice = settings.voice
		abbreviations = settings.abbreviations
		ignoreVoiceTags = settings.ignoreVoiceTags
		sampleRateHz = settings.sampleRateHz
		pauses = settings.pauses
		pauseDurationMs = settings.pauseDurationMs
		autoDetectLanguage = settings.autoDetectLanguage
		autoVariantEn = settings.autoVariantEn
		autoVariantEs = settings.autoVariantEs
		autoVariantFr = settings.autoVariantFr
		useSystemSpeed = settings.useSystemSpeed
		useSystemVolume = settings.useSystemVolume
		volume = settings.volume
		processEmojis = settings.processEmojis
		readUnicodeSymbols = settings.readUnicodeSymbols
		punctEnabled = settings.punctEnabled
		punctLevel = settings.punctLevel
		skipParentheses = settings.skipParentheses
		numberForceEnabled = settings.numberForceEnabled
		numberGroup = settings.numberGroup
		mixEnabled = settings.mixEnabled
		mixPartner = settings.mixPartner
		mixInterval = settings.mixInterval
		bodyEnabled = settings.bodyEnabled
		eqEnabled = settings.eqEnabled
		eqBands = settings.eqBands().toList()
		reverbEnabled = settings.reverbEnabled
		reverbLevel = settings.reverbLevel
		bassDb = settings.bassDb
		presenceDb = settings.presenceDb
		echoEnabled = settings.echoEnabled
		echoLevel = settings.echoLevel
		echoTimeMs = settings.echoTimeMs
		compEnabled = settings.compressorEnabled
		boostPercent = settings.boostPercent
		gateEnabled = settings.gateEnabled
		tremoloLevel = settings.tremoloLevel
		tremoloRate = settings.tremoloRate
		chorusEnabled = settings.chorusEnabled
		phaserEnabled = settings.phaserEnabled
		vibratoLevel = settings.vibratoLevel
		ringmodLevel = settings.ringmodLevel
		fuzzLevel = settings.fuzzLevel
		wahLevel = settings.wahLevel
		maximizerLevel = settings.maximizerLevel
		tapeMix = settings.tapeMix
		subBassMix = settings.subBassMix
		reverbSize = settings.reverbSize
		crusherBits = settings.crusherBits
		deesserDepth = settings.deesserDepth
		telephoneEnabled = settings.telephoneEnabled
		normalizerEnabled = settings.normalizerEnabled
		highpassEnabled = settings.highpassEnabled
		driveEnabled = settings.driveEnabled
		crusherEnabled = settings.crusherEnabled
		flangerEnabled = settings.flangerEnabled
		exciterLevel = settings.exciterLevel
		warmthEnabled = settings.warmthEnabled
		deesserEnabled = settings.deesserEnabled
		hdEnhance = settings.hdEnhance
		appLocale = settings.appLocale
		fxProfile = settings.fxProfile
		fxProfiles = settings.fxProfiles
		voiceProfile = settings.voiceProfile
		voiceProfiles = settings.voiceProfiles
		dictionaryLibrary = settings.dictionaries
		dictionary = settings.dictionaryForLanguage(settings.activeDictionaryLanguage)?.name
			?: app.getString(R.string.dictionary_none)
		wordEntries = settings.dictionaryWords
		shape.clear()
		shape.putAll(settings.shape(voice))
		if (resolveLanguage() != previewLang) switchPreview(resolveLanguage())
		if (withDictionary) {
			dictLoading = true
			refreshFileEntries()
		}
		seenEpoch = SettingsSignal.epoch
	}

	/**
	 * 4.3: relee si algo cambio fuera de esta pantalla.
	 *
	 * Es lo que hace que una importacion hecha en otra actividad
	 * (el "Abrir con" del gestor de archivos o del correo) se vea al
	 * volver aqui, y lo que hace que una copia restaurada se vea sin
	 * cerrar y abrir la aplicacion. Si no cambio nada, no hace nada:
	 * comparar un contador es mucho mas barato que releer sesenta
	 * valores y el diccionario entero.
	 */
	fun syncFromDisk() {
		if (SettingsSignal.epoch == seenEpoch) return
		val wantDictionary = SettingsSignal.dictionaryRevision != seenDictionaryRevision
		seenEpoch = SettingsSignal.epoch
		seenDictionaryRevision = SettingsSignal.dictionaryRevision
		reloadAll(wantDictionary)
	}

	private fun activeDictionaryPath(): String? =
		settings.dictionaryForLanguage(settings.activeDictionaryLanguage)?.path

	private fun currentFileEntries(): List<Pair<String, String>> {
		val path = activeDictionaryPath() ?: return emptyList()
		val file = java.io.File(path)
		if (!file.exists()) return emptyList()
		return Dictionaries.entries(file)
	}

	/** Palabras propias del editor, como palabra a pronunciación. */
	var wordEntries: Map<String, String> by mutableStateOf(settings.dictionaryWords)
		private set

	/** Limpia una entrada del editor: saltos de línea y tabuladores
	 *  a espacios (romperían el formato del archivo), sin tocar lo
	 *  demás. Vale para palabras sueltas y oraciones enteras. */
	private fun cleanEntry(raw: String): String =
		raw.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim()

	fun addWord(key: String, say: String): Boolean {
		val word = cleanEntry(key)
		val pron = cleanEntry(say)
		if (word.isEmpty() || pron.isEmpty()) return false
		if (word.length > Dictionaries.MAX_KEY || pron.length > Dictionaries.MAX_SAY) return false
		val updated = settings.dictionaryWords.toMutableMap()
		updated[word] = pron
		settings.dictionaryWords = updated
		wordEntries = updated
		rebuildListed()
		return true
	}

	fun removeWord(key: String) {
		val updated = settings.dictionaryWords.toMutableMap()
		updated.remove(key)
		settings.dictionaryWords = updated
		wordEntries = updated
		rebuildListed()
	}

	private fun nameOf(uri: android.net.Uri): String? = runCatching {
		app.contentResolver.query(uri, null, null, null, null)?.use { row ->
			val at = row.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
			if (at >= 0 && row.moveToFirst()) row.getString(at) else null
		}
	}.getOrNull()

	// ---- velocidad y volumen ------------------------------------------

	/** Si la velocidad la manda solo el sistema. 4.1: con estado
	 *  propio para que la pantalla se redibuje al tocar "Sistema". */
	var useSystemSpeed by mutableStateOf(settings.useSystemSpeed)
		private set

	/** Si el volumen lo manda el sistema (multimedia). 4.1: con
	 *  estado propio, igual que la velocidad. */
	var useSystemVolume by mutableStateOf(settings.useSystemVolume)
		private set

	/** La velocidad actual como % de la tasa normal (100 = normal). */
	fun speedPercent(): Int {
		val normal = SpeechRate.timesFor(Eci.DEFAULT_SPEED)
		if (normal <= 0.0) return 100
		return Math.round(SpeechRate.timesFor(settings.speed) * 100.0 / normal).toInt().coerceIn(0, 500)
	}

	/** Fija la velocidad en [percent] % de la tasa normal, de 0 a 500. */
	fun setSpeedPercent(percent: Int) {
		settings.useSystemSpeed = false
		useSystemSpeed = false
		settings.speed = SpeechRate.speedForPercent(Eci.DEFAULT_SPEED, percent.coerceIn(0, 500))
		shape[Eci.VOICE_SPEED] = settings.speed
		markVoiceManual()
		diag(
			"settings", "event" to "speed",
			"percent" to percent.coerceIn(0, 500).toString(),
			"engine" to settings.speed.toString(),
			"system" to "false"
		)
	}

	/** La velocidad vuelve a manos del sistema. */
	fun useSystemSpeed() {
		settings.useSystemSpeed = true
		useSystemSpeed = true
		markVoiceManual()
		diag("settings", "event" to "speed", "system" to "true")
	}

	/** Olvida el valor elegido de un parámetro: vuelve al del preset
	 *  (tono del sistema). */
	fun clearParam(param: Int) {
		settings.clearShapeValue(voice, param)
		shape.remove(param)
		if (param == Eci.VOICE_SPEED) useSystemSpeed = true
		markVoiceManual()
		diag(
			"settings", "event" to "param_cleared",
			"voice" to voice.toString(),
			"param" to VoicePlan.nameOf(param)
		)
	}

	fun chooseVolume(value: Int) {
		volume = value.coerceIn(0, 500)
		settings.useSystemVolume = false
		useSystemVolume = false
		settings.volume = volume
		markVoiceManual()
		diag(
			"settings", "event" to "volume",
			"percent" to volume.toString(),
			"system" to "false"
		)
	}

	/** El volumen vuelve a manos del sistema (multimedia). */
	fun useSystemVolume() {
		settings.useSystemVolume = true
		useSystemVolume = true
		volume = 100
		markVoiceManual()
		diag("settings", "event" to "volume", "system" to "true")
	}

	fun choosePauses(mode: Int) {
		pauses = mode
		settings.pauses = mode
		diag("settings", "event" to "pauses", "mode" to mode.toString())
	}

	fun choosePauseDuration(ms: Int) {
		pauseDurationMs = ms
		settings.pauseDurationMs = ms
		diag("settings", "event" to "pause_ms", "ms" to ms.toString())
	}

	fun chooseSampleRate(hz: Int) {
		sampleRateHz = hz
		settings.sampleRateHz = hz
		markVoiceManual()
	}

	// ---- efectos de audio ------------------------------------------------

	fun chooseEqEnabled(on: Boolean) {
		eqEnabled = on
		settings.eqEnabled = on
		if (!on) markFxManual()
	}

	fun chooseEqBand(band: Int, db: Int) {
		if (band !in 0 until 10) return
		val settled = db.coerceIn(-12, 12)
		val updated = eqBands.toMutableList()
		if (updated[band] == settled) return
		updated[band] = settled
		eqBands = updated
		settings.setEqBand(band, settled)
		markFxManual()
	}

	fun chooseReverbEnabled(on: Boolean) {
		reverbEnabled = on
		settings.reverbEnabled = on
		if (!on) markFxManual()
	}

	fun chooseReverbLevel(level: Int) {
		reverbLevel = level.coerceIn(0, 100)
		settings.reverbLevel = reverbLevel
		markFxManual()
	}

	fun chooseBass(db: Int) {
		bassDb = db.coerceIn(-12, 12)
		settings.bassDb = bassDb
		markFxManual()
	}

	fun choosePresence(db: Int) {
		presenceDb = db.coerceIn(-12, 12)
		settings.presenceDb = presenceDb
		markFxManual()
	}

	fun chooseEchoEnabled(on: Boolean) {
		echoEnabled = on
		settings.echoEnabled = on
		if (!on) markFxManual()
	}

	fun chooseEchoLevel(level: Int) {
		echoLevel = level.coerceIn(0, 100)
		settings.echoLevel = echoLevel
		markFxManual()
	}

	fun chooseEchoTime(ms: Int) {
		echoTimeMs = ms.coerceIn(80, 600)
		settings.echoTimeMs = echoTimeMs
		markFxManual()
	}

	fun chooseCompressor(on: Boolean) {
		compEnabled = on
		settings.compressorEnabled = on
		if (!on) markFxManual()
	}

	fun chooseBoost(percent: Int) {
		boostPercent = percent.coerceIn(0, 200)
		settings.boostPercent = boostPercent
		markFxManual()
	}

	fun chooseGate(on: Boolean) {
		gateEnabled = on
		settings.gateEnabled = on
		if (!on) markFxManual()
	}

	fun chooseTremolo(level: Int) {
		tremoloLevel = level.coerceIn(0, 100)
		settings.tremoloLevel = tremoloLevel
		markFxManual()
	}

	fun chooseTremoloRate(tenths: Int) {
		tremoloRate = tenths.coerceIn(20, 120)
		settings.tremoloRate = tremoloRate
		markFxManual()
	}

	fun chooseChorus(on: Boolean) {
		chorusEnabled = on
		settings.chorusEnabled = on
		if (!on) markFxManual()
	}

	fun choosePhaser(on: Boolean) {
		phaserEnabled = on
		settings.phaserEnabled = on
		if (!on) markFxManual()
	}

	fun chooseVibrato(level: Int) {
		vibratoLevel = level.coerceIn(0, 100)
		settings.vibratoLevel = vibratoLevel
		markFxManual()
	}

	fun chooseRingmod(level: Int) {
		ringmodLevel = level.coerceIn(0, 100)
		settings.ringmodLevel = ringmodLevel
		markFxManual()
	}

	fun chooseFuzz(level: Int) {
		fuzzLevel = level.coerceIn(0, 100)
		settings.fuzzLevel = fuzzLevel
		markFxManual()
	}

	fun chooseWah(level: Int) {
		wahLevel = level.coerceIn(0, 100)
		settings.wahLevel = wahLevel
		markFxManual()
	}

	fun chooseMaximizer(level: Int) {
		maximizerLevel = level.coerceIn(0, 100)
		settings.maximizerLevel = maximizerLevel
		markFxManual()
	}

	fun chooseTape(level: Int) {
		tapeMix = level.coerceIn(0, 100)
		settings.tapeMix = tapeMix
		markFxManual()
	}

	fun chooseSubBass(level: Int) {
		subBassMix = level.coerceIn(0, 100)
		settings.subBassMix = subBassMix
		markFxManual()
	}

	fun chooseReverbSize(level: Int) {
		reverbSize = level.coerceIn(0, 100)
		settings.reverbSize = reverbSize
		markFxManual()
	}

	fun chooseCrusherBits(bits: Int) {
		crusherBits = bits.coerceIn(2, 16)
		settings.crusherBits = crusherBits
		markFxManual()
	}

	fun chooseDeesserDepth(level: Int) {
		deesserDepth = level.coerceIn(0, 100)
		settings.deesserDepth = deesserDepth
		markFxManual()
	}

	fun chooseAutoDetectLanguage(on: Boolean) {
		autoDetectLanguage = on
		settings.autoDetectLanguage = on
	}

	/** Elige la variante que suena al detectar [tag] ("eng",
	 *  "spa", "fra"): [engine] es uno de los motores de ese
	 *  idioma. Coste cero si no cambia. */
	fun chooseAutoVariant(tag: String, engine: Int) {
		val loc = Eci.localeOf(engine) ?: return
		if (loc.first != tag || !EvvEngine.available.contains(engine)) return
		when (tag) {
			"eng" -> {
				autoVariantEn = engine
				settings.autoVariantEn = engine
			}
			"spa" -> {
				autoVariantEs = engine
				settings.autoVariantEs = engine
			}
			"fra" -> {
				autoVariantFr = engine
				settings.autoVariantFr = engine
			}
		}
	}

	fun chooseTelephone(on: Boolean) {
		telephoneEnabled = on
		settings.telephoneEnabled = on
		if (!on) markFxManual()
	}

	fun chooseNormalizer(on: Boolean) {
		normalizerEnabled = on
		settings.normalizerEnabled = on
		if (!on) markFxManual()
	}

	fun chooseHighpass(on: Boolean) {
		highpassEnabled = on
		settings.highpassEnabled = on
		if (!on) markFxManual()
	}

	fun chooseDrive(on: Boolean) {
		driveEnabled = on
		settings.driveEnabled = on
		if (!on) markFxManual()
	}

	fun chooseCrusher(on: Boolean) {
		crusherEnabled = on
		settings.crusherEnabled = on
		if (!on) markFxManual()
	}

	fun chooseFlanger(on: Boolean) {
		flangerEnabled = on
		settings.flangerEnabled = on
		if (!on) markFxManual()
	}

	fun chooseExciter(level: Int) {
		exciterLevel = level.coerceIn(0, 100)
		settings.exciterLevel = exciterLevel
		markFxManual()
	}

	fun chooseWarmth(on: Boolean) {
		warmthEnabled = on
		settings.warmthEnabled = on
		if (!on) markFxManual()
	}

	fun chooseDeesser(on: Boolean) {
		deesserEnabled = on
		settings.deesserEnabled = on
		// 4.3: encenderlo con la profundidad en cero no hacia nada y
		// el interruptor pareceria roto. Al encenderlo con cero se
		// pone a 100; si el usuario ya habia elegido otro nivel, ese
		// se respeta.
		if (on && settings.deesserDepth == 0) {
			deesserDepth = 100
			settings.deesserDepth = 100
		}
		if (!on) markFxManual()
	}

	fun chooseHdEnhance(on: Boolean) {
		hdEnhance = on
		settings.hdEnhance = on
		markVoiceManual()
	}

	fun chooseAppLocale(tag: String) {
		appLocale = tag
		settings.appLocale = tag
	}

	fun chooseProcessEmojis(on: Boolean) {
		processEmojis = on
		settings.processEmojis = on
	}

	fun chooseReadUnicodeSymbols(on: Boolean) {
		readUnicodeSymbols = on
		settings.readUnicodeSymbols = on
	}

	fun choosePunctEnabled(on: Boolean) {
		punctEnabled = on
		settings.punctEnabled = on
	}

	fun choosePunctLevel(level: Int) {
		punctLevel = level.coerceIn(0, 2)
		settings.punctLevel = punctLevel
	}

	fun chooseSkipParentheses(on: Boolean) {
		skipParentheses = on
		settings.skipParentheses = on
	}

	fun chooseNumberForce(on: Boolean) {
		numberForceEnabled = on
		settings.numberForceEnabled = on
	}

	fun chooseNumberGroup(group: Int) {
		numberGroup = if (group == Numbers.CORRIDO) Numbers.CORRIDO
		else group.coerceIn(Numbers.MIN_GROUP, Numbers.MAX_GROUP)
		settings.numberGroup = numberGroup
	}

	fun chooseMixEnabled(on: Boolean) {
		mixEnabled = on
		settings.mixEnabled = on
	}

	fun chooseMixPartner(which: Int) {
		mixPartner = which.coerceIn(0, Eci.PRESET_NAMES.lastIndex)
		settings.mixPartner = mixPartner
	}

	fun chooseMixInterval(words: Int) {
		mixInterval = words.coerceIn(Settings.MIN_MIX_WORDS, Settings.MAX_MIX_WORDS)
		settings.mixInterval = mixInterval
	}

	fun chooseBody(on: Boolean) {
		bodyEnabled = on
		settings.bodyEnabled = on
		markVoiceManual()
	}

	/** La forma para el motor: la mezcla es por turnos de
	 *  palabras, así que cada turno lleva su propia forma sin
	 *  interpolar nada. */
	fun effectiveShape(): Map<Int, Int> = settings.shape(voice)

	private fun markFxManual() {
		if (fxProfile != null) {
			fxProfile = null
			settings.fxProfile = null
		}
	}

	/** La cadena de efectos tal como sonará, o nada si todo
	 *  está apagado (el realce HD por tasa cuenta como efecto). */
	fun fxChain(rateHz: Int): AudioFx.Chain? {
		val bands = if (eqEnabled) IntArray(10) { eqBands.getOrElse(it) { 0 } } else null
		val reverb = if (reverbEnabled) reverbLevel else 0
		val bass = if (eqEnabled) bassDb else 0
		val presence = if (eqEnabled) presenceDb else 0
		val echo = if (echoEnabled) echoLevel else 0
		return AudioFx.Chain(
			rateHz = rateHz,
			eqDb = bands,
			reverbMix = reverb,
			bassDb = bass,
			presenceDb = presence,
			echoMix = echo,
			compress = compEnabled,
			boostPercent = boostPercent,
			gate = gateEnabled,
			tremoloMix = tremoloLevel,
			chorus = chorusEnabled,
			crusher = crusherEnabled,
			flanger = flangerEnabled,
			exciterMix = exciterLevel,
			warmth = warmthEnabled,
			hdEnhance = hdEnhance,
			deesser = deesserEnabled,
			body = bodyEnabled,
			echoDelayMs = echoTimeMs,
			tremoloRateHz = tremoloRate / 10.0,
			phaser = phaserEnabled,
			vibratoMix = vibratoLevel,
			ringmodMix = ringmodLevel,
			fuzzMix = fuzzLevel,
			wahMix = wahLevel,
			maximizerMix = maximizerLevel,
			tapeMix = tapeMix,
			subBassMix = subBassMix,
			reverbSize = reverbSize,
			crusherBits = crusherBits,
			deesserDepth = deesserDepth,
			telephone = telephoneEnabled,
			normalizer = normalizerEnabled,
			highpass = highpassEnabled,
			drive = driveEnabled
		).takeUnless { it.idle }
	}

	/** Aplica un perfil (propio o de fábrica): todo de una vez.
	 *  Los de fábrica encienden lo que usan. */
	fun applyFxProfile(name: String) {
		val patch = fxProfiles[name] ?: BUILTIN_FX[name] ?: return
		settings.applyFx(patch)
		settings.eqEnabled = true
		settings.reverbEnabled = patch.reverb > 0
		settings.echoEnabled = patch.echo > 0
		settings.fxProfile = name
		eqBands = settings.eqBands().toList()
		reverbLevel = settings.reverbLevel
		bassDb = settings.bassDb
		presenceDb = settings.presenceDb
		echoLevel = settings.echoLevel
		echoTimeMs = settings.echoTimeMs
		eqEnabled = settings.eqEnabled
		reverbEnabled = settings.reverbEnabled
		echoEnabled = settings.echoEnabled
		compEnabled = settings.compressorEnabled
		boostPercent = settings.boostPercent
		gateEnabled = settings.gateEnabled
		tremoloLevel = settings.tremoloLevel
		tremoloRate = settings.tremoloRate
		chorusEnabled = settings.chorusEnabled
		phaserEnabled = settings.phaserEnabled
		vibratoLevel = settings.vibratoLevel
		ringmodLevel = settings.ringmodLevel
		fuzzLevel = settings.fuzzLevel
		wahLevel = settings.wahLevel
		maximizerLevel = settings.maximizerLevel
		tapeMix = settings.tapeMix
		subBassMix = settings.subBassMix
		reverbSize = settings.reverbSize
		crusherBits = settings.crusherBits
		deesserDepth = settings.deesserDepth
		telephoneEnabled = settings.telephoneEnabled
		normalizerEnabled = settings.normalizerEnabled
		highpassEnabled = settings.highpassEnabled
		driveEnabled = settings.driveEnabled
		crusherEnabled = settings.crusherEnabled
		flangerEnabled = settings.flangerEnabled
		exciterLevel = settings.exciterLevel
		warmthEnabled = settings.warmthEnabled
		deesserEnabled = settings.deesserEnabled
		fxProfile = settings.fxProfile
		// 4.3: un perfil de efectos cambia media docena de claves, y
		// el motor se entera por [SettingsSignal] al vuelo. Esto es
		// solo para el caso de que el motor estuviera hablando: así
		// el reinicio espera a que termine la frase en vez de cortarla.
		settings.engineReload("perfil de efectos aplicado")
		diag("profile", "event" to "fx_applied", "builtin" to BUILTIN_FX.containsKey(name).toString())
	}

	/** Guarda lo actual como perfil propio. Contesta si se guardó. */
	fun saveFxProfile(name: String): Boolean {
		val clean = name.trim()
		if (clean.isEmpty() || BUILTIN_FX.containsKey(clean)) return false
		val updated = fxProfiles.toMutableMap()
		updated[clean] = Settings.FxPatch(
			bands = IntArray(10) { eqBands.getOrElse(it) { 0 } },
			reverb = reverbLevel,
			bass = bassDb,
			presence = presenceDb,
			echo = echoLevel,
			comp = compEnabled,
			boost = boostPercent,
			gate = gateEnabled,
			tremolo = tremoloLevel,
			chorus = chorusEnabled,
			crusher = crusherEnabled,
			flanger = flangerEnabled,
			exciter = exciterLevel,
			warmth = warmthEnabled,
			deesser = deesserEnabled,
			echoTime = echoTimeMs,
			tremoloRate = tremoloRate,
			phaser = phaserEnabled,
			vibrato = vibratoLevel,
			ringmod = ringmodLevel,
			fuzz = fuzzLevel,
			wah = wahLevel,
			maximizer = maximizerLevel,
			telephone = telephoneEnabled,
			normalizer = normalizerEnabled,
			highpass = highpassEnabled,
			drive = driveEnabled
		)
		settings.fxProfiles = updated
		settings.fxProfile = clean
		fxProfiles = updated
		fxProfile = clean
		return true
	}

	/** Borra un perfil propio. Contesta si existía. */
	fun deleteFxProfile(name: String): Boolean {
		val updated = fxProfiles.toMutableMap()
		if (updated.remove(name) == null) return false
		settings.fxProfiles = updated
		if (fxProfile == name) {
			settings.fxProfile = null
			fxProfile = null
		}
		fxProfiles = updated
		return true
	}

	/** Renombra un perfil propio. Contesta si se guardó: el nombre
	 *  debe ser nuevo y no chocar con uno de fábrica. */
	fun renameFxProfile(old: String, new: String): Boolean {
		val clean = new.trim()
		if (clean.isEmpty() || BUILTIN_FX.containsKey(clean)) return false
		val updated = fxProfiles.toMutableMap()
		val patch = updated.remove(old) ?: return false
		if (updated.containsKey(clean)) return false
		updated[clean] = patch
		settings.fxProfiles = updated
		if (fxProfile == old) {
			settings.fxProfile = clean
			fxProfile = clean
		}
		fxProfiles = updated
		return true
	}

	/** Un perfil como JSON para compartirlo fuera de la app.
	 *  4.1: usa kotlinx-serialization; el formato de tubo interno
	 *  no cambia y las copias lo siguen llevando igual. */
	fun exportProfileJson(name: String): String? {
		val patch = fxProfiles[name] ?: BUILTIN_FX[name] ?: return null
		return runCatching {
			PROFILE_JSON.encodeToString(Settings.FxPatch.serializer(), patch)
		}.getOrNull()?.let { "{\"name\":${PROFILE_JSON.encodeToString(kotlinx.serialization.serializer<String>(), name)},\"patch\":$it}" }
	}

	/** Lee un JSON de [exportProfileJson] sin guardarlo: nombre y
	 *  parche saneados, o nada si no era un perfil válido. */
	fun parseFxProfileJson(json: String): Pair<String, Settings.FxPatch>? {
		val parsed = runCatching {
			PROFILE_JSON.parseToJsonElement(json).let { el ->
				val obj = el as? kotlinx.serialization.json.JsonObject ?: return null
				val name = obj["name"]?.let {
					PROFILE_JSON.decodeFromJsonElement(kotlinx.serialization.serializer<String>(), it)
				}?.trim()?.take(60).orEmpty()
				val patch = obj["patch"]?.let {
					PROFILE_JSON.decodeFromJsonElement(Settings.FxPatch.serializer(), it)
				} ?: return null
				name to patch
			}
		}.getOrNull() ?: return null
		var (base, patch) = parsed
		if (base.isEmpty()) base = app.getString(R.string.fx_profile_label)
		// Sanea rangos por si el JSON viene de otra versión.
		val clean = patch.copy(
			bands = IntArray(10) { patch.bands.getOrElse(it) { 0 }.coerceIn(-12, 12) },
			reverb = patch.reverb.coerceIn(0, 100),
			bass = patch.bass.coerceIn(-12, 12),
			presence = patch.presence.coerceIn(-12, 12),
			echo = patch.echo.coerceIn(0, 100),
			boost = patch.boost.coerceIn(0, 200),
			tremolo = patch.tremolo.coerceIn(0, 100),
			exciter = patch.exciter.coerceIn(0, 100),
			echoTime = patch.echoTime.coerceIn(80, 600),
			tremoloRate = patch.tremoloRate.coerceIn(20, 120),
			vibrato = patch.vibrato.coerceIn(0, 100),
			ringmod = patch.ringmod.coerceIn(0, 100),
			fuzz = patch.fuzz.coerceIn(0, 100),
			wah = patch.wah.coerceIn(0, 100),
			maximizer = patch.maximizer.coerceIn(0, 100)
		)
		return base to clean
	}

	/** Guarda un parche ya revisado como perfil propio y lo aplica.
	 *  Contesta el nombre final (con número si chocaba) o nada. */
	fun adoptFxProfile(base: String, patch: Settings.FxPatch): String? {
		val updated = fxProfiles.toMutableMap()
		var name = base.trim().take(60).ifEmpty { return null }
		var n = 2
		while (updated.containsKey(name) || BUILTIN_FX.containsKey(name)) {
			name = "$base $n"
			n++
			if (n > 99) return null
		}
		updated[name] = patch
		settings.fxProfiles = updated
		fxProfiles = updated
		applyFxProfile(name)
		return name
	}

	/** Lee un JSON de [exportProfileJson] y lo guarda como perfil
	 *  propio (con otro nombre si el original existe). Contesta el
	 *  nombre guardado o nada si no era un perfil válido. */
	fun importProfileJson(json: String): String? {
		val (base, patch) = parseFxProfileJson(json) ?: return null
		return adoptFxProfile(base, patch)
	}

	/** Si hay un perfil de voz en vigor y algo de voz cambia,
	 *  vuelve a manual (igual que los efectos). */
	private fun markVoiceManual() {
		if (voiceProfile != null) {
			voiceProfile = null
			settings.voiceProfile = null
		}
	}

	/** Guarda la voz actual como perfil propio. Contesta si se guardó. */
	fun saveVoiceProfile(name: String): Boolean {
		val clean = name.trim()
		if (clean.isEmpty()) return false
		val updated = voiceProfiles.toMutableMap()
		val shapeNow = settings.shape(voice).toMap()
		updated[clean] = Settings.VoicePatch(
			baseVoice = voice,
			params = shapeNow.filterKeys { it != Eci.VOICE_SPEED },
			speed = settings.speed,
			systemSpeed = settings.useSystemSpeed,
			volume = settings.volume,
			systemVolume = settings.useSystemVolume,
			sampleRateHz = sampleRateHz,
			body = bodyEnabled,
			hdEnhance = hdEnhance
		)
		settings.voiceProfiles = updated
		settings.voiceProfile = clean
		voiceProfiles = updated
		voiceProfile = clean
		return true
	}

	/** Aplica un perfil de voz: base, forma, velocidad, volumen,
	 *  tasa, cuerpo y realce, todo de una vez. El servicio lo lee
	 *  en la siguiente emisión sin reiniciar nada. */
	fun applyVoiceProfile(name: String) {
		// 4.3: los de fábrica se leen igual que los propios, para que
		// la lista pueda ofrecerlos sin copiarlos a los ajustes del
		// usuario ni que editarlos los pise.
		val patch = voiceProfiles[name] ?: BUILTIN_VOICE[name] ?: return
		val base = patch.baseVoice.coerceIn(0, Eci.PRESET_NAMES.lastIndex)
		settings.voice = base
		voice = base
		// Forma: lo guardado tal cual (ya viene saneado al guardar,
		// se vuelve a sanear al aplicar por si el JSON es externo).
		val edit = LinkedHashMap<Int, Int>()
		for ((param, value) in patch.params) {
			if (param == Eci.VOICE_SPEED || param !in 0 until Eci.NUM_VOICE_PARAMS) continue
			edit[param] = Eci.clampVoice(param, value)
		}
		settings.writeShape(base, edit)
		settings.speed = Eci.clampVoice(Eci.VOICE_SPEED, patch.speed)
		settings.useSystemSpeed = patch.systemSpeed
		useSystemSpeed = patch.systemSpeed
		settings.useSystemVolume = patch.systemVolume
		useSystemVolume = patch.systemVolume
		settings.volume = patch.volume.coerceIn(0, 500)
		volume = settings.volume
		settings.sampleRateHz = patch.sampleRateHz
		sampleRateHz = patch.sampleRateHz
		settings.bodyEnabled = patch.body
		bodyEnabled = patch.body
		settings.hdEnhance = patch.hdEnhance
		hdEnhance = patch.hdEnhance
		shape.clear()
		shape.putAll(settings.shape(base))
		settings.voiceProfile = name
		voiceProfile = name
		// 4.3: un perfil de voz toca la voz, la tasa, el volumen y el
		// cuerpo. Todo eso se repone por huella en la siguiente
		// emisión, pero el reinicio pedido aquí deja el motor limpio
		// para cuando el perfil venía de un archivo importado.
		settings.engineReload("perfil de voz aplicado")
		diag(
			"profile", "event" to "voice_applied",
			"builtin" to BUILTIN_VOICE.containsKey(name).toString(),
			"voice" to base.toString()
		)
	}

	/** Borra un perfil de voz propio. Contesta si existía. */
	fun deleteVoiceProfile(name: String): Boolean {
		val updated = voiceProfiles.toMutableMap()
		if (updated.remove(name) == null) return false
		settings.voiceProfiles = updated
		if (voiceProfile == name) {
			settings.voiceProfile = null
			voiceProfile = null
		}
		voiceProfiles = updated
		return true
	}

	/** Renombra un perfil de voz propio. Contesta si se guardó. */
	fun renameVoiceProfile(old: String, new: String): Boolean {
		val clean = new.trim()
		if (clean.isEmpty()) return false
		val updated = voiceProfiles.toMutableMap()
		val patch = updated.remove(old) ?: return false
		if (updated.containsKey(clean)) return false
		updated[clean] = patch
		settings.voiceProfiles = updated
		if (voiceProfile == old) {
			settings.voiceProfile = clean
			voiceProfile = clean
		}
		voiceProfiles = updated
		return true
	}

	/** Un perfil de voz como JSON para compartirlo. */
	fun exportVoiceProfileJson(name: String): String? {
		val patch = voiceProfiles[name] ?: return null
		return runCatching {
			val inner = Settings.VOICE_JSON.encodeToString(Settings.VoicePatch.serializer(), patch)
			"{\"name\":${Settings.VOICE_JSON.encodeToString(kotlinx.serialization.serializer<String>(), name)},\"patch\":$inner}"
		}.getOrNull()
	}

	/** Lee un JSON de [exportVoiceProfileJson] sin guardarlo:
	 *  nombre y parche saneados, o nada si no era válido. */
	fun parseVoiceProfileJson(json: String): Pair<String, Settings.VoicePatch>? {
		val parsed = runCatching {
			Settings.VOICE_JSON.parseToJsonElement(json).let { el ->
				val obj = el as? kotlinx.serialization.json.JsonObject ?: return null
				val name = obj["name"]?.let {
					Settings.VOICE_JSON.decodeFromJsonElement(kotlinx.serialization.serializer<String>(), it)
				}?.trim()?.take(60).orEmpty()
				val patch = obj["patch"]?.let {
					Settings.VOICE_JSON.decodeFromJsonElement(Settings.VoicePatch.serializer(), it)
				} ?: return null
				name to patch
			}
		}.getOrNull() ?: return null
		var (base, patch) = parsed
		if (base.isEmpty()) base = app.getString(R.string.voice_profile_label)
		val clean = patch.copy(
			baseVoice = patch.baseVoice.coerceIn(0, Eci.PRESET_NAMES.lastIndex),
			params = patch.params.filterKeys { it in 0 until Eci.NUM_VOICE_PARAMS && it != Eci.VOICE_SPEED }
				.mapValues { (k, v) -> Eci.clampVoice(k, v) },
			speed = Eci.clampVoice(Eci.VOICE_SPEED, patch.speed),
			volume = patch.volume.coerceIn(0, 500)
		)
		return base to clean
	}

	/** Guarda un parche de voz ya revisado como perfil propio y lo
	 *  aplica. Contesta el nombre final o nada. */
	fun adoptVoiceProfile(base: String, patch: Settings.VoicePatch): String? {
		val updated = voiceProfiles.toMutableMap()
		var name = base.trim().take(60).ifEmpty { return null }
		var n = 2
		while (updated.containsKey(name)) {
			name = "$base $n"
			n++
			if (n > 99) return null
		}
		updated[name] = patch
		settings.voiceProfiles = updated
		voiceProfiles = updated
		applyVoiceProfile(name)
		return name
	}

	/** Lee un JSON de [exportVoiceProfileJson] y lo guarda como
	 *  perfil propio. Contesta el nombre guardado o nada. */
	fun importVoiceProfileJson(json: String): String? {
		val (base, patch) = parseVoiceProfileJson(json) ?: return null
		return adoptVoiceProfile(base, patch)
	}

	/** Si el parámetro tiene un valor elegido (si no, manda el preset). */
	fun hasParamValue(param: Int): Boolean = shape.containsKey(param) || settings.shapeValue(voice, param) != null

	/** Valor en porcentaje (0-100) para el deslizador, usando el rango nativo del motor. */
	fun percentOf(param: Int): Int = Eci.toPercent(param, getVoiceParamValue(param))

	fun getVoiceParamValue(param: Int): Int {
		val shapeValue = shape[param]
		if (shapeValue != null) return shapeValue
		// El preset de la voz ELEGIDA, no la que el motor tenga
		// puesta: esa es la última que sonó y puede ser otra voz,
		// y entonces los deslizadores enseñaban valores que no son
		// los de esta voz. Solo si el motor aún no abrió se lee la
		// voz actual como último recurso.
		preview?.presetShape(voice)?.get(param)?.let { return it }
		// 5.3: el mismo valor de sistema que aplica [VoicePlan], para
		// que el deslizador no enseñe un cero que el motor no va a
		// usar. Antes caía en 0 y la fluctuación se veía apagada
		// mientras sonaba a 30.
		return preview?.getVoiceParam(param)?.takeIf { it >= 0 }
			?: VoicePlan.defaultValue(param)
	}

	// ---- escucharla ------------------------------------------------------

	/** Describe lo que la detección haría con [raw], para la
	 *  prueba de voz: idioma detectado y motor que sonaría, o por
	 *  qué no cambiaría nada. Solo heurística (instantánea): en
	 *  el servicio se suma ML Kit. */
	fun describeDetection(raw: String): String {
		if (raw.isBlank()) return "—"
		val tag = AutoLang.heuristic(raw) ?: return "—"
		val langName = java.util.Locale.forLanguageTag(AutoLang.iso1(tag)).displayLanguage
		if (!autoDetectLanguage) return app.getString(R.string.autodetect_off_hint, langName)
		val pref = when (tag) {
			"eng" -> autoVariantEn
			"spa" -> autoVariantEs
			"fra" -> autoVariantFr
			else -> 0
		}
		val engine = AutoLang.preferredEngine(tag, null, pref) ?: return "—"
		return app.getString(R.string.autodetect_will_use, langName, EngineLocale.displayName(app, engine))
	}

	/** Habla un texto libre con los ajustes en vigor: sirve para
	 *  probar cómo cae una palabra o frase concreta. Nada habla por
	 *  sí solo aquí; cada ajuste está en vigor en el momento en que
	 *  se escribe, y así es como se escucha. */
	fun sayCustom(text: String) {
		if (text.isNotBlank()) speakPipeline(text)
	}

	/** Últimas emisiones del servicio, lo más reciente primero. */
	var history: List<String> by mutableStateOf(emptyList())
		private set

	fun refreshHistory() {
		history = EvvTtsService.historySnapshot()
	}

	fun replayHistory(text: String) {
		sayCustom(text)
	}

	fun clearHistory() {
		EvvTtsService.clearHistory()
		history = emptyList()
		diag("history", "event" to "clear")
	}

	/** Habla todo el historial seguido (capado a lo razonable). */
	fun speakHistoryAll() {
		val all = history.take(10).joinToString(". ").take(1500)
		if (all.isNotBlank()) sayCustom(all)
	}

	/** Añade varias palabras de una vez desde texto pegado
	 *  ("palabra=pron" o "palabra:pron" por línea). Contesta
	 *  cuántas entraron. */
	fun addWordsBulk(text: String): Int {
		var added = 0
		val updated = settings.dictionaryWords.toMutableMap()
		for (line in text.lines()) {
			val entry = Dictionaries.parseLine(line)
				?: Dictionaries.parseCfdLine(line)?.let { it.word to it.say }
				?: continue
			if (entry.first.isBlank() || entry.second.isBlank()) continue
			updated[entry.first] = entry.second
			added++
		}
		if (added > 0) {
			settings.dictionaryWords = updated
			wordEntries = updated
			rebuildListed()
		}
		return added
	}

	/** Apaga todos los efectos de una vez (los niveles quedan). */
	fun resetFx() {
		settings.eqEnabled = false
		settings.reverbEnabled = false
		settings.echoEnabled = false
		settings.compressorEnabled = false
		settings.gateEnabled = false
		settings.chorusEnabled = false
		settings.phaserEnabled = false
		settings.vibratoLevel = 0
		settings.ringmodLevel = 0
		settings.fuzzLevel = 0
		settings.wahLevel = 0
		settings.maximizerLevel = 0
		settings.telephoneEnabled = false
		settings.normalizerEnabled = false
		settings.highpassEnabled = false
		settings.driveEnabled = false
		settings.crusherEnabled = false
		settings.flangerEnabled = false
		settings.warmthEnabled = false
		settings.deesserEnabled = false
		settings.eqBands().indices.forEach { settings.setEqBand(it, 0) }
		settings.exciterLevel = 0
		settings.tremoloLevel = 0
		settings.tapeMix = 0
		settings.subBassMix = 0
		settings.boostPercent = 100
		settings.fxProfile = null
		eqEnabled = false
		eqBands = List(10) { 0 }
		reverbEnabled = false
		echoEnabled = false
		compEnabled = false
		gateEnabled = false
		chorusEnabled = false
		phaserEnabled = false
		vibratoLevel = 0
		ringmodLevel = 0
		fuzzLevel = 0
		wahLevel = 0
		maximizerLevel = 0
		telephoneEnabled = false
		normalizerEnabled = false
		highpassEnabled = false
		driveEnabled = false
		crusherEnabled = false
		flangerEnabled = false
		warmthEnabled = false
		deesserEnabled = false
		exciterLevel = 0
		tremoloLevel = 0
		tapeMix = 0
		subBassMix = 0
		boostPercent = 100
		fxProfile = null
	}

	/** Borra ajustes y diccionario: vuelve a valores de fábrica.
	 *  4.3: pide reiniciar el motor, porque aquí cambia todo. */
	fun resetAll() {
		for (entry in settings.dictionaries) {
			runCatching { java.io.File(entry.path).delete() }
		}
		settings.restorePrefs(emptyMap())
		settings.engineReload("ajustes devueltos a sus valores")
		reloadAll()
	}

	private fun speakPipeline(rawInput: String) {
		// 5.1: la muestra pasa por la misma normalización que el
		// servicio. Sin esto, una tilde descompuesta o una letra
		// matemática sonarían distinto en la prueba y en TalkBack.
		val raw = UnicodeText.normalize(rawInput)
		// 4.0: con detección activada, la prueba también cambia de
		// idioma como el servicio. Solo heurística aquí (instantánea
		// en el hilo principal); el servicio suma ML Kit en fondo.
		if (autoDetectLanguage && raw.isNotBlank()) {
			AutoLang.heuristic(raw)?.let { tag ->
				AutoLang.preferredEngine(tag, null, settings.autoVariantFor(tag))
			}?.let { switchPreview(it) }
		}
		val tag = Eci.localeOf(previewLang)?.first ?: "eng"
		val said = if (processEmojis) EmojiSpeech.describeLang(raw, tag) else raw
		val symbols = if (readUnicodeSymbols) UnicodeSpeech.describeLang(said, tag) else said
		val punctuated = Punctuation.apply(symbols, punctEnabled, punctLevel, tag)
		val unparened = if (skipParentheses) TextFixes.withoutParens(punctuated) else punctuated
		val fixed = TextFixes.apply(unparened, tag)
		val numbered = Numbers.apply(fixed, numberForceEnabled, numberGroup)
		// 5.3: el prefijo de prosodia y el reinicio de voz los compone
		// [Emission] dentro de [EvvPreview]. Antes se pegaba aquí y el
		// reinicio se añadía otra vez, así que la muestra no sonaba
		// exactamente como el servicio.
		val prefix = Prosody.natural()
		if (mixEnabled && mixPartner != voice) {
			val chunks = TextPieces.alternateWords(TextFixes.terminalDot(numbered), mixInterval)
			val segments = chunks.mapIndexed { at, chunk ->
				val v = if (at % 2 == 0) voice else mixPartner
				val shape = settings.shape(v)
				val paused = Pauses.apply(chunk, pauses, at == chunks.lastIndex, pauseDurationMs, pauseDurationMs)
				Triple(v, shape, paused)
			}
			preview?.sayAlternating(
				segments, sampleRateHz, settings.volume, listedEntries, fxChain(sampleRateHz),
				prefix, ignoreVoiceTags, settings.useSystemSpeed
			)
			return
		}
		val withPauses = Pauses.apply(TextFixes.terminalDot(numbered), pauses, true, pauseDurationMs, pauseDurationMs)
		preview?.say(
			withPauses, voice, effectiveShape(),
			sampleRateHz, settings.volume, listedEntries, fxChain(sampleRateHz), prefix,
			ignoreVoiceTags, settings.useSystemSpeed
		)
	}

	/**
	 * El idioma con el que se habla en la vista previa.
	 *
	 * 4.1: "Sistema" ya no cae en el primer idioma que el motor
	 * declara, que es el inglés, sino en el que corresponde al
	 * idioma del teléfono ([Settings.resolvedLanguage]). Con un
	 * teléfono en español, la muestra de la pantalla de ajustes se
	 * oía en inglés y la mitad de las pruebas de pronunciación
	 * salían mal por eso y no por el motor.
	 */
	private fun resolveLanguage(): Int {
		val wanted = settings.defaultLanguage
		if (wanted != 0 && EvvEngine.available.contains(wanted)) return wanted
		val here = settings.resolvedLanguage()
		if (here != 0) return here
		return EvvEngine.available.firstOrNull() ?: 0
	}

	private fun previewLanguage(): Int = resolveLanguage()

	private fun refreshShape() {
		// 4.0: sin escritura silenciosa. Una voz nueva muestra lo
		// que el preset trae (vía getVoiceParamValue fallback) y
		// solo se guarda lo que el usuario mueve. Antes se forzaba
		// inflexión 55 al primer uso y cada voz sonaba distinta a
		// su preset sin pedirlo.
		shape.clear()
		shape.putAll(settings.shape(settings.voice))
	}

	companion object {
		val RATES: List<Int> = Eci.SAMPLE_RATES.sorted()

		/** Cuántas entradas se enseñan en la vista previa de una
		 *  importación. Suficiente para reconocer el archivo sin
		 *  llenar la pantalla con 4000 líneas. */
		const val PREVIEW_LINES = 12

		/**
		 * 4.3: cuánto se espera a que se calle lo que se está
		 * cambiando antes de releer y redibujar. Un deslizador que
		 * se arrastra escribe en cada paso; con esto, da igual cuántos
		 * pasos dé, la pantalla se refresca una vez al soltar.
		 *
		 * 250 ms es más corto que el tiempo que tarda una persona en
		 *  volver a tocar la pantalla, así que no se ve retraso.
		 */
		const val SYNC_QUIET_MS = 250L

		/** JSON para compartir perfiles (legible y con claves). */
		val PROFILE_JSON = kotlinx.serialization.json.Json {
			prettyPrint = true
			ignoreUnknownKeys = true
		}

		/** Qué trae un archivo a importar, como texto estable:
		 *  "fx", "voice", "backup", "dict" o "unknown". Para avisar
		 *  "esto va en otra categoría" en vez de un error genérico. */
		fun detectProfileKind(content: String): String {
			val t = content.trimStart()
			if (t.isEmpty()) return "unknown"
			if (t.startsWith(BackupStore.HEADER)) return "backup"
			if (t.startsWith("{")) {
				val parsed = runCatching {
					val obj = PROFILE_JSON.parseToJsonElement(t) as?
						kotlinx.serialization.json.JsonObject ?: return@runCatching null
					val patch = obj["patch"] as?
						kotlinx.serialization.json.JsonObject ?: return@runCatching null
					val keys = patch.keys
					when {
						"baseVoice" in keys || "params" in keys -> "voice"
						"bands" in keys || "reverb" in keys -> "fx"
						else -> null
					}
				}.getOrNull()
				return parsed ?: "unknown"
			}
			var hits = 0
			for (line in t.lineSequence().take(60)) {
				if (Dictionaries.parseLine(line) != null || Dictionaries.parseCfdLine(line) != null) {
					if (++hits >= 2) return "dict"
				}
			}
			return "unknown"
		}

		/**
		 * 4.3: perfiles de voz que vienen de fábrica, como ya había
		 * para los efectos.
		 *
		 * **Lo que esto es y lo que no es.** El módulo `esus` es el
		 * español latinoamericano de IBM, de los años noventa, y su
		 * fonología no se cambia desde aquí: los fonemas los da el
		 * motor. Lo que sí se puede cambiar es el carácter de la voz,
		 * y por eso esto lleva a un preset, una altura y una velocidad.
		 *
		 * "México moderno" quiere decir una voz que no suene de los
		 * años noventa ni a anciano, así que:
		 *
		 *  - evita "Abuela" y "Abuelo", que son los dos presets que
		 *    IBM diseñó para sonar mayores,
		 *  - sube la altura un poco, que es lo que más cambia el
		 *    carácter de una voz,
		 *  - sube la velocidad de la base, que a 11025 Hz es lo que
		 *    separa una lectura deprisa de una lenta,
		 *  - y enciende el realce, que es lo que hace que suene
		 *    cerca en vez de lejos.
		 *
		 * El preset es el 2 ("Bobby") para México y el 3 ("Rocko")
		 * para el resto de Latinoamérica, no por su nombre propio sino
		 * por lo que suenan.
		 */
		val BUILTIN_VOICE: Map<String, Settings.VoicePatch> = mapOf(
			"México moderno" to Settings.VoicePatch(
				baseVoice = 2,
				speed = Eci.DEFAULT_SPEED + 8,
				systemSpeed = true,
				hdEnhance = true,
				body = true
			),
			"Latinoamérica moderna" to Settings.VoicePatch(
				baseVoice = 3,
				speed = Eci.DEFAULT_SPEED + 6,
				systemSpeed = true,
				hdEnhance = true,
				body = true
			),
			"Clara" to Settings.VoicePatch(
				baseVoice = 4,
				speed = Eci.DEFAULT_SPEED + 4,
				systemSpeed = true,
				hdEnhance = true
			),
			"Narración" to Settings.VoicePatch(
				baseVoice = 0,
				speed = Eci.DEFAULT_SPEED - 10,
				systemSpeed = true,
				hdEnhance = true
			)
		)

		/** Perfiles de fábrica: nombre a parche completo.
		 *  4.1: podados los duplicados (había 5 "claridad", 3
		 *  "noche", 3 "radio" y 2 "podcast" casi idénticos).
		 *  "Podcast" trae la cadena completa que antes solo tenía
		 *  "Podcast pro". Nuevo: "Metálica" (modulador en anillo). */
		val BUILTIN_FX: Map<String, Settings.FxPatch> = mapOf(			"Plano" to Settings.FxPatch(),
			"Voz clara" to Settings.FxPatch(intArrayOf(0, 0, 0, 1, 3, 4, 3, 1, 0, 0), 0, 0, 3, 0),
			"Voz cristalina" to Settings.FxPatch(
				bands = intArrayOf(0, 0, 0, 1, 2, 3, 4, 3, 2, 1), presence = 4,
				comp = true, boost = 103, gate = true, exciter = 50, deesser = true
			),
			"Máxima claridad" to Settings.FxPatch(
				bands = intArrayOf(-1, 0, 1, 2, 3, 4, 4, 3, 2, 1),
				presence = 4, boost = 103, gate = true, exciter = 55,
				deesser = true, normalizer = true, highpass = true
			),
			"Realce de agudos" to Settings.FxPatch(
				bands = intArrayOf(0, 0, 0, 0, 1, 2, 3, 4, 4, 4), presence = 4,
				boost = 102, exciter = 45, deesser = true
			),
			"Graves potentes" to Settings.FxPatch(intArrayOf(5, 4, 3, 2, 0, 0, 0, 0, 0, 0), 0, 5, 0, 0),
			"Bajo + brillo" to Settings.FxPatch(
				bands = intArrayOf(4, 3, 2, 1, 0, 1, 2, 3, 3, 4), bass = 3,
				presence = 4, boost = 102, exciter = 35
			),
			"Noche suave" to Settings.FxPatch(intArrayOf(2, 1, 0, 0, -1, -1, -2, -3, -3, -4), 15, 0, -2, 0),
			"Noche clara" to Settings.FxPatch(
				bands = intArrayOf(2, 1, 0, 0, -1, -1, -2, -2, -2, -2), reverb = 12,
				comp = true, gate = true, warmth = true, normalizer = true
			),
			"Sala grande" to Settings.FxPatch(IntArray(10), 45, 0, 0, 0),
			"Catedral" to Settings.FxPatch(intArrayOf(3, 2, 1, 0, 0, 0, 1, 2, 3, 3), 65, 2, 1, 25, false, 102, false),
			"Cine en casa" to Settings.FxPatch(intArrayOf(4, 3, 2, 1, 0, 0, 1, 2, 2, 3), 32, 4, 2, 0),
			"Eco suave" to Settings.FxPatch(IntArray(10), 20, 0, 1, 35),
			"Espacial" to Settings.FxPatch(reverb = 45, echo = 30, chorus = true, flanger = true),
			"Estudio" to Settings.FxPatch(intArrayOf(0, 0, 0, 1, 2, 2, 1, 1, 0, 0), 18, 1, 2, 0, true, 104, true),
			"Podcast" to Settings.FxPatch(
				bands = intArrayOf(0, 0, 0, 1, 2, 3, 3, 2, 1, 0), reverb = 6,
				presence = 2, comp = true, boost = 102, gate = true,
				deesser = true, normalizer = true, highpass = true
			),
			"Conferencia" to Settings.FxPatch(intArrayOf(-1, 0, 0, 1, 3, 3, 2, 1, 0, 0), 5, 0, 3, 0, true, 103, true),
			"Radio" to Settings.FxPatch(intArrayOf(-1, 0, 1, 2, 4, 5, 4, 3, 2, 1), 0, 0, 4, 0, true, 106, true),
			"Radio FM" to Settings.FxPatch(
				bands = intArrayOf(0, 0, 1, 2, 3, 4, 4, 3, 2, 1),
				presence = 3, comp = true, boost = 105, gate = true,
				telephone = true, highpass = true, drive = true
			),
			"Teléfono" to Settings.FxPatch(presence = 4, comp = true, gate = true, boost = 106, crusher = true),
			"Robot" to Settings.FxPatch(IntArray(10), 0, 0, 3, 30, false, 104, true, 100, false),
			"Metálica" to Settings.FxPatch(
				presence = 2, comp = true, gate = true, ringmod = 65, deesser = true
			),
			"Dalek total" to Settings.FxPatch(
				presence = 2, comp = true, boost = 103, gate = true,
				ringmod = 80, telephone = true, reverb = 12, deesser = true
			),
			"Locutor FM" to Settings.FxPatch(
				bands = intArrayOf(2, 2, 1, 0, 0, 1, 2, 3, 3, 3), bass = 4,
				presence = 3, comp = true, boost = 103, gate = true,
				exciter = 35, deesser = true, highpass = true,
				maximizer = 45
			),
			"Fuzz pesado" to Settings.FxPatch(
				bands = intArrayOf(3, 2, 1, 0, 0, 0, 1, 2, 2, 3), bass = 3,
				comp = true, gate = true, fuzz = 70, deesser = true
			),
			"Wah funky" to Settings.FxPatch(
				presence = 2, comp = true, boost = 102, gate = true,
				wah = 70, warmth = true, deesser = true
			),
			"Susurro" to Settings.FxPatch(intArrayOf(1, 1, 0, 0, -1, -2, -2, -3, -3, -4), 30, -2, -3, 15, true, 90, true),
			"Voz flotante" to Settings.FxPatch(
				bands = intArrayOf(0, 0, 0, 1, 2, 2, 1, 1, 0, 0), reverb = 35,
				echo = 20, chorus = true, phaser = true,
				comp = true, boost = 102, gate = true
			),
			"Canto cálido" to Settings.FxPatch(
				bands = intArrayOf(1, 1, 1, 2, 3, 3, 2, 1, 0, 0),
				presence = 2, comp = true, boost = 102,
				vibrato = 35, warmth = true, deesser = true
			),
			"Altavoz pequeño" to Settings.FxPatch(
				bands = intArrayOf(0, 0, -1, -1, 0, 1, 2, 3, 3, 3),
				presence = 3, boost = 103, exciter = 45, deesser = true,
				highpass = true, drive = true
			),
			"Voz pareja" to Settings.FxPatch(
				comp = true, gate = true, normalizer = true, deesser = true
			)
		)
	}
}

/** Copias JSON sin pantalla: exporta, revisa e importa todo
 *  (ajustes, perfiles, velocidad, diccionario). Lo usan la
 *  pantalla de copias y el "Abrir con" de fuera. */
object BackupStore {

	data class ImportResult(val message: String, val localeChanged: Boolean)

	enum class IssueKind {
		FUTURE_VERSION,
		BAD_PREFS,
		UNKNOWN_PREF_KEYS,
		INVALID_PREF_ENTRIES,
		OUT_OF_RANGE_PREFS,
		DICT_FORMAT,
		DICT_DATA,
		DICT_TOO_LARGE
	}

	data class Issue(val kind: IssueKind, val count: Int = 1)

	data class ParsedBackup(
		val name: String,
		val prefs: Map<String, Any?>,
		val dictExt: String?,
		val dictName: String,
		val dictBytes: ByteArray?,
		val issues: List<Issue> = emptyList(),
		val compatible: Boolean = true,
		/** 5.4.1: preferencias del apartado de actualizaciones. */
		val updatePrefs: Map<String, Any?> = emptyMap(),
		/** 5.4.1: todos los diccionarios de la biblioteca. */
		val dictionaries: List<BackupDictionary> = emptyList(),
		/** 2 para el JSON de 5.1-5.4; 3 desde 5.4.1. */
		val version: Int = 2,
		/** 5.4.1: la copia trae la sección `dictionaries`, aunque
		 *  venga vacía. Solo entonces se reemplaza la biblioteca. */
		val libraryPresent: Boolean = false,
		/** 5.4.1: la copia trae la sección `updates`. */
		val updatesPresent: Boolean = false
	)

	/**
	 * 5.4.1: un diccionario de la copia, con sus datos y sin la ruta
	 * del aparato que lo guardó. Al importar se reconstruye en la
	 * carpeta propia de esta instalación.
	 */
	data class BackupDictionary(
		val id: String,
		val name: String,
		val language: Int,
		val active: Boolean,
		val ext: String,
		val bytes: ByteArray
	)

	/** El formato viejo sigue leyéndose: una copia de 4.x/5.0. */
	const val HEADER = "# reloqbkp v1"
	const val JSON_FORMAT = "reloqbkp"
	const val JSON_VERSION = 3
	/** 5.0: extensión propia; el contenido sigue siendo JSON. */
	const val EXTENSION = "reloqbkp"
	private const val LEGACY_EXTENSION = "json"

	/** Nombre de archivo con la extensión propia, sin duplicarla si
	 *  ya venía puesta. Acepta también el `.json` de las copias
	 *  anteriores. */
	fun fileName(raw: String): String {
		val clean = raw.trim()
		val base = when {
			clean.endsWith(".$EXTENSION", ignoreCase = true) ->
				clean.dropLast(EXTENSION.length + 1)
			clean.endsWith(".$LEGACY_EXTENSION", ignoreCase = true) ->
				clean.dropLast(LEGACY_EXTENSION.length + 1)
			else -> clean
		}
		return "${base.ifBlank { "copia" }}.$EXTENSION"
	}
	/** 4.0: la copia viaja en texto Base64; más de esto es
	 *  basura o ataque, no diccionario real. 5.4.1: la copia lleva
	 *  la biblioteca entera, así que el tope sube para que varios
	 *  diccionarios quepan. */
	const val MAX_BACKUP_BYTES = 64 * 1024 * 1024
	const val MAX_DICT_BYTES = 10 * 1024 * 1024
	/** 5.4.1: tope de diccionarios en una copia; más es un archivo
	 *  manipulado, no una biblioteca real. */
	const val MAX_BACKUP_DICTS = 100

	/**
	 * Arma la copia en memoria para guardarla y compartirla.
	 *
	 * 5.0: JSON. Antes era un formato de líneas propio; funcionaba,
	 * pero no lo podía leer nada que no fuera esta app y una copia
	 * con un ajuste nuevo dependía de acordarse de la puerta de
	 * importación. Aquí los ajustes van con su tipo y su valor, el
	 * diccionario personalizado va en Base64, y todo lo que la app
	 * guarda entra solo con [Settings.dumpPrefs].
	 */
	fun buildBackup(
		name: String,
		settings: Settings,
		diagnosticsPrefs: Map<String, Any?> = emptyMap(),
		app: android.content.Context? = null
	): ByteArray {
		val prefs = kotlinx.serialization.json.buildJsonObject {
			for ((key, value) in settings.dumpPrefs()) {
				// 5.4.1: la biblioteca viaja como archivos, no como
				// rutas de este aparato. [Settings.KEY_DICTIONARY_PATH]
				// y [Settings.KEY_DICTIONARY_NAME] se guardan como
				// información, pero la importación las reconstruye.
				if (key == Settings.KEY_DICTIONARY_LIBRARY) continue
				putTyped(key, value)
			}
			// 5.4: el estado del registro (casilla activa/inactiva) viaja
			// en la copia para que, al importar, la app arranque con el
			// mismo ajuste de registro que tenía. Los archivos de registro
			// nunca se incluyen: solo el interruptor y sus marcas.
			for ((key, value) in diagnosticsPrefs) putTyped(key, value)
		}
		val dictionaries = kotlinx.serialization.json.buildJsonArray {
			for ((entry, file) in dictionaryFiles(settings, app)) {
				val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
				if (bytes.isEmpty() || bytes.size > MAX_DICT_BYTES) continue
				add(kotlinx.serialization.json.buildJsonObject {
					put("id", kotlinx.serialization.json.JsonPrimitive(entry.id))
					put("name", kotlinx.serialization.json.JsonPrimitive(entry.name.take(200)))
					put("language", kotlinx.serialization.json.JsonPrimitive(entry.language))
					put("active", kotlinx.serialization.json.JsonPrimitive(entry.active))
					put("ext", kotlinx.serialization.json.JsonPrimitive(dictionaryExt(file)))
					put(
						"data",
						kotlinx.serialization.json.JsonPrimitive(
							android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
						)
					)
				})
			}
		}
		// 5.4.1: también viajan las preferencias de actualizaciones
		// (comprobar al iniciar, última comprobación, versión omitida
		// y la última versión abierta). El APK pendiente no: es una
		// ruta de este aparato. No hay archivos de registro aquí.
		val updates = kotlinx.serialization.json.buildJsonObject {
			if (app != null) {
				for ((key, value) in UpdatePrefs(app).dumpPrefs()) putTyped(key, value)
			}
		}
		val root = kotlinx.serialization.json.buildJsonObject {
			put("format", kotlinx.serialization.json.JsonPrimitive(JSON_FORMAT))
			put("version", kotlinx.serialization.json.JsonPrimitive(JSON_VERSION))
			put("name", kotlinx.serialization.json.JsonPrimitive(name))
			put("prefs", prefs)
			put("dictionaries", dictionaries)
			put("updates", updates)
		}
		return root.toString().toByteArray(Charsets.UTF_8)
	}

	/** Un valor con su tipo, como lo lee [parseJsonBackup]. */
	private fun kotlinx.serialization.json.JsonObjectBuilder.putTyped(key: String, value: Any?) {
		val typed = when (value) {
			is Int -> "I" to kotlinx.serialization.json.JsonPrimitive(value)
			is Long -> "L" to kotlinx.serialization.json.JsonPrimitive(value)
			is Float -> "F" to kotlinx.serialization.json.JsonPrimitive(value)
			is Boolean -> "B" to kotlinx.serialization.json.JsonPrimitive(value)
			is String -> "S" to kotlinx.serialization.json.JsonPrimitive(value)
			is Set<*> -> "SS" to kotlinx.serialization.json.buildJsonArray {
				for (one in value) add(kotlinx.serialization.json.JsonPrimitive(one.toString()))
			}
			else -> return
		}
		put(key, kotlinx.serialization.json.buildJsonObject {
			put("type", kotlinx.serialization.json.JsonPrimitive(typed.first))
			put("value", typed.second)
		})
	}

	/**
	 * 5.4.1: todos los diccionarios que la app conoce, sin repetir
	 * archivos. La biblioteca manda; además se miran los archivos de
	 * la carpeta propia por si alguno quedó fuera de la lista.
	 */
	private fun dictionaryFiles(
		settings: Settings,
		app: android.content.Context?
	): List<Pair<Settings.DictionaryEntry, java.io.File>> {
		val out = LinkedHashMap<String, Pair<Settings.DictionaryEntry, java.io.File>>()
		fun add(entry: Settings.DictionaryEntry) {
			val file = java.io.File(entry.path)
			if (!file.isFile || file.length() <= 0L) return
			out.putIfAbsent(file.absolutePath, entry to file)
		}
		settings.dictionaries.forEach { add(it) }
		settings.dictionaryForLanguage(settings.activeDictionaryLanguage)?.let { add(it) }
		if (app != null) {
			val dir = DirectBoot.dictionaries(app)
			val activePath = settings.dictionaryForLanguage(settings.activeDictionaryLanguage)?.path
			for (file in dir.listFiles().orEmpty()) {
				if (!file.isFile || file.length() <= 0L) continue
				val ext = file.extension.lowercase(java.util.Locale.ROOT)
				if (ext != "cfd" && ext != "reloqdic") continue
				if (out.containsKey(file.absolutePath)) continue
				val entry = Settings.DictionaryEntry(
					id = "file-${file.name}",
					name = file.nameWithoutExtension,
					language = settings.activeDictionaryLanguage,
					path = file.absolutePath,
					active = file.absolutePath == activePath
				)
				out[file.absolutePath] = entry to file
			}
		}
		return out.values.toList()
	}

	/** El formato que se guarda para [file], por extensión o por
	 *  contenido. */
	private fun dictionaryExt(file: java.io.File): String {
		val ext = file.extension.lowercase(java.util.Locale.ROOT)
		return if (ext == "cfd" || ext == "reloqdic") ext else Dictionaries.formatOf(file)
	}

	fun exportTo(
		resolver: android.content.ContentResolver,
		uri: android.net.Uri,
		name: String,
		settings: Settings,
		app: android.content.Context? = null
	): Boolean = runCatching {
		val bytes = buildBackup(name, settings, app = app)
		resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
		true
	}.getOrDefault(false)

	/** Lee una copia sin aplicarla, para mostrar qué trae.
	 *  Fuera del hilo principal. Acepta el JSON de 5.0 y el formato
	 *  de líneas anterior, para que una copia vieja siga sirviendo. */
	fun parseBackup(
		resolver: android.content.ContentResolver,
		uri: android.net.Uri
	): ParsedBackup? = runCatching {
		// 4.0: corta antes de leer basura de 100 MB.
		val sizeHint = runCatching {
			resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
		}.getOrNull() ?: -1L
		if (sizeHint > MAX_BACKUP_BYTES) return null
		val raw = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
		if (raw.size > MAX_BACKUP_BYTES) return null
		val text = String(raw, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
		if (text.startsWith("{")) parseJsonBackup(text) else parseLegacyBackup(text)
	}.getOrNull()

	/** El JSON de 5.0 y el de 5.4.1 (con biblioteca y actualizaciones). */
	internal fun parseJsonBackup(text: String): ParsedBackup? {
		val root = kotlinx.serialization.json.Json.parseToJsonElement(text)
			 as? kotlinx.serialization.json.JsonObject ?: return null
		val format = root["format"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
		if (format != JSON_FORMAT) return null
		val version = root["version"]?.let {
			(it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
		} ?: 1
		val name = root["name"]?.let {
			(it as? kotlinx.serialization.json.JsonPrimitive)?.content
		}.orEmpty().take(200)
		// Una copia de una versión futura se reconoce para poder decirlo,
		// pero no se interpreta ni se deja importar.
		if (version < 1 || version > JSON_VERSION) {
			return ParsedBackup(
				name, emptyMap(), null, "", null,
				listOf(Issue(IssueKind.FUTURE_VERSION, version)),
				compatible = false, version = version
			)
		}
		val issues = ArrayList<Issue>()
		val prefs = LinkedHashMap<String, Any?>()
		val prefElement = root["prefs"]
		if (prefElement != null) {
			if (prefElement !is kotlinx.serialization.json.JsonObject) {
				issues += Issue(IssueKind.BAD_PREFS)
			} else {
				prefs.putAll(
					readTypedPrefs(
						withoutLegacyPrefs(prefElement, LEGACY_SETTINGS_KEYS),
						known = { isKnownPrefKey(it) },
						expected = { expectedType(it) },
						sane = { key, value -> isSanePref(key, value) },
						issues = issues
					)
				)
			}
		}
		var dictExt: String? = null
		var dictName = ""
		var bytes: ByteArray? = null
		val dictElement = root["dictionary"]
		if (dictElement != null && dictElement !is kotlinx.serialization.json.JsonObject) {
			issues += Issue(IssueKind.DICT_DATA)
		} else {
			val dict = dictElement as? kotlinx.serialization.json.JsonObject
			val extRaw = (dict?.get("ext") as? kotlinx.serialization.json.JsonPrimitive)
				?.content.orEmpty().lowercase(java.util.Locale.ROOT).take(12)
			val ext = if (extRaw == "cfd" || extRaw == "reloqdic") extRaw else null
			dictName = (dict?.get("name") as? kotlinx.serialization.json.JsonPrimitive)
				?.content.orEmpty().take(200)
			val data = (dict?.get("data") as? kotlinx.serialization.json.JsonPrimitive)?.content
			val wantsDictionary = extRaw.isNotEmpty() || !data.isNullOrEmpty() || dictName.isNotEmpty()
			if (wantsDictionary && ext == null) {
				issues += Issue(IssueKind.DICT_FORMAT)
			} else if (ext != null && !data.isNullOrEmpty()) {
				val decoded = decodeDictionary(data, issues)
				if (decoded != null) {
					dictExt = ext
					bytes = decoded
				}
			}
		}
		// 5.4.1: la biblioteca entera, con un archivo por entrada. La
		// sección puede venir vacía: eso significa que el aparato de
		// origen no tenía diccionarios, y al importar se reemplaza la
		// biblioteca por esa lista vacía.
		var libraryPresent = false
		val dictionaries = ArrayList<BackupDictionary>()
		val dictsElement = root["dictionaries"]
		if (dictsElement != null) {
			libraryPresent = true
			if (dictsElement !is kotlinx.serialization.json.JsonArray) {
				issues += Issue(IssueKind.DICT_DATA)
			} else {
				if (dictsElement.size > MAX_BACKUP_DICTS) {
					issues += Issue(
						IssueKind.DICT_DATA,
						dictsElement.size - MAX_BACKUP_DICTS
					)
				}
				var decodedBytes = 0L
				for (index in 0 until minOf(dictsElement.size, MAX_BACKUP_DICTS)) {
					val one = readBackupDictionary(dictsElement[index], issues) ?: continue
					if (decodedBytes + one.bytes.size > MAX_BACKUP_BYTES) {
						issues += Issue(IssueKind.DICT_TOO_LARGE)
						continue
					}
					decodedBytes += one.bytes.size
					dictionaries += one
				}
			}
		}
		// 5.4.1: frecuencia, última comprobación y versiones omitidas.
		var updatesPresent = false
		val updates = LinkedHashMap<String, Any?>()
		val updatesElement = root["updates"]
		if (updatesElement != null) {
			updatesPresent = true
			if (updatesElement !is kotlinx.serialization.json.JsonObject) {
				issues += Issue(IssueKind.BAD_PREFS)
			} else {
				updates.putAll(
					readTypedPrefs(
						withoutLegacyPrefs(updatesElement, UpdatePrefs.LEGACY_KEYS),
						known = { isKnownUpdateKey(it) },
						expected = { updateExpectedType(it) },
						sane = { key, value -> isSaneUpdatePref(key, value) },
						issues = issues
					)
				)
			}
		}
		if (prefs.isEmpty() && bytes == null && dictionaries.isEmpty() && updates.isEmpty()) return null
		return ParsedBackup(
			name, prefs, dictExt, dictName, bytes, issues,
			version = version,
			updatePrefs = updates,
			dictionaries = dictionaries,
			libraryPresent = libraryPresent,
			updatesPresent = updatesPresent
		)
	}

	/**
	 * 5.4.3: quita del JSON las claves que ya no existen **antes** de
	 * pasarlo por la puerta de claves conocidas. Así una copia de una
	 * versión anterior se importa sin un aviso por cada ajuste
	 * retirado: no son datos rotos, son datos viejos.
	 */
	private fun withoutLegacyPrefs(
		source: kotlinx.serialization.json.JsonObject,
		legacy: Set<String>
	): kotlinx.serialization.json.JsonObject {
		if (legacy.none { it in source }) return source
		return kotlinx.serialization.json.JsonObject(
			source.filterKeys { it !in legacy }
		)
	}

	/**
	 * 5.4.1: lee un mapa `{clave: {type, value}}` con la misma puerta
	 * que los ajustes: clave conocida, tipo exacto y valor en rango.
	 * Se reutiliza para las preferencias de actualizaciones.
	 */
	private fun readTypedPrefs(
		source: kotlinx.serialization.json.JsonObject,
		known: (String) -> Boolean,
		expected: (String) -> Char?,
		sane: (String, Any?) -> Boolean,
		issues: MutableList<Issue>
	): LinkedHashMap<String, Any?> {
		val out = LinkedHashMap<String, Any?>()
		var unknownKeys = 0
		var invalidEntries = 0
		var outOfRange = 0
		source.forEach { (key, element) ->
			if (out.size > 600) {
				invalidEntries++
				return@forEach
			}
			if (!known(key)) {
				unknownKeys++
				return@forEach
			}
			val entry = element as? kotlinx.serialization.json.JsonObject
			if (entry == null) {
				invalidEntries++
				return@forEach
			}
			val type = (entry["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
			val raw = entry["value"]
			if (type == null || raw == null) {
				invalidEntries++
				return@forEach
			}
			if (expected(key)?.toString() != type) {
				invalidEntries++
				return@forEach
			}
			val value: Any? = when (type) {
				"I" -> (raw as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
				"L" -> (raw as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
				"F" -> (raw as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull()
				"B" -> (raw as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()
				"S" -> (raw as? kotlinx.serialization.json.JsonPrimitive)?.content
				"SS" -> (raw as? kotlinx.serialization.json.JsonArray)
					?.mapNotNull {
						(it as? kotlinx.serialization.json.JsonPrimitive)?.content
					}
					?.toSet()
				else -> null
			}
			if (value == null) {
				invalidEntries++
				return@forEach
			}
			if (!sane(key, value)) {
				outOfRange++
				return@forEach
			}
			out[key] = value
		}
		if (unknownKeys > 0) issues += Issue(IssueKind.UNKNOWN_PREF_KEYS, unknownKeys)
		if (invalidEntries > 0) issues += Issue(IssueKind.INVALID_PREF_ENTRIES, invalidEntries)
		if (outOfRange > 0) issues += Issue(IssueKind.OUT_OF_RANGE_PREFS, outOfRange)
		return out
	}

	/** Decodifica el Base64 de un diccionario y avisa si no sirve. */
	private fun decodeDictionary(data: String, issues: MutableList<Issue>): ByteArray? {
		val decoded = runCatching {
			android.util.Base64.decode(data, android.util.Base64.DEFAULT)
		}.getOrNull()
		return when {
			decoded == null || decoded.isEmpty() -> {
				issues += Issue(IssueKind.DICT_DATA)
				null
			}
			decoded.size > MAX_DICT_BYTES -> {
				issues += Issue(IssueKind.DICT_TOO_LARGE)
				null
			}
			else -> decoded
		}
	}

	/**
	 * 5.4.1: una entrada de la biblioteca. El nombre y el idioma son
	 * informativos; los bytes y la extensión son lo que se escribe.
	 */
	private fun readBackupDictionary(
		element: kotlinx.serialization.json.JsonElement,
		issues: MutableList<Issue>
	): BackupDictionary? {
		if (element !is kotlinx.serialization.json.JsonObject) {
			issues += Issue(IssueKind.DICT_DATA)
			return null
		}
		val extRaw = (element["ext"] as? kotlinx.serialization.json.JsonPrimitive)
			?.content.orEmpty().lowercase(java.util.Locale.ROOT).take(12)
		val ext = if (extRaw == "cfd" || extRaw == "reloqdic") extRaw else null
		val data = (element["data"] as? kotlinx.serialization.json.JsonPrimitive)?.content
		val language = (element["language"] as? kotlinx.serialization.json.JsonPrimitive)
			?.content?.toIntOrNull()
		if (ext == null) {
			issues += Issue(IssueKind.DICT_FORMAT)
			return null
		}
		if (language == null || language < 0 || data.isNullOrEmpty()) {
			issues += Issue(IssueKind.DICT_DATA)
			return null
		}
		val bytes = decodeDictionary(data, issues) ?: return null
		return BackupDictionary(
			id = (element["id"] as? kotlinx.serialization.json.JsonPrimitive)
				?.content.orEmpty().take(200),
			name = (element["name"] as? kotlinx.serialization.json.JsonPrimitive)
				?.content.orEmpty().take(200),
			language = language,
			active = (element["active"] as? kotlinx.serialization.json.JsonPrimitive)
				?.content?.toBooleanStrictOrNull() ?: false,
			ext = ext,
			bytes = bytes
		)
	}

	/** Tipo que [Settings.dumpPrefs] escribe para [key]. Un tipo
	 *  distinto se descarta: leerlo con el getter equivocado
	 *  lanzaría ClassCastException dentro de la app. */
	private fun expectedType(key: String): Char? {
		if (key.startsWith(Settings.KEY_EQ_BAND)) return 'I'
		if (key.startsWith("voice") && "_param" in key) return 'I'
		if (key == Diagnostics.KEY_STARTED) return 'L'
		return when (key) {
			in BOOL_KEYS -> 'B'
			in INT_KEYS -> 'I'
			in STRING_KEYS -> 'S'
			else -> null
		}
	}

	private fun typeOf(value: Any?): Char? = when (value) {
		is Int -> 'I'
		is Long -> 'L'
		is Float -> 'F'
		is Boolean -> 'B'
		is String -> 'S'
		is Set<*> -> 'X'
		else -> null
	}

	/** Rangos que la app ya aplica al leer; se validan antes de
	 *  escribir para no dejar valores que luego se recorten solos. */
	private fun isSanePref(key: String, value: Any?): Boolean = when (value) {
		is Boolean -> true
		is String -> value.length <= 100000
		is Set<*> -> value.size <= 500 &&
			value.all { it is String && (it as String).length <= 500 }
		is Float -> value.isFinite() && value >= -1_000_000f && value <= 1_000_000f
		is Long -> true
		is Int -> isSaneInt(key, value)
		else -> false
	}

	/** 5.4.1: claves de [UpdatePrefs], con su tipo y su rango. No
	 *  comparten lista con los ajustes de voz porque viven en otro
	 *  archivo y se restauran aparte. */
	private fun isKnownUpdateKey(key: String): Boolean =
		key in UpdatePrefs.BOOL_KEYS || key in UpdatePrefs.INT_KEYS ||
			key in UpdatePrefs.LONG_KEYS || key in UpdatePrefs.STRING_KEYS

	private fun updateExpectedType(key: String): Char? = when (key) {
		in UpdatePrefs.BOOL_KEYS -> 'B'
		in UpdatePrefs.INT_KEYS -> 'I'
		in UpdatePrefs.LONG_KEYS -> 'L'
		in UpdatePrefs.STRING_KEYS -> 'S'
		else -> null
	}

	private fun isSaneUpdatePref(key: String, value: Any?): Boolean = when (value) {
		is Boolean -> key in UpdatePrefs.BOOL_KEYS
		is Int -> key in UpdatePrefs.INT_KEYS && value >= 0
		is Long -> key == UpdatePrefs.KEY_LAST_CHECK && value >= 0L
		is String -> key in UpdatePrefs.STRING_KEYS && value.length <= 500
		else -> false
	}

	private fun isSaneInt(key: String, value: Int): Boolean {
		if (key.startsWith(Settings.KEY_EQ_BAND)) return value in -12..12
		if (key.startsWith("voice") && "_param" in key) {
			val param = key.substringAfterLast("_param").toIntOrNull() ?: return true
			if (param !in 0 until Eci.NUM_VOICE_PARAMS) return false
			return value in Eci.voiceRange(param)
		}
		return when (key) {
			Settings.KEY_VOICE -> value in 0..Eci.PRESET_NAMES.lastIndex
			Settings.KEY_MIX_PARTNER -> value in 0..Eci.PRESET_NAMES.lastIndex
			Settings.KEY_SAMPLE_RATE -> value in Eci.SAMPLE_RATES
			Settings.KEY_DEFAULT_LANGUAGE,
			Settings.KEY_AUTO_VARIANT_EN,
			Settings.KEY_AUTO_VARIANT_ES,
			Settings.KEY_AUTO_VARIANT_FR,
			Settings.KEY_DICTIONARY_LANGUAGE -> value >= 0
			Settings.KEY_SPEED -> value in 0..Eci.SPEED_MAX
			Settings.KEY_PAUSES -> value in Pauses.KEEP..Pauses.ALL
			Settings.KEY_PAUSE_DURATION_MS -> value in 0..2000
			Settings.KEY_VOLUME -> value in 0..500
			Settings.KEY_REVERB_LEVEL,
			Settings.KEY_ECHO_LEVEL,
			Settings.KEY_TREMOLO,
			Settings.KEY_VIBRATO,
			Settings.KEY_RINGMOD,
			Settings.KEY_FUZZ,
			Settings.KEY_WAH,
			Settings.KEY_MAXIMIZER,
			Settings.KEY_EXCITER,
			Settings.KEY_TAPE,
			Settings.KEY_SUB_BASS,
			Settings.KEY_REVERB_SIZE,
			Settings.KEY_DEESSER_DEPTH -> value in 0..100
			Settings.KEY_BASS_DB,
			Settings.KEY_PRESENCE_DB -> value in -12..12
			Settings.KEY_ECHO_TIME -> value in 80..600
			Settings.KEY_BOOST -> value in 0..200
			Settings.KEY_TREMOLO_RATE -> value in 20..120
			Settings.KEY_CRUSHER_BITS -> value in 4..16
			Settings.KEY_DICT_ORDER -> value in 0..3
			Settings.KEY_PUNCT_LEVEL -> value in 0..2
			Settings.KEY_NUMBER_GROUP ->
				value == Numbers.CORRIDO || value in Numbers.MIN_GROUP..Numbers.MAX_GROUP
			Settings.KEY_MIX_INTERVAL -> value in Settings.MIN_MIX_WORDS..Settings.MAX_MIX_WORDS
			else -> true
		}
	}

	/** El formato de líneas anterior. */
	internal fun parseLegacyBackup(text: String): ParsedBackup? {
		val all = text.lineSequence().toList()
		if (all.firstOrNull()?.trim() != HEADER) return null
		var name = ""
		var section = ""
		val prefs = LinkedHashMap<String, Any?>()
		var dictExt: String? = null
		var dictName = ""
		var dictData: String? = null
		for ((index, line) in all.withIndex()) {
			if (index > 20000) break
			if (line.length > 4 * 1024 * 1024) break
			when (line) {
				"[prefs]" -> section = "prefs"
				"[dict]" -> section = "dict"
				else -> {
					if (section == "prefs") {
						if (prefs.size > 600) continue
						val parts = line.split('|', limit = 3)
						if (parts.size != 3) continue
						val key = unesc(parts[1])
						// 4.0: lista blanca. Una copia no puede
						// inyectar claves arbitrarias.
						if (!isKnownPrefKey(key)) continue
						val value: Any? = when (parts[0]) {
							"I" -> parts[2].toIntOrNull()
							"L" -> parts[2].toLongOrNull()
							"F" -> parts[2].toFloatOrNull()
							"B" -> parts[2].toBooleanStrictOrNull()
							// 4.0: 100k para diccionarios propios
							// grandes (van en un solo String).
							"S" -> unesc(parts[2]).take(100000)
							"SS" -> parts[2].split("\u001F"[0]).map { unesc(it).take(500) }.take(500).toSet()
							else -> continue
						}
						if (value != null) prefs[key] = value
					} else if (section == "dict") {
						when {
							line.startsWith("ext=") -> {
								val e = line.substringAfter('=').trim().lowercase(java.util.Locale.ROOT).take(12)
								dictExt = if (e == "cfd" || e == "reloqdic") e else null
							}
							line.startsWith("name=") -> dictName = unesc(line.substringAfter('=')).take(200)
							line.startsWith("data=") -> {
								val d = line.substringAfter('=')
								if (d.length < 20 * 1024 * 1024) dictData = d
							}
						}
					} else if (line.startsWith("name=")) {
						name = unesc(line.substringAfter('=')).take(200)
					}
				}
			}
		}
		val dataLocal = dictData
		val extLocal = dictExt
		val bytes = if (!dataLocal.isNullOrEmpty() && !extLocal.isNullOrEmpty()) {
			runCatching {
				if (dataLocal.length > 14 * 1024 * 1024) return null
				val b = android.util.Base64.decode(dataLocal, android.util.Base64.DEFAULT)
				if (b.size > MAX_DICT_BYTES) return null
				b
			}.getOrNull()
		} else null
		// Sin prefs y sin dict no es copia, es archivo vacío.
		if (prefs.isEmpty() && bytes == null) return null
		return ParsedBackup(name, prefs, extLocal, dictName, bytes)
	}

	/** 5.4.3: claves retiradas que las copias anteriores todavía
	 *  pueden traer. Se descartan sin avisar. */
	private val LEGACY_SETTINGS_KEYS: Set<String> = setOf(
		Settings.LEGACY_LOW_LATENCY
	)

	/** Claves que una copia puede traer. Todo lo demás se ignora. */
	private fun isKnownPrefKey(key: String): Boolean {
		if (key.startsWith("voice") && "_param" in key) return true
		// Las 10 bandas del ecualizador van como eq_band_0..9.
		if (key.startsWith(Settings.KEY_EQ_BAND)) return true
		return key in KNOWN_KEYS
	}

	private val BOOL_KEYS: Set<String> = setOf(
		Settings.KEY_ABBREVIATIONS, Settings.KEY_AUTO_DETECT,
		Settings.KEY_IGNORE_VOICE_TAGS, Settings.KEY_USE_SYSTEM_SPEED,
		Settings.KEY_USE_SYSTEM_VOLUME, Settings.KEY_EQ_ENABLED,
		Settings.KEY_REVERB_ENABLED, Settings.KEY_ECHO_ENABLED,
		Settings.KEY_COMPRESSOR, Settings.KEY_GATE, Settings.KEY_CHORUS,
		Settings.KEY_CRUSHER, Settings.KEY_FLANGER, Settings.KEY_DEESSER,
		Settings.KEY_WARMTH, Settings.KEY_HD_ENHANCE, Settings.KEY_EMOJIS,
		Settings.KEY_UNICODE_SYMBOLS, Settings.KEY_PUNCT, Settings.KEY_SKIP_PARENS,
		Settings.KEY_NUMBER_FORCE, Settings.KEY_MIX_ENABLED,
		Settings.KEY_MIX_MIGRATED, Settings.KEY_PHASER, Settings.KEY_TELEPHONE,
		Settings.KEY_NORMALIZER, Settings.KEY_HIGHPASS, Settings.KEY_DRIVE,
		Settings.KEY_BODY,
		// 5.4: el interruptor de registro viaja en la copia y se restaura
		// al importar, para que la app arranque con el mismo estado.
		Diagnostics.KEY_ENABLED
	)

	private val INT_KEYS: Set<String> = setOf(
		Settings.KEY_VOICE, Settings.KEY_SAMPLE_RATE, Settings.KEY_DEFAULT_LANGUAGE,
		Settings.KEY_AUTO_VARIANT_EN, Settings.KEY_AUTO_VARIANT_ES,
		Settings.KEY_AUTO_VARIANT_FR, Settings.KEY_SPEED, Settings.KEY_PAUSES,
		Settings.KEY_PAUSE_DURATION_MS, Settings.KEY_VOLUME,
		Settings.KEY_REVERB_LEVEL, Settings.KEY_BASS_DB, Settings.KEY_PRESENCE_DB,
		Settings.KEY_ECHO_LEVEL, Settings.KEY_ECHO_TIME, Settings.KEY_BOOST,
		Settings.KEY_TREMOLO, Settings.KEY_TREMOLO_RATE, Settings.KEY_TAPE,
		Settings.KEY_SUB_BASS, Settings.KEY_REVERB_SIZE, Settings.KEY_CRUSHER_BITS,
		Settings.KEY_DEESSER_DEPTH, Settings.KEY_EXCITER, Settings.KEY_VIBRATO,
		Settings.KEY_RINGMOD, Settings.KEY_FUZZ, Settings.KEY_WAH,
		Settings.KEY_MAXIMIZER, Settings.KEY_MIX_PARTNER,
		Settings.KEY_MIX_INTERVAL, Settings.KEY_PUNCT_LEVEL,
		Settings.KEY_NUMBER_GROUP, Settings.KEY_DICT_ORDER,
		Settings.KEY_DICTIONARY_LANGUAGE
	)

	private val STRING_KEYS: Set<String> = setOf(
		Settings.KEY_FX_PROFILE, Settings.KEY_FX_PROFILES,
		Settings.KEY_VOICE_PROFILE, Settings.KEY_VOICE_PROFILES,
		Settings.KEY_APP_LOCALE, Settings.KEY_DICTIONARY_PATH,
		Settings.KEY_DICTIONARY_NAME, Settings.KEY_DICTIONARY_WORDS,
		Settings.KEY_DICTIONARY_LIBRARY
	)

	private val KNOWN_KEYS: Set<String> = setOf(
		Settings.KEY_VOICE, Settings.KEY_SAMPLE_RATE, Settings.KEY_DEFAULT_LANGUAGE,
		Settings.KEY_ABBREVIATIONS, Settings.KEY_AUTO_DETECT,
		Settings.KEY_IGNORE_VOICE_TAGS,
		Settings.KEY_AUTO_VARIANT_EN, Settings.KEY_AUTO_VARIANT_ES, Settings.KEY_AUTO_VARIANT_FR,
		Settings.KEY_SPEED, Settings.KEY_USE_SYSTEM_SPEED,
		Settings.KEY_USE_SYSTEM_VOLUME,
		Settings.KEY_PAUSES, Settings.KEY_PAUSE_DURATION_MS,
		Settings.KEY_VOLUME, Settings.KEY_EQ_ENABLED, Settings.KEY_REVERB_ENABLED,
		Settings.KEY_REVERB_LEVEL, Settings.KEY_BASS_DB, Settings.KEY_PRESENCE_DB,
		Settings.KEY_ECHO_ENABLED, Settings.KEY_ECHO_LEVEL, Settings.KEY_ECHO_TIME,
		Settings.KEY_COMPRESSOR, Settings.KEY_BOOST, Settings.KEY_GATE,
		Settings.KEY_TREMOLO, Settings.KEY_TREMOLO_RATE, Settings.KEY_CHORUS,
		Settings.KEY_CRUSHER, Settings.KEY_FLANGER, Settings.KEY_DEESSER,
		Settings.KEY_EXCITER, Settings.KEY_WARMTH, Settings.KEY_HD_ENHANCE,
		Settings.KEY_FX_PROFILE, Settings.KEY_FX_PROFILES,
		Settings.KEY_VOICE_PROFILE, Settings.KEY_VOICE_PROFILES, Settings.KEY_APP_LOCALE,
		Settings.KEY_EMOJIS, Settings.KEY_UNICODE_SYMBOLS,
		Settings.KEY_PUNCT, Settings.KEY_PUNCT_LEVEL,
		Settings.KEY_SKIP_PARENS, Settings.KEY_NUMBER_FORCE, Settings.KEY_NUMBER_GROUP,
		Settings.KEY_DICT_ORDER,
		Settings.KEY_MIX_ENABLED, Settings.KEY_MIX_PARTNER, Settings.KEY_MIX_INTERVAL,
		Settings.KEY_MIX_MIGRATED,
		Settings.KEY_PHASER, Settings.KEY_VIBRATO, Settings.KEY_RINGMOD, Settings.KEY_FUZZ,
		Settings.KEY_WAH, Settings.KEY_MAXIMIZER, Settings.KEY_TELEPHONE,
		Settings.KEY_NORMALIZER, Settings.KEY_HIGHPASS, Settings.KEY_DRIVE,
		Settings.KEY_BODY,
		Settings.KEY_DICTIONARY_PATH, Settings.KEY_DICTIONARY_NAME,
		Settings.KEY_DICTIONARY_WORDS, Settings.KEY_DICTIONARY_LIBRARY,
		Settings.KEY_DICTIONARY_LANGUAGE,
		// 4.3: estos cinco se perdían al importar. La lista de aquí
		// estaba escrita a mano y se quedó corta cuando se añadieron,
		// así que una copia los traía y la puerta los tiraba sin
		// avisar: se importaba, no pasaba nada, y la única pista era
		// que un ajuste no había cambiado. `BackupGateTest` falla si
		// vuelve a pasar.
		Settings.KEY_CRUSHER_BITS, Settings.KEY_DEESSER_DEPTH,
		Settings.KEY_REVERB_SIZE, Settings.KEY_SUB_BASS, Settings.KEY_TAPE,
		// 5.4: el estado del registro (casilla activa/inactiva) viaja
		// en la copia para restaurarse al importar.
		Diagnostics.KEY_ENABLED, Diagnostics.KEY_STARTED
	)

	/** Avisos legibles de una copia, para enseñarlos antes de
	 *  confirmar la importación. */
	fun issueMessages(context: android.content.Context, parsed: ParsedBackup): List<String> =
		parsed.issues.groupBy { it.kind }.map { (kind, list) ->
			val count = list.sumOf { it.count }
			when (kind) {
				IssueKind.FUTURE_VERSION ->
					context.getString(R.string.backup_warn_version, count)
				IssueKind.BAD_PREFS -> context.getString(R.string.backup_warn_bad_prefs)
				IssueKind.UNKNOWN_PREF_KEYS ->
					context.getString(R.string.backup_warn_unknown_keys, count)
				IssueKind.INVALID_PREF_ENTRIES ->
					context.getString(R.string.backup_warn_invalid_entries, count)
				IssueKind.OUT_OF_RANGE_PREFS ->
					context.getString(R.string.backup_warn_out_of_range, count)
				IssueKind.DICT_FORMAT -> context.getString(R.string.backup_warn_dict_format)
				IssueKind.DICT_DATA -> context.getString(R.string.backup_warn_dict_data)
				IssueKind.DICT_TOO_LARGE -> context.getString(R.string.backup_warn_dict_large)
			}
		}

	/** Aplica una copia ya leída. Fuera del hilo principal. */
	fun applyParsed(
		parsed: ParsedBackup,
		settings: Settings,
		app: android.content.Context
	): ImportResult {
		if (!parsed.compatible) {
			return ImportResult(app.getString(R.string.backup_incompatible), false)
		}
		val beforeLocale = settings.appLocale
		val libraryBefore = settings.dictionaries
		// 4.0: solo claves conocidas; si la copia viene vacía no se
		// borra todo por error. El tipo también se comprueba: un
		// valor del tipo equivocado haría caer al getter que lo lee.
		if (parsed.prefs.isNotEmpty()) {
			val clean = parsed.prefs.filterKeys { key ->
				val value = parsed.prefs[key]
				key != Settings.KEY_DICTIONARY_PATH &&
					key != Settings.KEY_DICTIONARY_NAME &&
					key != Settings.KEY_DICTIONARY_LIBRARY &&
					isKnownPrefKey(key) && expectedType(key) == typeOf(value) &&
					isSanePref(key, value)
			}
			// 5.4: las preferencias de diagnóstico (casilla de registro
			// activa/inactiva) viven en su propio archivo y no se
			// mezclan con los ajustes de voz. Se restauran aparte.
			val diagnosticsPrefs = clean.filterKeys { it.startsWith("diagnostics_") }
			val normalPrefs = clean - diagnosticsPrefs.keys
			if (normalPrefs.isNotEmpty()) {
				settings.restorePrefs(normalPrefs)
				// 5.1: la copia solo lleva el diccionario activo. La
				// biblioteca del aparato se queda como estaba y la
				// copia añade su diccionario arriba. 5.4.1 cambia eso
				// para las copias v3, que sí llevan la biblioteca.
				if (!parsed.libraryPresent) settings.dictionaries = libraryBefore
			}
			if (diagnosticsPrefs.isNotEmpty()) {
				Diagnostics.restorePrefs(app, diagnosticsPrefs)
			}
		}
		// 5.4.1: frecuencia y versiones del apartado de actualizaciones.
		if (parsed.updatesPresent) {
			val updates = parsed.updatePrefs.filterKeys { key ->
				val value = parsed.updatePrefs[key]
				isKnownUpdateKey(key) && updateExpectedType(key) == typeOf(value) &&
					isSaneUpdatePref(key, value)
			}
			val prefs = UpdatePrefs(app)
			prefs.restorePrefs(updates)
			// Importar una copia no es instalar una versión nueva: la
			// marca de "ya abrí esta versión" vuelve a la instalada
			// para no enseñar un registro de cambios ajeno al volver
			// a abrir la app.
			prefs.lastSeenVersionCode = BuildConfig.VERSION_CODE
		}
		val language = (parsed.prefs[Settings.KEY_DICTIONARY_LANGUAGE] as? Int)
			?.takeIf { it >= 0 } ?: settings.activeDictionaryLanguage
		if (parsed.libraryPresent) {
			restoreLibrary(parsed.dictionaries, language, settings, app, libraryBefore)
		} else {
			val bytes = parsed.dictBytes
			val ext = parsed.dictExt?.lowercase(java.util.Locale.ROOT)
			if (bytes != null && (ext == "cfd" || ext == "reloqdic") && bytes.size <= MAX_DICT_BYTES) {
				runCatching {
					val into = DirectBoot.dictionaries(app).apply { mkdirs() }
					val file = java.io.File(
						into,
						"dict-${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(8)}.$ext"
					)
					file.writeBytes(bytes)
					// Verifica que lo guardado sí parsea como diccionario.
					if (Dictionaries.entries(file).isNotEmpty() || bytes.size < 4096) {
						settings.addDictionary(
							parsed.dictName.ifEmpty { file.name },
							file.absolutePath, language, active = true
						)
					} else {
						file.delete()
					}
				}
			} else if (libraryBefore.isNotEmpty()) {
				// Sin diccionario en la copia: deja activo el de la
				// biblioteca que corresponda al idioma restaurado.
				val current = libraryBefore.lastOrNull {
					it.language == language && it.active
				} ?: libraryBefore.lastOrNull { it.language == language }
				if (current != null) settings.setActiveDictionary(current.id)
				else settings.activeDictionaryLanguage = language
			}
		}
		return ImportResult(
			app.getString(R.string.backup_ok, parsed.name.ifEmpty { "backup" }),
			settings.appLocale != beforeLocale
		)
	}

	/**
	 * 5.4.1: escribe en la carpeta propia de esta instalación todos
	 * los diccionarios de la copia y reemplaza la biblioteca entera.
	 *
	 * Las rutas del aparato de origen no sirven aquí, así que cada
	 * archivo se escribe con un nombre nuevo y la entrada se rearma
	 * con esa ruta. Después se borran los archivos de esta carpeta
	 * que la copia no traiga: si no, una copia con la biblioteca
	 * vacía dejaría los archivos viejos ocupando sitio y fuera de la
	 * biblioteca. Los diccionarios elegidos fuera de esta carpeta no
	 * se tocan; solo se reemplaza lo que la app administra.
	 */
	private fun restoreLibrary(
		source: List<BackupDictionary>,
		language: Int,
		settings: Settings,
		app: android.content.Context,
		fallback: List<Settings.DictionaryEntry>
	) {
		val into = DirectBoot.dictionaries(app).apply { mkdirs() }
		val written = HashSet<String>()
		val usedIds = HashSet<String>()
		val restored = ArrayList<Settings.DictionaryEntry>()
		for (entry in source) {
			val ext = entry.ext.lowercase(java.util.Locale.ROOT)
			if (ext != "cfd" && ext != "reloqdic") continue
			if (entry.bytes.isEmpty() || entry.bytes.size > MAX_DICT_BYTES) continue
			if (entry.language < 0) continue
			val file = java.io.File(
				into,
				"dict-${System.currentTimeMillis()}-" +
					"${java.util.UUID.randomUUID().toString().take(8)}.$ext"
			)
			val ok = runCatching {
				file.writeBytes(entry.bytes)
				// Un diccionario de verdad trae entradas. Los archivos
				// diminutos se aceptan igual porque un diccionario
				// personalizado puede tener dos o tres palabras.
				Dictionaries.entries(file).isNotEmpty() || entry.bytes.size < 4096
			}.getOrDefault(false)
			if (!ok) {
				runCatching { file.delete() }
				continue
			}
			written += file.absolutePath
			var id = entry.id.trim().take(200)
			if (id.isEmpty() || !usedIds.add(id)) {
				id = java.util.UUID.randomUUID().toString()
				usedIds.add(id)
			}
			restored += Settings.DictionaryEntry(
				id = id,
				name = entry.name.trim().take(60)
					.ifEmpty { file.nameWithoutExtension },
				language = entry.language,
				path = file.absolutePath,
				active = entry.active
			)
		}
		// Si la copia decía traer diccionarios y ninguno sirvió, no
		// se borra la biblioteca que ya había: una copia dañada no
		// debe llevarse por delante la única que funciona.
		if (source.isNotEmpty() && restored.isEmpty()) {
			settings.dictionaries = fallback
			return
		}
		// Un solo activo por idioma, como en la biblioteca normal.
		val activeLanguages = HashSet<Int>()
		val normalized = restored.map {
			if (it.language in activeLanguages) {
				it.copy(active = false)
			} else {
				if (it.active) activeLanguages += it.language
				it
			}
		}
		settings.dictionaries = normalized
		val target = normalized.firstOrNull { it.language == language && it.active }
			?: normalized.firstOrNull { it.language == language }
			?: normalized.firstOrNull { it.active }
		if (target != null) {
			settings.setActiveDictionary(target.id)
		} else {
			settings.activeDictionaryLanguage = language
			settings.dictionaryPath = null
			settings.dictionaryName = null
		}
		// Archivos de la carpeta que la copia no trajo. Se hace
		// después de escribir todo, para que un fallo a mitad no deje
		// la biblioteca apuntando a un archivo borrado.
		for (file in into.listFiles().orEmpty()) {
			if (!file.isFile || file.absolutePath in written) continue
			val ext = file.extension.lowercase(java.util.Locale.ROOT)
			if (ext == "cfd" || ext == "reloqdic") runCatching { file.delete() }
		}
	}

	/** Aplica una copia de una vez (para el "Abrir con" de fuera).
	 *  Contesta mensaje y si cambió el idioma, o nada si no era
	 *  copia válida. Fuera del hilo principal. */
	fun importFrom(
		resolver: android.content.ContentResolver,
		uri: android.net.Uri,
		settings: Settings,
		app: android.content.Context
	): ImportResult? {
		val parsed = parseBackup(resolver, uri) ?: return null
		return applyParsed(parsed, settings, app)
	}

	/** Si [uri] huele a copia: el JSON de 5.0 o la cabecera vieja. */
	fun looksLikeBackup(resolver: android.content.ContentResolver, uri: android.net.Uri): Boolean = runCatching {
		resolver.openInputStream(uri)?.use { inp ->
			val head = ByteArray(4096)
			val read = inp.read(head)
			val text = if (read <= 0) "" else String(head, 0, read, Charsets.UTF_8)
				.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
			text.startsWith(HEADER) ||
				(text.startsWith("{") && text.contains("\"format\"") && text.contains(JSON_FORMAT))
		} ?: false
	}.getOrDefault(false)

	/** Qué trae [uri]: copia, diccionario o nada reconocible.
	 *  4.0: para avisar "esto es diccionario, no copia" y al revés
	 *  en vez de un error genérico. */
	enum class Kind { BACKUP, DICT, UNKNOWN }

	fun detectKind(resolver: android.content.ContentResolver, uri: android.net.Uri): Kind = runCatching {
		if (looksLikeBackup(resolver, uri)) return Kind.BACKUP
		// Ojeada corta: si parsea como diccionario, es diccionario.
		resolver.openInputStream(uri)?.use { inp ->
			val tmp = java.io.File.createTempFile("kind", ".tmp")
			try {
				tmp.outputStream().use { out -> inp.copyTo(out) }
				val n = Dictionaries.entries(tmp).size
				if (n > 0) return Kind.DICT
			} finally {
				tmp.delete()
			}
		}
		Kind.UNKNOWN
	}.getOrDefault(Kind.UNKNOWN)

	private fun esc(raw: String): String = raw
		.replace("\\", "\\\\")
		.replace("\n", "\\n")
		.replace("|", "\\p")

	private fun unesc(raw: String): String {
		val out = StringBuilder(raw.length)
		var i = 0
		while (i < raw.length) {
			val c = raw[i]
			if (c == '\\' && i + 1 < raw.length) {
				when (raw[i + 1]) {
					'\\' -> out.append('\\')
					'n' -> out.append('\n')
					'p' -> out.append('|')
					else -> {
						out.append(c)
						out.append(raw[i + 1])
					}
				}
				i += 2
			} else {
				out.append(c)
				i++
			}
		}
		return out.toString()
	}
}
