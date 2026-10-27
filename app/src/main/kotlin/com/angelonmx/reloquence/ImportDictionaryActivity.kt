package com.angelonmx.reloquence

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Recibe archivos desde fuera ("Abrir con" y Compartir en
 *  WhatsApp, el gestor, el correo...): diccionarios .cfd y
 *  .reloqdic más copias (.reloqbkp o .json anteriores).
 *
 *  Nada se importa sin preguntar: primero se muestra una vista
 *  previa (nombre, cuántas palabras, repetidas y primeras
 *  entradas en diccionarios; contenido en copias) y solo al
 *  confirmar se aplica, igual que al importar desde dentro de
 *  la configuración. */
class ImportDictionaryActivity : ComponentActivity() {

	/**
	 * 5.3: una línea por paso de la importación. Nunca el nombre
	 * del archivo, ni las palabras del diccionario, ni una ruta:
	 * solo el tipo, los conteos y si se pudo.
	 */
	private fun diag(category: String, vararg values: Pair<String, String>) =
		Diagnostics.log(this, category, *values)

	private var working by mutableStateOf(true)
	private var mode by mutableStateOf("working")

	// Vista previa de diccionario.
	private var dictName by mutableStateOf("")
	private var dictCount by mutableStateOf(0)
	private var dictDups by mutableStateOf(0)
	private var dictNear by mutableStateOf(listOf<Pair<String, String>>())
	private var dictSample by mutableStateOf(listOf<String>())
	private var dictLabel by mutableStateOf("")
	private var dictLanguage by mutableStateOf(0)
	private var dictLanguages by mutableStateOf(listOf<Int>())

	// Vista previa de copia.
	private var backupTitle by mutableStateOf("")
	private var backupLines by mutableStateOf(listOf<String>())
	private var backupWarnings by mutableStateOf(listOf<String>())
	private var backupImportable by mutableStateOf(true)

	private var title by mutableStateOf("")
	private var message by mutableStateOf("")

	private var tmpPath: String? = null
	private var dictEntries: List<Pair<String, String>> = emptyList()
	private var dictPicked = ""
	private var dictExt = "reloqdic"
	private var pendingBackup: BackupStore.ParsedBackup? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		// Ver y Compartir: el dato viene en data o en EXTRA_STREAM.
		val shared: Uri? = if (android.os.Build.VERSION.SDK_INT >= 33) {
			intent?.getParcelableExtra(android.content.Intent.EXTRA_STREAM, Uri::class.java)
		} else {
			@Suppress("DEPRECATION")
			intent?.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
		}
		val uri = intent?.data ?: shared
		if (uri == null) {
			finish()
			return
		}
		setContent {
			MaterialTheme {
				Surface(modifier = Modifier.fillMaxSize()) {
					when (mode) {
						"working" -> {
							Column(
								modifier = Modifier.fillMaxSize().padding(24.dp),
								verticalArrangement = Arrangement.Center,
								horizontalAlignment = Alignment.CenterHorizontally
							) {
								CircularProgressIndicator()
								Text(
									text = stringResource(R.string.dict_working),
									style = MaterialTheme.typography.bodyLarge,
									modifier = Modifier.padding(top = 16.dp)
								)
							}
						}
						"dict" -> {
							AlertDialog(
								onDismissRequest = { cancelAndClose() },
								title = { Text(getString(R.string.dictionary_import)) },
								text = {
									Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
										OutlinedTextField(
											value = dictLabel,
											onValueChange = { dictLabel = it },
											label = { Text(getString(R.string.dict_export_name_label)) },
											singleLine = true,
											modifier = Modifier.fillMaxWidth()
										)
										Text(
											text = getString(R.string.dict_language_label),
											style = MaterialTheme.typography.bodyLarge,
											modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
										)
										for (language in dictLanguages) {
											TextButton(onClick = { dictLanguage = language }) {
												Text(
													(if (language == dictLanguage) "* " else "") +
														EngineLocale.displayName(this@ImportDictionaryActivity, language)
												)
											}
										}
										Text(
											getString(R.string.dictionary_entries, dictName, dictCount),
											style = MaterialTheme.typography.bodyLarge,
											modifier = Modifier.padding(vertical = 8.dp)
										)
										if (dictDups > 0) {
											Text(
												getString(R.string.dict_preview_repeated, dictDups),
												style = MaterialTheme.typography.bodyMedium,
												modifier = Modifier.padding(bottom = 8.dp)
											)
										}
										if (dictNear.isNotEmpty()) {
											Text(
												getString(R.string.near_duplicates, dictNear.size),
												style = MaterialTheme.typography.bodyMedium,
												modifier = Modifier.padding(bottom = 4.dp, top = 4.dp)
											)
											for ((a, b) in dictNear) {
												Text(
													text = getString(R.string.near_pair, a, b),
													style = MaterialTheme.typography.bodySmall,
													modifier = Modifier.padding(vertical = 1.dp)
												)
											}
										}
										for (line in dictSample) {
											Text(
												text = line,
												style = MaterialTheme.typography.bodySmall,
												modifier = Modifier.padding(vertical = 1.dp)
											)
										}
									}
								},
								confirmButton = {
									TextButton(onClick = { confirmDict() }) {
										Text(stringResource(R.string.backup_do_import))
									}
								},
								dismissButton = {
									Row {
										TextButton(onClick = { cancelAndClose() }) {
											Text(stringResource(R.string.backup_do_keep))
										}
									}
								}
							)
						}
						"backup" -> {
							AlertDialog(
								onDismissRequest = { cancelAndClose() },
								title = { Text(backupTitle) },
								text = {
									Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
										Text(
											text = stringResource(R.string.backup_preview_question),
											style = MaterialTheme.typography.bodyLarge,
											modifier = Modifier.padding(bottom = 8.dp)
										)
										for (warning in backupWarnings) {
											Text(
												text = warning,
												style = MaterialTheme.typography.bodyMedium,
												color = MaterialTheme.colorScheme.error,
												modifier = Modifier.padding(vertical = 2.dp)
											)
										}
										for (line in backupLines) {
											Text(
												text = line,
												style = MaterialTheme.typography.bodyMedium,
												modifier = Modifier.padding(vertical = 2.dp)
											)
										}
									}
								},
								confirmButton = {
									if (backupImportable) {
										TextButton(onClick = { confirmBackup() }) {
											Text(stringResource(R.string.backup_do_import))
										}
									} else {
										TextButton(onClick = { cancelAndClose() }) {
											Text(stringResource(R.string.backup_ok_button))
										}
									}
								},
								dismissButton = {
									if (backupImportable) {
										TextButton(onClick = { cancelAndClose() }) {
											Text(stringResource(R.string.backup_do_keep))
										}
									}
								}
							)
						}
						else -> {
							AlertDialog(
								onDismissRequest = { finish() },
								title = { Text(title) },
								text = { Text(message) },
								confirmButton = {
									TextButton(onClick = { finish() }) {
										Text(stringResource(R.string.backup_ok_button))
									}
								}
							)
						}
					}
				}
			}
		}
		// 4.1: corrutina atada a la actividad en vez de Thread suelto.
		lifecycleScope.launch(Dispatchers.IO) { process(uri) }
	}

	private suspend fun process(uri: Uri) {
		val appCtx = applicationContext
		val picked = displayNameOf(uri) ?: "dictionary.reloqdic"
		val isBackup = picked.substringAfterLast('.', "")
			.equals("reloqbkp", ignoreCase = true) ||
			BackupStore.looksLikeBackup(contentResolver, uri)
		diag("import", "event" to "open_with", "kind" to if (isBackup) "backup" else "dictionary")
		if (isBackup) {
			val parsed = BackupStore.parseBackup(contentResolver, uri)
			if (parsed == null) {
				// 4.0: avisa si era diccionario con extensión rara.
				val kind = BackupStore.detectKind(contentResolver, uri)
				diag("import", "event" to "backup_unreadable", "kind" to kind.name)
				if (kind == BackupStore.Kind.DICT) fail(getString(R.string.backup_is_dict), isBackup = true)
				else fail(getString(R.string.backup_error), isBackup = true)
				return
			}
			pendingBackup = parsed
			val lines = backupPreviewLines(parsed)
			val warnings = BackupStore.issueMessages(appCtx, parsed)
			diag(
				"import", "event" to "backup_preview",
				"compatible" to parsed.compatible.toString(),
				"warnings" to warnings.size.toString()
			)
			withContext(Dispatchers.Main) {
				backupTitle = getString(R.string.backup_preview_title, parsed.name.ifEmpty { "backup" })
				backupLines = lines
				backupWarnings = warnings
				backupImportable = parsed.compatible
				mode = "backup"
				working = false
			}
			return
		}
		val into = DirectBoot.dictionaries(appCtx).apply { mkdirs() }
		val tmp = java.io.File(into, "openwith-${System.nanoTime()}.tmp")
		try {
			contentResolver.openInputStream(uri).use { source ->
				if (source == null) {
					fail(getString(R.string.dict_import_error))
					return
				}
				tmp.outputStream().use { sink -> source.copyTo(sink) }
			}
		} catch (_: Exception) {
			tmp.delete()
			fail(getString(R.string.dict_import_error))
			return
		}
		val entries = Dictionaries.entries(tmp)
		if (entries.isEmpty()) {
			tmp.delete()
			// 4.0: si era copia con otro nombre, decirlo claro.
			if (BackupStore.looksLikeBackup(contentResolver, uri)) {
				fail(getString(R.string.dict_is_backup))
			} else {
				fail(getString(R.string.dict_import_error))
			}
			return
		}
		val ext = when {
			picked.substringAfterLast('.', "").equals("reloqdic", ignoreCase = true) -> "reloqdic"
			picked.substringAfterLast('.', "").equals("cfd", ignoreCase = true) -> "cfd"
			Dictionaries.sniffFormat(tmp) == "cfd" -> "cfd"
			else -> "reloqdic"
		}
		val dups = entries.groupBy { it.first }.count { it.value.size > 1 }
		val near = Dictionaries.nearDuplicates(entries)
		val sample = entries.take(8).map { (k, s) -> "$k → $s" }
		diag(
			"import", "event" to "dictionary_preview",
			"entries" to entries.size.toString(),
			"format" to ext,
			"duplicates" to dups.toString()
		)
		val settings = Settings(appCtx)
		val available = EvvEngine.available.toList()
		val current = settings.activeDictionaryLanguage
		val language = current.takeIf { it in available } ?: available.firstOrNull() ?: current
		tmpPath = tmp.absolutePath
		dictEntries = entries
		dictPicked = picked
		dictExt = ext
		val label = dictionaryLabel(picked)
		withContext(Dispatchers.Main) {
			dictName = label
			dictCount = entries.size
			dictDups = dups
			dictNear = near
			dictSample = sample
			dictLabel = label
			dictLanguage = language
			dictLanguages = available
			mode = "dict"
			working = false
		}
	}

	private fun confirmDict() {
		working = true
		mode = "working"
		lifecycleScope.launch(Dispatchers.IO) {
			val appCtx = applicationContext
			val into = DirectBoot.dictionaries(appCtx).apply { mkdirs() }
			val tmp = tmpPath?.let { java.io.File(it) }
			if (tmp == null || !tmp.exists()) {
				fail(getString(R.string.dict_import_error))
				return@launch
			}
			var entries = dictEntries
			if (dictDups > 0) {
				val seen = LinkedHashMap<String, String>()
				for ((k, s) in entries) if (!seen.containsKey(k)) seen[k] = s
				entries = seen.toList()
				runCatching {
					if (dictExt == "cfd") {
						Dictionaries.writeCfd(tmp, entries.map { (k, s) -> Dictionaries.CfdEntry(k, s) })
					} else {
						Dictionaries.writeEntries(tmp, entries, "reloqdic")
					}
				}
			}
			val label = dictLabel.trim().take(60).ifEmpty { dictionaryLabel(dictPicked) }
			val file = java.io.File(
				into,
				"dict-${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(8)}.$dictExt"
			)
			try {
				if (!tmp.renameTo(file)) {
					tmp.copyTo(file, overwrite = true)
					tmp.delete()
				}
			} catch (_: Exception) {
				tmp.delete()
				fail(getString(R.string.dict_import_error))
				return@launch
			}
			val said = appCtx.getString(R.string.dictionary_entries, label, entries.size)
			Settings(appCtx).apply {
				addDictionary(label, file.absolutePath, dictLanguage, active = true)
				// 4.3: el motor va por su cuenta y esta es otra
				// actividad, así que hay que avisarlo. Así la
				// siguiente frase ya sale con el diccionario nuevo, y
				// la pantalla de ajustes lo ve al volver, que ella
				// está atenta a [SettingsSignal].
				dictionaryRewritten("diccionario importado con Abrir con")
			}
			tmpPath = null
			withContext(Dispatchers.Main) {
				title = getString(R.string.dictionary_import)
				message = said
				mode = "done"
				working = false
				diag(
					"import", "event" to "dictionary_applied",
					"entries" to entries.size.toString(),
					"duplicates" to dictDups.toString()
				)
			}
		}
	}

	private fun confirmBackup() {
		val parsed = pendingBackup
		if (parsed == null) {
			fail(getString(R.string.backup_error))
			return
		}
		working = true
		mode = "working"
		lifecycleScope.launch(Dispatchers.IO) {
			val appCtx = applicationContext
			val settings = Settings(appCtx)
			val done = BackupStore.applyParsed(parsed, settings, appCtx)
			// 4.3: reinicio del motor y aviso a la pantalla de
			// ajustes, que va en otra actividad y se entera por
			// [SettingsSignal]. Sin esto la copia se oía bien pero
			// los deslizadores seguían enseñando lo de antes.
			settings.engineReload("copia importada con Abrir con")
			pendingBackup = null
			withContext(Dispatchers.Main) {
				title = getString(R.string.category_backup)
				message = done.message
				mode = "done"
				working = false
				diag(
					"import", "event" to "backup_applied",
					"locale_changed" to done.localeChanged.toString()
				)
			}
		}
	}

	/** Líneas de vista previa de una copia: voz, idioma, volumen,
	 *  tasa, efectos y diccionario incluido. */
	private fun backupPreviewLines(parsed: BackupStore.ParsedBackup): List<String> {
		val out = ArrayList<String>()
		val prefs = parsed.prefs
		(prefs["voice_preset"] as? Int)?.let { v ->
			out.add(getString(R.string.labelled_value,
				getString(R.string.voice_label),
				Eci.PRESET_NAMES.getOrElse(v) { "?" }))
		}
		(prefs["default_language"] as? Int)?.let { lang ->
			val name = if (lang == 0) getString(R.string.language_system)
			else EngineLocale.displayName(this, lang)
			out.add(getString(R.string.labelled_value,
				getString(R.string.language_label), name))
		}
		(prefs["volume"] as? Int)?.let { v ->
			out.add(getString(R.string.labelled_value,
				getString(R.string.volume_label), "$v%"))
		}
		(prefs["sample_rate_hz"] as? Int)?.let { hz ->
			out.add(getString(R.string.labelled_value,
				getString(R.string.sample_rate_label),
				getString(R.string.hertz, hz)))
		}
		val bytes = parsed.dictBytes
		if (bytes != null && !parsed.dictExt.isNullOrEmpty()) {
			val count = runCatching {
				val tmp = java.io.File.createTempFile("bkp", ".${parsed.dictExt}", cacheDir)
				try {
					tmp.writeBytes(bytes)
					Dictionaries.entries(tmp).size
				} finally {
					tmp.delete()
				}
			}.getOrDefault(-1)
			val label = if (count >= 0) getString(R.string.dictionary_entries, parsed.dictName.ifEmpty { "dict" }, count)
			else parsed.dictName.ifEmpty { "dict" }
			out.add(getString(R.string.labelled_value,
				getString(R.string.category_dictionary), label))
		} else {
			out.add(getString(R.string.labelled_value,
				getString(R.string.category_dictionary),
				getString(R.string.dictionary_none)))
		}
		return out
	}

	private fun cancelAndClose() {
		tmpPath?.let { java.io.File(it).delete() }
		tmpPath = null
		pendingBackup = null
		finish()
	}

	private fun fail(msg: String, isBackup: Boolean = false) {
		runOnUiThread {
			title = getString(if (isBackup) R.string.category_backup else R.string.dictionary_import)
			message = msg
			mode = "done"
			working = false
		}
	}

	private fun dictionaryLabel(picked: String): String =
		picked.substringBeforeLast('.').trim().take(60).ifEmpty { "Diccionario" }

	private fun displayNameOf(uri: Uri): String? = runCatching {
		contentResolver.query(uri, null, null, null, null)?.use { row ->
			val at = row.getColumnIndex(OpenableColumns.DISPLAY_NAME)
			if (at >= 0 && row.moveToFirst()) row.getString(at) else null
		}
	}.getOrNull()?.takeIf { it.isNotBlank() }
		?: uri.lastPathSegment?.substringAfterLast('/').takeIf { !it.isNullOrBlank() }
}
