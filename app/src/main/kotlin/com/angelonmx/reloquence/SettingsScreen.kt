package com.angelonmx.reloquence

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

/**
 * 5.2: copiar al portapapeles del sistema.
 *
 * `LocalClipboardManager` de Compose quedó obsoleto en 1.10, y su
 * sustituto (`LocalClipboard.setClipEntry`) es `suspend`: copiar
 * desde un `onClick` obligaba a lanzar una corrutina solo para
 * esto. El servicio del sistema hace lo mismo sin suspender y sin
 * API obsoleta.
 *
 * Si el sistema no da portapapeles (no debería) no se copia nada y
 * el mensaje de "copiado" saldría igual; por eso lo que importa es
 * que la copia no pueda tumbar la pantalla de ajustes.
 */
private fun copyToClipboard(ctx: android.content.Context, label: String, text: String) {
	val clip = ctx.getSystemService(android.content.ClipboardManager::class.java) ?: return
	runCatching {
		clip.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
	}
}

/**
 * Los ajustes del motor en cuatro apartados: voz, pronunciación,
 * diccionario y acerca de. Todo se elige en diálogos que se
 * aplican al momento, sin deslizadores.
 *
 * Cada fila es una sola cosa donde parar. Una etiqueta y un valor
 * son dos paradas para un lector de pantalla, así que cada fila
 * se fusiona en un solo nodo que dice lo que es y lo que tiene.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
	state: SettingsModel,
	updates: UpdatesModel,
	openUpdatesRequest: Int = 0
) {
	// 4.1: navegación real con pila (librería navigation-compose)
	// en vez de un string de estado: el botón atrás del sistema
	// vuelve al apartado anterior solo.
	val nav = rememberNavController()
	val entry by nav.currentBackStackEntryAsState()
	val route = entry?.destination?.route ?: "main"
	val title = when (route) {
		"voz" -> stringResource(R.string.category_voice)
		"pron" -> stringResource(R.string.category_reading)
		"dict" -> stringResource(R.string.category_dictionary)
		"fx" -> stringResource(R.string.category_fx)
		"history" -> stringResource(R.string.category_history)
		"backup" -> stringResource(R.string.category_backup)
		"permisos" -> stringResource(R.string.category_permisos)
		"diagnostico" -> stringResource(R.string.category_diagnostics)
		"updates" -> stringResource(R.string.updates_label)
		"about" -> stringResource(R.string.about_label)
		else -> stringResource(R.string.app_name)
	}
	val shareCtx = LocalContext.current
	androidx.compose.runtime.LaunchedEffect(state.shareTarget) {
		state.shareTarget?.let { target ->
			Share.send(shareCtx, target)
			state.clearShareTarget()
		}
	}
	// 5.4: la notificación de actualización pide abrir su apartado.
	// Al arrancar se navega una vez; con la pantalla ya abierta, cada
	// notificación incrementa el contador y vuelve a navegar.
	androidx.compose.runtime.LaunchedEffect(openUpdatesRequest) {
		if (openUpdatesRequest > 0) {
			nav.navigate("updates") { launchSingleTop = true }
			// La notificación ya avisó de que hay algo nuevo. Al abrirla
			// se comprueba otra vez para enseñar el diálogo con la
			// versión y los botones, en vez de obligar a pulsar
			// "Buscar actualizaciones" otra vez. No descarga nada.
			updates.search()
		}
	}
	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(title) },
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = MaterialTheme.colorScheme.primaryContainer,
					titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
				)
			)
		}
	) { inset ->
		NavHost(
			navController = nav,
			startDestination = "main",
			modifier = Modifier.padding(inset)
		) {
			composable("main") {
				MainScreen(onOpen = { nav.navigate(it) })
			}
			composable("voz") {
				VozScreen(state = state, onBack = { nav.popBackStack() })
			}
			composable("pron") {
				PronScreen(state = state, onBack = { nav.popBackStack() })
			}
			composable("dict") {
				DictScreen(state = state, onBack = { nav.popBackStack() })
			}
			composable("fx") {
				FxScreen(state = state, onBack = { nav.popBackStack() })
			}
			composable("history") {
				HistoryScreen(state = state, onBack = { nav.popBackStack() })
			}
			composable("backup") {
				BackupScreen(state = state, onBack = { nav.popBackStack() })
			}
			composable("permisos") {
				PermisosScreen(onBack = { nav.popBackStack() })
			}
			composable("diagnostico") {
				DiagnosticsScreen(state = state, onBack = { nav.popBackStack() })
			}
			composable("updates") {
				UpdatesScreen(updates = updates, onBack = { nav.popBackStack() })
			}
			composable("about") {
				AboutScreen(state = state, onBack = { nav.popBackStack() })
			}
		}
	}
}

/** Encabezado de apartado, igual en todos: atrás arriba a la
 *  izquierda y título en medio, pegadito a la barra de estado. */
@Composable
private fun SubHeader(title: String, onBack: () -> Unit) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		TextButton(onClick = onBack) {
			Text(stringResource(R.string.back_label))
		}
		Text(
			text = title,
			style = MaterialTheme.typography.titleLarge,
			textAlign = TextAlign.Center,
			modifier = Modifier.weight(1f).padding(end = 16.dp)
		)
	}
}

/** Título de sección: llamativo, compacto y con semántica de
 *  encabezado para lectores de pantalla. */
@Composable
private fun SectionHeader(title: String) {
	Text(
		text = title,
		style = MaterialTheme.typography.titleMedium,
		color = MaterialTheme.colorScheme.primary,
		modifier = Modifier
			.fillMaxWidth()
			.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
			.padding(horizontal = 16.dp, vertical = 10.dp)
			.clearAndSetSemantics { contentDescription = title }
	)
}

/** Las categorías, como tarjetas grandes y legibles. */
@Composable
private fun MainScreen(modifier: Modifier = Modifier, onOpen: (String) -> Unit) {
	Column(
		modifier = modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp)
	) {
		CategoryCard(
			title = stringResource(R.string.category_voice),
			subtitle = stringResource(R.string.category_voice_hint),
			icon = Icons.Filled.Mic,
			onClick = { onOpen("voz") }
		)
		CategoryCard(
			title = stringResource(R.string.category_reading),
			subtitle = stringResource(R.string.category_reading_hint),
			icon = Icons.Filled.Tune,
			onClick = { onOpen("pron") }
		)
		CategoryCard(
			title = stringResource(R.string.category_dictionary),
			subtitle = stringResource(R.string.category_dictionary_hint),
			icon = Icons.AutoMirrored.Filled.MenuBook,
			onClick = { onOpen("dict") }
		)
		CategoryCard(
			title = stringResource(R.string.category_fx),
			subtitle = stringResource(R.string.category_fx_hint),
			icon = Icons.Filled.GraphicEq,
			onClick = { onOpen("fx") }
		)
		CategoryCard(
			title = stringResource(R.string.category_history),
			subtitle = stringResource(R.string.category_history_hint),
			icon = Icons.Filled.History,
			onClick = { onOpen("history") }
		)
		CategoryCard(
			title = stringResource(R.string.category_backup),
			subtitle = stringResource(R.string.category_backup_hint),
			icon = Icons.Filled.Backup,
			onClick = { onOpen("backup") }
		)
		CategoryCard(
			title = stringResource(R.string.category_permisos),
			subtitle = stringResource(R.string.category_permisos_hint),
			icon = Icons.Filled.Security,
			onClick = { onOpen("permisos") }
		)
		CategoryCard(
			title = stringResource(R.string.category_diagnostics),
			subtitle = stringResource(R.string.category_diagnostics_hint),
			icon = Icons.Filled.BugReport,
			onClick = { onOpen("diagnostico") }
		)
		CategoryCard(
			title = stringResource(R.string.updates_label),
			subtitle = stringResource(R.string.category_updates_hint),
			icon = Icons.Filled.SystemUpdate,
			onClick = { onOpen("updates") }
		)
		CategoryCard(
			title = stringResource(R.string.about_label),
			subtitle = stringResource(R.string.about_developed_by),
			icon = Icons.Filled.Info,
			onClick = { onOpen("about") }
		)
	}
}

@Composable
private fun CategoryCard(
	title: String,
	subtitle: String,
	icon: androidx.compose.ui.graphics.vector.ImageVector,
	onClick: () -> Unit
) {
	Card(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.clearAndSetSemantics {
				contentDescription = "$title. $subtitle"
				role = Role.Button
			},
		colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
		elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
	) {
		Row(
			modifier = Modifier.padding(20.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			Icon(
				imageVector = icon,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.primary,
				modifier = Modifier.padding(end = 16.dp)
			)
			Column {
				Text(
					text = title,
					style = MaterialTheme.typography.titleLarge,
					color = MaterialTheme.colorScheme.primary
				)
				Text(
					text = subtitle,
					style = MaterialTheme.typography.bodyMedium,
					modifier = Modifier.padding(top = 4.dp)
				)
			}
		}
	}
}

/** Todo lo relacionado con la voz, por secciones. */
@Composable
private fun VozScreen(state: SettingsModel, modifier: Modifier = Modifier, onBack: () -> Unit) {
	BackHandler(onBack = onBack)
	Column(
		modifier = modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(bottom = 24.dp)
	) {
		SubHeader(stringResource(R.string.category_voice), onBack)
		SectionHeader(stringResource(R.string.section_voice))
		ChoiceRow(
			label = stringResource(R.string.voice_label),
			options = Eci.PRESET_NAMES.toList(),
			chosen = state.voice,
			onChoose = state::chooseVoice
		)
		val langs = state.languages()
		ChoiceRow(
			label = stringResource(R.string.language_label),
			options = listOf(stringResource(R.string.language_system)) + langs.map { engineName(it) },
			chosen = if (state.language == 0) 0 else langs.indexOf(state.language).coerceAtLeast(0) + 1,
			onChoose = { at -> state.chooseLanguage(if (at == 0) 0 else langs[at - 1]) }
		)
		ChoiceRow(
			label = stringResource(R.string.gender_label),
			options = listOf(
				stringResource(R.string.gender_male),
				stringResource(R.string.gender_female)
			),
			chosen = state.gender,
			onChoose = { state.setShape(Eci.VOICE_GENDER, it) }
		)
		// 4.0: perfiles de voz propios (base + forma + velocidad +
		// volumen + tasa + cuerpo + HD). Al usar uno aparece su menú.
		SectionHeader(stringResource(R.string.section_voice_profiles))
		VoiceProfileBlock(state)
		PercentRow(
			label = stringResource(R.string.speed_label),
			values = SPEED_OPTIONS,
			current = state.speedPercent(),
			systemLabel = stringResource(R.string.speed_system),
			isSystem = state.useSystemSpeed,
			onChooseSystem = { state.useSystemSpeed() },
			onChoose = { state.setSpeedPercent(SPEED_OPTIONS[it]) }
		)
		PercentRow(
			label = stringResource(R.string.pitch_label),
			values = TONE_OPTIONS,
			current = state.getVoiceParamValue(Eci.VOICE_PITCH_BASELINE),
			systemLabel = stringResource(R.string.tone_system),
			isSystem = !state.hasParamValue(Eci.VOICE_PITCH_BASELINE),
			onChooseSystem = { state.clearParam(Eci.VOICE_PITCH_BASELINE) },
			onChoose = { state.setShape(Eci.VOICE_PITCH_BASELINE, TONE_OPTIONS[it]) }
		)
		ParamRow(state, Eci.VOICE_PITCH_FLUCTUATION, stringResource(R.string.inflection_label))
		ParamRow(state, Eci.VOICE_HEAD_SIZE, stringResource(R.string.head_size_label))
		ParamRow(state, Eci.VOICE_ROUGHNESS, stringResource(R.string.roughness_label))
		ParamRow(state, Eci.VOICE_BREATHINESS, stringResource(R.string.breathiness_label))
		PercentRow(
			label = stringResource(R.string.volume_label),
			values = VOLUME_OPTIONS,
			current = state.volume,
			systemLabel = stringResource(R.string.volume_system),
			isSystem = state.useSystemVolume,
			onChooseSystem = { state.useSystemVolume() },
			onChoose = { state.chooseVolume(VOLUME_OPTIONS[it]) }
		)
		SectionHeader(stringResource(R.string.section_audio_quality))
		ChoiceRow(
			label = stringResource(R.string.sample_rate_label),
			options = SettingsModel.RATES.map { stringResource(R.string.hertz, it) },
			chosen = SettingsModel.RATES.indexOf(state.sampleRateHz).coerceAtLeast(0),
			onChoose = { state.chooseSampleRate(SettingsModel.RATES[it]) }
		)
		SwitchRow(
			label = stringResource(R.string.hd_enhance_label),
			checked = state.hdEnhance,
			onChange = state::chooseHdEnhance
		)
		SwitchRow(
			label = stringResource(R.string.body_label),
			checked = state.bodyEnabled,
			onChange = state::chooseBody
		)
		// 4.3: grave profundo, un subarmonico de verdad bajo la voz.
		ChoiceRow(
			label = stringResource(R.string.sub_bass_label),
			options = REVERB_OPTIONS.map { "$it%" },
			chosen = nearest(REVERB_OPTIONS, state.subBassMix),
			onChoose = { state.chooseSubBass(REVERB_OPTIONS[it]) }
		)
		SectionHeader(stringResource(R.string.section_mix))
		SwitchRow(
			label = stringResource(R.string.voice_mix_label),
			checked = state.mixEnabled,
			onChange = state::chooseMixEnabled
		)
		ChoiceRow(
			label = stringResource(R.string.voice_mix_partner),
			options = Eci.PRESET_NAMES.toList(),
			chosen = state.mixPartner,
			onChoose = state::chooseMixPartner
		)
		ChoiceRow(
			label = stringResource(R.string.mix_interval_label),
			options = MIX_WORDS.map { stringResource(R.string.mix_words_value, it) },
			chosen = nearest(MIX_WORDS, state.mixInterval),
			onChoose = { state.chooseMixInterval(MIX_WORDS[it]) }
		)
		SectionHeader(stringResource(R.string.voice_try_label))
		var testText by remember { mutableStateOf("") }
		OutlinedTextField(
			value = testText,
			onValueChange = { testText = it },
			label = { Text(stringResource(R.string.voice_try_hint)) },
			minLines = 2,
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 4.dp)
		)
		ActionRow(stringResource(R.string.voice_try_speak), {
			state.sayCustom(testText)
		})
		// 4.0: muestra qué haría la detección con este texto
		// (heurística instantánea, igual que la prueba).
		Text(
			text = state.describeDetection(testText),
			style = MaterialTheme.typography.bodyMedium,
			modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
		)
		Row(
			modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
			horizontalArrangement = Arrangement.End
		) {
			TextButton(onClick = { state.resetVoiceCategory() }) {
				Text(stringResource(R.string.reset_category))
			}
		}
	}
}

/** Un parámetro de voz de 0 a 100 como diálogo. */
@Composable
private fun ParamRow(state: SettingsModel, param: Int, label: String) {
	val current = state.getVoiceParamValue(param)
	PercentRow(
		label = label,
		values = PCT_0_100,
		current = current,
		onChoose = { state.setShape(param, PCT_0_100[it]) }
	)
}

/** Perfiles de voz: elegir, añadir, importar y menú del propio
 *  (compartir, eliminar, renombrar). Lo que se edita después en
 *  deslizadores vuelve el perfil a manual. */
@Composable
private fun VoiceProfileBlock(state: SettingsModel) {
	val ctx = LocalContext.current
	var saving by remember { mutableStateOf(false) }
	var deleting by remember { mutableStateOf<String?>(null) }
	var renaming by remember { mutableStateOf(false) }
	var options by remember { mutableStateOf(false) }
	var msg by remember { mutableStateOf<String?>(null) }
	var voicePreview by remember { mutableStateOf<Pair<String, Settings.VoicePatch>?>(null) }
	var voiceWrong by remember { mutableStateOf<Pair<String, String>?>(null) }
	// 4.3: los de fábrica se ofrecen junto a los del usuario, como ya
	// se hacía con los de efectos. Van detrás y no se pueden borrar ni
	// renombrar, que para eso no están en los ajustes: son una receta
	// de fábrica, no algo que el usuario posea.
	val names = state.voiceProfiles.keys.sorted()
	val builtin = SettingsModel.BUILTIN_VOICE.keys.sorted()
	val all = listOf(stringResource(R.string.voice_profile_manual)) + names + builtin
	val active = state.voiceProfile
	ChoiceRow(
		label = stringResource(R.string.voice_profile_label),
		options = all,
		chosen = if (active == null) 0 else all.indexOf(active).coerceAtLeast(0),
		onChoose = { at -> if (at > 0) state.applyVoiceProfile(all[at]) }
	)
	// Las opciones (exportar, renombrar, borrar) son solo de las del
	// usuario: un perfil de fábrica no se renombra ni se borra.
	if (active != null && active in names) {
		ActionRow(stringResource(R.string.voice_profile_options), { options = true })
	}
	ActionRow(stringResource(R.string.voice_profile_add), { saving = true })
	val importPick = rememberLauncherForActivityResult(
		ActivityResultContracts.OpenDocument()
	) { uri ->
		if (uri != null) {
			val json = runCatching {
				ctx.contentResolver.openInputStream(uri)?.use {
					it.readBytes().toString(Charsets.UTF_8)
				}
			}.getOrNull().orEmpty()
			// 4.1: antiformato + vista previa en todos los casos.
			when (SettingsModel.detectProfileKind(json)) {
				"voice" -> {
					val parsed = state.parseVoiceProfileJson(json)
					if (parsed != null) voicePreview = parsed
					else voiceWrong = ctx.getString(R.string.kind_unknown) to
						ctx.getString(R.string.cat_voice)
				}
				"fx" -> voiceWrong = ctx.getString(R.string.kind_fx) to
					ctx.getString(R.string.cat_voice)
				"backup" -> voiceWrong = ctx.getString(R.string.kind_backup) to
					ctx.getString(R.string.cat_backup)
				"dict" -> voiceWrong = ctx.getString(R.string.kind_dict) to
					ctx.getString(R.string.cat_dict)
				else -> voiceWrong = ctx.getString(R.string.kind_unknown) to
					ctx.getString(R.string.cat_voice)
			}
		}
	}
	ActionRow(stringResource(R.string.voice_profile_import), { importPick.launch(arrayOf("*/*")) })
	msg?.let {
		Text(
			text = it,
			style = MaterialTheme.typography.bodyMedium,
			modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
		)
	}
	if (saving) {
		var name by remember { mutableStateOf("") }
		AlertDialog(
			onDismissRequest = { saving = false },
			title = { Text(stringResource(R.string.voice_profile_add)) },
			text = {
				OutlinedTextField(
					value = name,
					onValueChange = { name = it },
					label = { Text(stringResource(R.string.fx_profile_name)) },
					singleLine = true
				)
			},
			confirmButton = {
				TextButton(onClick = { if (state.saveVoiceProfile(name)) saving = false }) {
					Text(stringResource(R.string.save))
				}
			},
			dismissButton = {
				TextButton(onClick = { saving = false }) {
					Text(stringResource(R.string.cancel))
				}
			}
		)
	}
	if (options) {
		val name = state.voiceProfile
		AlertDialog(
			onDismissRequest = { options = false },
			title = { Text(stringResource(R.string.voice_profile_options)) },
			text = {
				Column {
					TextButton(onClick = {
						options = false
						val json = name?.let { state.exportVoiceProfileJson(it) }
						if (json != null) {
							state.enqueueShare(
								"profiles", "$name.reloqvc", "application/json",
								json.toByteArray(Charsets.UTF_8)
							)
						} else {
							msg = ctx.getString(R.string.voice_profile_error)
						}
					}) { Text(stringResource(R.string.fx_share_export)) }
					TextButton(onClick = {
						val json = name?.let { state.exportVoiceProfileJson(it) }
						if (json != null) {
							copyToClipboard(ctx, "reloquence", json)
							msg = ctx.getString(R.string.fx_share_copied)
						}
						options = false
					}) { Text(stringResource(R.string.fx_share_copy)) }
					TextButton(onClick = {
						options = false
						if (name != null) deleting = name
					}) { Text(stringResource(R.string.voice_profile_delete)) }
					TextButton(onClick = {
						options = false
						renaming = true
					}) { Text(stringResource(R.string.voice_profile_rename)) }
				}
			},
			confirmButton = {
				TextButton(onClick = { options = false }) {
					Text(stringResource(R.string.cancel))
				}
			}
		)
	}
	deleting?.let { name ->
		AlertDialog(
			onDismissRequest = { deleting = null },
			title = { Text(stringResource(R.string.voice_profile_delete)) },
			text = { Text(stringResource(R.string.voice_profile_delete_confirm, name)) },
			confirmButton = {
				TextButton(onClick = {
					state.deleteVoiceProfile(name)
					deleting = null
				}) { Text(stringResource(R.string.delete)) }
			},
			dismissButton = {
				TextButton(onClick = { deleting = null }) {
					Text(stringResource(R.string.cancel))
				}
			}
		)
	}
	if (renaming) {
		val name = state.voiceProfile
		var next by remember(name) { mutableStateOf(name ?: "") }
		AlertDialog(
			onDismissRequest = { renaming = false },
			title = { Text(stringResource(R.string.voice_profile_rename)) },
			text = {
				OutlinedTextField(
					value = next,
					onValueChange = { next = it },
					label = { Text(stringResource(R.string.fx_profile_name)) },
					singleLine = true
				)
			},
			confirmButton = {
				TextButton(onClick = {
					if (name != null && state.renameVoiceProfile(name, next)) renaming = false
				}) { Text(stringResource(R.string.save)) }
			},
			dismissButton = {
				TextButton(onClick = { renaming = false }) {
					Text(stringResource(R.string.cancel))
				}
			}
		)
	}
	voicePreview?.let { (name, patch) ->
		AlertDialog(
			onDismissRequest = { voicePreview = null },
			title = { Text(name) },
			text = {
				Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
					Text(
						text = stringResource(R.string.import_preview_question),
						style = MaterialTheme.typography.bodyMedium,
						modifier = Modifier.padding(bottom = 8.dp)
					)
					for (line in describeVoicePatch(patch)) {
						Text(
							text = line,
							style = MaterialTheme.typography.bodySmall,
							modifier = Modifier.padding(vertical = 1.dp)
						)
					}
				}
			},
			confirmButton = {
				TextButton(onClick = {
					val saved = state.adoptVoiceProfile(name, patch)
					msg = if (saved != null) ctx.getString(R.string.voice_profile_imported, saved)
					else ctx.getString(R.string.voice_profile_error)
					voicePreview = null
				}) { Text(stringResource(R.string.backup_do_import)) }
			},
			dismissButton = {
				TextButton(onClick = { voicePreview = null }) {
					Text(stringResource(R.string.backup_do_keep))
				}
			}
		)
	}
	voiceWrong?.let { (actual, expected) ->
		AlertDialog(
			onDismissRequest = { voiceWrong = null },
			title = { Text(stringResource(R.string.import_wrong_title)) },
			text = { Text(stringResource(R.string.import_wrong_msg, actual, expected)) },
			confirmButton = {
				TextButton(onClick = { voiceWrong = null }) {
					Text(stringResource(R.string.backup_ok_button))
				}
			}
		)
	}
}

/** Cómo se pronuncia: pausas, abreviaturas y detección. */
@Composable
private fun PronScreen(state: SettingsModel, modifier: Modifier = Modifier, onBack: () -> Unit) {
	BackHandler(onBack = onBack)
	Column(
		modifier = modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(bottom = 24.dp)
	) {
		SubHeader(stringResource(R.string.category_reading), onBack)
		SectionHeader(stringResource(R.string.section_reading))
		ChoiceRow(
			label = stringResource(R.string.pauses_label),
			options = listOf(
				stringResource(R.string.pauses_keep),
				stringResource(R.string.pauses_end),
				stringResource(R.string.pauses_all)
			),
			chosen = state.pauses,
			onChoose = state::choosePauses
		)
		ChoiceRow(
			label = stringResource(R.string.pause_duration_label),
			options = PAUSE_OPTIONS.map { stringResource(R.string.milliseconds, it) },
			chosen = nearest(PAUSE_OPTIONS, state.pauseDurationMs),
			onChoose = { state.choosePauseDuration(PAUSE_OPTIONS[it]) }
		)
		SwitchRow(
			label = stringResource(R.string.abbreviations_label),
			checked = state.abbreviations,
			onChange = state::chooseAbbreviations
		)
		// 4.3: apagar los códigos de voz. Va aquí y no en la
		// sección de sonido porque no es un efecto: es si el texto
		// puede darle órdenes al motor.
		SwitchRow(
			label = stringResource(R.string.ignore_voice_tags_label),
			checked = state.ignoreVoiceTags,
			onChange = state::chooseIgnoreVoiceTags
		)
		if (state.ignoreVoiceTags) {
			Text(
				text = stringResource(R.string.ignore_voice_tags_hint),
				style = MaterialTheme.typography.bodyMedium,
				modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
			)
		} else {
			// Con el interruptor apagado, esto es lo que se oye: la
			// voz salta a media frase y se queda ahí. Da algo que
			// escuchar en vez de describir lo que hace un acento
			// grave, que un lector de pantalla no lee bien.
			val ctx = LocalContext.current
			ActionRow(stringResource(R.string.voice_tags_example_label), {
				state.sayCustom(ctx.getString(R.string.voice_tags_example, "`v4 "))
			})
		}
		SwitchRow(
			label = stringResource(R.string.autodetect_label),
			checked = state.autoDetectLanguage,
			onChange = state::chooseAutoDetectLanguage
		)
		// 4.0: qué variante suena por idioma detectado (los que
		// traen más de una: inglés, español, francés).
		var autoVariants by remember { mutableStateOf(false) }
		ActionRow(stringResource(R.string.autodetect_variants), { autoVariants = true })
		if (autoVariants) {
			val groups = remember {
				EvvEngine.available.toList()
					.mapNotNull { engine -> Eci.localeOf(engine)?.first?.let { it to engine } }
					.groupBy({ it.first }, { it.second })
					.filter { it.value.size > 1 }
			}
			AlertDialog(
				onDismissRequest = { autoVariants = false },
				title = { Text(stringResource(R.string.autodetect_variants)) },
				text = {
					Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
						for ((tag, engines) in groups) {
							val current = when (tag) {
								"eng" -> state.autoVariantEn
								"spa" -> state.autoVariantEs
								else -> state.autoVariantFr
							}.takeIf { it in engines } ?: engines.first()
							ChoiceRow(
								label = stringResource(
									R.string.autodetect_variant_for,
									java.util.Locale.forLanguageTag(tag).displayLanguage
								),
								options = engines.map { engineName(it) },
								chosen = engines.indexOf(current).coerceAtLeast(0),
								onChoose = { at -> state.chooseAutoVariant(tag, engines[at]) }
							)
						}
					}
				},
				confirmButton = {
					TextButton(onClick = { autoVariants = false }) {
						Text(stringResource(R.string.backup_ok_button))
					}
				}
			)
		}
		SwitchRow(
			label = stringResource(R.string.skip_parens_label),
			checked = state.skipParentheses,
			onChange = state::chooseSkipParentheses
		)
		SectionHeader(stringResource(R.string.section_numbers))
		SwitchRow(
			label = stringResource(R.string.number_force_label),
			checked = state.numberForceEnabled,
			onChange = state::chooseNumberForce
		)
		if (state.numberForceEnabled) {
			val groupOptions = listOf(
				stringResource(R.string.number_group_1),
				stringResource(R.string.number_group_2),
				stringResource(R.string.number_group_3),
				stringResource(R.string.number_group_n, 4),
				stringResource(R.string.number_group_n, 5),
				stringResource(R.string.number_group_n, 6),
				stringResource(R.string.number_group_n, 7),
				stringResource(R.string.number_group_n, 8),
				stringResource(R.string.number_group_n, 9),
				stringResource(R.string.number_group_n, 10),
				stringResource(R.string.number_corrido)
			)
			ChoiceRow(
				label = stringResource(R.string.number_group_label),
				options = groupOptions,
				chosen = if (state.numberGroup == Numbers.CORRIDO) 10 else (state.numberGroup - 1).coerceIn(0, 9),
				onChoose = { at -> state.chooseNumberGroup(if (at == 10) Numbers.CORRIDO else at + 1) }
			)
		}
		SectionHeader(stringResource(R.string.section_punctuation))
		SwitchRow(
			label = stringResource(R.string.emoji_label),
			checked = state.processEmojis,
			onChange = state::chooseProcessEmojis
		)
		SwitchRow(
			label = stringResource(R.string.unicode_symbols_label),
			checked = state.readUnicodeSymbols,
			onChange = state::chooseReadUnicodeSymbols
		)
		SwitchRow(
			label = stringResource(R.string.punct_process_label),
			checked = state.punctEnabled,
			onChange = state::choosePunctEnabled
		)
		if (state.punctEnabled) {
			ChoiceRow(
				label = stringResource(R.string.punct_level_label),
				options = listOf(
					stringResource(R.string.punct_all),
					stringResource(R.string.punct_some),
					stringResource(R.string.punct_none)
				),
				chosen = state.punctLevel,
				onChoose = state::choosePunctLevel
			)
		}
		Row(
			modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
			horizontalArrangement = Arrangement.End
		) {
			TextButton(onClick = { state.resetReadingCategory() }) {
				Text(stringResource(R.string.reset_category))
			}
		}
	}
}

/** Efectos de audio por secciones: ecualizador, ambiente,
 *  dinámica, modulación y perfiles. */
@Composable
private fun FxScreen(state: SettingsModel, modifier: Modifier = Modifier, onBack: () -> Unit) {
	BackHandler(onBack = onBack)
	var saving by remember { mutableStateOf(false) }
	var deleting by remember { mutableStateOf<String?>(null) }
	var renaming by remember { mutableStateOf(false) }
	var profileOptions by remember { mutableStateOf(false) }
	var fxPreview by remember { mutableStateOf<Pair<String, Settings.FxPatch>?>(null) }
	var fxWrong by remember { mutableStateOf<Pair<String, String>?>(null) }
	// Lanzadores y mensajes a nivel de pantalla (los diálogos de
	// abajo viven fuera de la columna y los necesitan también).
	val ctx = LocalContext.current
	var shareMsg by remember { mutableStateOf<String?>(null) }
	val importPick = rememberLauncherForActivityResult(
		ActivityResultContracts.OpenDocument()
	) { uri ->
		if (uri != null) {
			val json = runCatching {
				ctx.contentResolver.openInputStream(uri)?.use {
					it.readBytes().toString(Charsets.UTF_8)
				}
			}.getOrNull().orEmpty()
			// 4.1: antiformato + vista previa en todos los casos.
			when (SettingsModel.detectProfileKind(json)) {
				"fx" -> {
					val parsed = state.parseFxProfileJson(json)
					if (parsed != null) fxPreview = parsed
					else fxWrong = ctx.getString(R.string.kind_unknown) to
						ctx.getString(R.string.cat_fx)
				}
				"voice" -> fxWrong = ctx.getString(R.string.kind_voice) to
					ctx.getString(R.string.cat_fx)
				"backup" -> fxWrong = ctx.getString(R.string.kind_backup) to
					ctx.getString(R.string.cat_backup)
				"dict" -> fxWrong = ctx.getString(R.string.kind_dict) to
					ctx.getString(R.string.cat_dict)
				else -> fxWrong = ctx.getString(R.string.kind_unknown) to
					ctx.getString(R.string.cat_fx)
			}
		}
	}
	Column(
		modifier = modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(bottom = 24.dp)
	) {
		SubHeader(stringResource(R.string.category_fx), onBack)
		SectionHeader(stringResource(R.string.section_eq))
		SwitchRow(
			label = stringResource(R.string.eq_label),
			checked = state.eqEnabled,
			onChange = state::chooseEqEnabled
		)
		// 4.3: los parametros de un efecto apagado no se ensenan. El
		// valor no se pierde, se queda escrito y vuelve a salir al
		// encenderlo. Los graves y la presencia van con el ecualizador
		// porque sin el no se oyen: los usa el mismo interruptor.
		if (state.eqEnabled) {
			state.eqBands.forEachIndexed { band, gain ->
				ChoiceRow(
					label = "${AudioFx.EQ_FREQS[band]} Hz",
					options = EQ_DB_OPTIONS.map { stringResource(R.string.decibels, it) },
					chosen = nearest(EQ_DB_OPTIONS, gain),
					onChoose = { state.chooseEqBand(band, EQ_DB_OPTIONS[it]) }
				)
			}
			ChoiceRow(
				label = stringResource(R.string.bass_label),
				options = EQ_DB_OPTIONS.map { stringResource(R.string.decibels, it) },
				chosen = nearest(EQ_DB_OPTIONS, state.bassDb),
				onChoose = { state.chooseBass(EQ_DB_OPTIONS[it]) }
			)
			ChoiceRow(
				label = stringResource(R.string.presence_label),
				options = EQ_DB_OPTIONS.map { stringResource(R.string.decibels, it) },
				chosen = nearest(EQ_DB_OPTIONS, state.presenceDb),
				onChoose = { state.choosePresence(EQ_DB_OPTIONS[it]) }
			)
		}
		SwitchRow(
			label = stringResource(R.string.highpass_label),
			checked = state.highpassEnabled,
			onChange = state::chooseHighpass
		)
		ChoiceRow(
			label = stringResource(R.string.exciter_label),
			options = REVERB_OPTIONS.map { "$it%" },
			chosen = nearest(REVERB_OPTIONS, state.exciterLevel),
			onChoose = { state.chooseExciter(REVERB_OPTIONS[it]) }
		)
		SwitchRow(
			label = stringResource(R.string.warmth_label),
			checked = state.warmthEnabled,
			onChange = state::chooseWarmth
		)
		SwitchRow(
			label = stringResource(R.string.deesser_label),
			checked = state.deesserEnabled,
			onChange = state::chooseDeesser
		)
		if (state.deesserEnabled) {
			// 4.3: cuanto quita, en vez de todo o nada.
			ChoiceRow(
				label = stringResource(R.string.deesser_depth_label),
				options = REVERB_OPTIONS.map { "$it%" },
				chosen = nearest(REVERB_OPTIONS, state.deesserDepth),
				onChoose = { state.chooseDeesserDepth(REVERB_OPTIONS[it]) }
			)
		}
		SwitchRow(
			label = stringResource(R.string.hd_enhance_label),
			checked = state.hdEnhance,
			onChange = state::chooseHdEnhance
		)
		SectionHeader(stringResource(R.string.section_space))
		SwitchRow(
			label = stringResource(R.string.reverb_label),
			checked = state.reverbEnabled,
			onChange = state::chooseReverbEnabled
		)
		if (state.reverbEnabled) {
			// 4.3: el tamano de la sala. Solo suena con la
			// reverberacion encendida.
			ChoiceRow(
				label = stringResource(R.string.reverb_size_label),
				options = REVERB_OPTIONS.map { "$it%" },
				chosen = nearest(REVERB_OPTIONS, state.reverbSize),
				onChoose = { state.chooseReverbSize(REVERB_OPTIONS[it]) }
			)
			ChoiceRow(
				label = stringResource(R.string.reverb_level_label),
				options = REVERB_OPTIONS.map { "$it%" },
				chosen = nearest(REVERB_OPTIONS, state.reverbLevel),
				onChoose = { state.chooseReverbLevel(REVERB_OPTIONS[it]) }
			)
		}
		SwitchRow(
			label = stringResource(R.string.echo_label),
			checked = state.echoEnabled,
			onChange = state::chooseEchoEnabled
		)
		if (state.echoEnabled) {
			ChoiceRow(
				label = stringResource(R.string.echo_level_label),
				options = REVERB_OPTIONS.map { "$it%" },
				chosen = nearest(REVERB_OPTIONS, state.echoLevel),
				onChoose = { state.chooseEchoLevel(REVERB_OPTIONS[it]) }
			)
			ChoiceRow(
				label = stringResource(R.string.echo_time_label),
				options = ECHO_TIMES.map { stringResource(R.string.milliseconds, it) },
				chosen = nearest(ECHO_TIMES, state.echoTimeMs),
				onChoose = { state.chooseEchoTime(ECHO_TIMES[it]) }
			)
		}
		SectionHeader(stringResource(R.string.section_dynamics))
		SwitchRow(
			label = stringResource(R.string.compressor_label),
			checked = state.compEnabled,
			onChange = state::chooseCompressor
		)
		if (state.compEnabled) {
			ChoiceRow(
				label = stringResource(R.string.boost_label),
				options = BOOST_OPTIONS.map { "$it%" },
				chosen = nearest(BOOST_OPTIONS, state.boostPercent),
				onChoose = { state.chooseBoost(BOOST_OPTIONS[it]) }
			)
		}
		SwitchRow(
			label = stringResource(R.string.gate_label),
			checked = state.gateEnabled,
			onChange = state::chooseGate
		)
		SwitchRow(
			label = stringResource(R.string.normalizer_label),
			checked = state.normalizerEnabled,
			onChange = state::chooseNormalizer
		)
		SectionHeader(stringResource(R.string.section_modulation))
		ChoiceRow(
			label = stringResource(R.string.tremolo_label),
			options = REVERB_OPTIONS.map { "$it%" },
			chosen = nearest(REVERB_OPTIONS, state.tremoloLevel),
			onChoose = { state.chooseTremolo(REVERB_OPTIONS[it]) }
		)
		if (state.tremoloLevel > 0) {
			ChoiceRow(
				label = stringResource(R.string.tremolo_rate_label),
				options = TREMOLO_RATES.map { tremoloHz(it) },
				chosen = nearest(TREMOLO_RATES, state.tremoloRate),
				onChoose = { state.chooseTremoloRate(TREMOLO_RATES[it]) }
			)
		}
		SwitchRow(
			label = stringResource(R.string.chorus_label),
			checked = state.chorusEnabled,
			onChange = state::chooseChorus
		)
		SwitchRow(
			label = stringResource(R.string.crusher_label),
			checked = state.crusherEnabled,
			onChange = state::chooseCrusher
		)
		if (state.crusherEnabled) {
			// 4.3: cuantos bits conserva el lofi.
			ChoiceRow(
				label = stringResource(R.string.crusher_bits_label),
				options = BITS_OPTIONS.map { "$it" },
				chosen = nearest(BITS_OPTIONS, state.crusherBits),
				onChoose = { state.chooseCrusherBits(BITS_OPTIONS[it]) }
			)
		}
		SwitchRow(
			label = stringResource(R.string.flanger_label),
			checked = state.flangerEnabled,
			onChange = state::chooseFlanger
		)
		SwitchRow(
			label = stringResource(R.string.phaser_label),
			checked = state.phaserEnabled,
			onChange = state::choosePhaser
		)
		ChoiceRow(
			label = stringResource(R.string.vibrato_label),
			options = REVERB_OPTIONS.map { "$it%" },
			chosen = nearest(REVERB_OPTIONS, state.vibratoLevel),
			onChoose = { state.chooseVibrato(REVERB_OPTIONS[it]) }
		)
		ChoiceRow(
			label = stringResource(R.string.ringmod_label),
			options = REVERB_OPTIONS.map { "$it%" },
			chosen = nearest(REVERB_OPTIONS, state.ringmodLevel),
			onChoose = { state.chooseRingmod(REVERB_OPTIONS[it]) }
		)
		ChoiceRow(
			label = stringResource(R.string.fuzz_label),
			options = REVERB_OPTIONS.map { "$it%" },
			chosen = nearest(REVERB_OPTIONS, state.fuzzLevel),
			onChoose = { state.chooseFuzz(REVERB_OPTIONS[it]) }
		)
		ChoiceRow(
			label = stringResource(R.string.wah_label),
			options = REVERB_OPTIONS.map { "$it%" },
			chosen = nearest(REVERB_OPTIONS, state.wahLevel),
			onChoose = { state.chooseWah(REVERB_OPTIONS[it]) }
		)
		ChoiceRow(
			label = stringResource(R.string.maximizer_label),
			options = REVERB_OPTIONS.map { "$it%" },
			chosen = nearest(REVERB_OPTIONS, state.maximizerLevel),
			onChoose = { state.chooseMaximizer(REVERB_OPTIONS[it]) }
		)
		// 4.3: cinta, que tambien mueve el tiempo, aqui con el
		// resto de la modulacion.
		ChoiceRow(
			label = stringResource(R.string.tape_label),
			options = REVERB_OPTIONS.map { "$it%" },
			chosen = nearest(REVERB_OPTIONS, state.tapeMix),
			onChoose = { state.chooseTape(REVERB_OPTIONS[it]) }
		)
		SwitchRow(
			label = stringResource(R.string.telephone_label),
			checked = state.telephoneEnabled,
			onChange = state::chooseTelephone
		)
		SwitchRow(
			label = stringResource(R.string.drive_label),
			checked = state.driveEnabled,
			onChange = state::chooseDrive
		)
		SectionHeader(stringResource(R.string.section_profiles))
		val customs = state.fxProfiles.keys.sorted()
		val builtins = SettingsModel.BUILTIN_FX.keys.sorted()
		val all = listOf(stringResource(R.string.fx_profile_manual)) + builtins + customs
		val active = state.fxProfile
		ChoiceRow(
			label = stringResource(R.string.fx_profile_label),
			options = all,
			chosen = if (active == null) 0 else all.indexOf(active).coerceAtLeast(0),
			onChoose = { at -> if (at > 0) state.applyFxProfile(all[at]) }
		)
		// 5.1: los perfiles de fábrica también enseñan opciones.
		// Compartir, renombrar y borrar siguen siendo solo de los
		// propios; guardar está siempre disponible.
		if (active != null) {
			ActionRow(stringResource(R.string.fx_profile_options), { profileOptions = true })
		}
		if (active == null) {
			ActionRow(stringResource(R.string.fx_profile_save), { saving = true })
		}
		ActionRow(stringResource(R.string.fx_share_import), { importPick.launch(arrayOf("*/*")) })
		shareMsg?.let { msg ->
			Text(
				text = msg,
				style = MaterialTheme.typography.bodyMedium,
				modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
			)
		}
		ActionRow(stringResource(R.string.fx_off_all), state::resetFx)
		Row(
			modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
			horizontalArrangement = Arrangement.End
		) {
			TextButton(onClick = { state.resetFxCategory() }) {
				Text(stringResource(R.string.reset_category))
			}
		}
	}
	if (saving) {
		var name by remember { mutableStateOf("") }
		AlertDialog(
			onDismissRequest = { saving = false },
			title = { Text(stringResource(R.string.fx_profile_save)) },
			text = {
				OutlinedTextField(
					value = name,
					onValueChange = { name = it },
					label = { Text(stringResource(R.string.fx_profile_name)) },
					singleLine = true
				)
			},
			confirmButton = {
				TextButton(onClick = {
					if (state.saveFxProfile(name)) {
						saving = false
						profileOptions = true
					}
				}) { Text(stringResource(R.string.save)) }
			},
			dismissButton = {
				TextButton(onClick = { saving = false }) {
					Text(stringResource(R.string.cancel))
				}
			}
		)
	}
	deleting?.let { name ->
		AlertDialog(
			onDismissRequest = { deleting = null },
			title = { Text(stringResource(R.string.fx_profile_delete)) },
			text = { Text(stringResource(R.string.fx_profile_delete_confirm, name)) },
			confirmButton = {
				TextButton(onClick = {
					state.deleteFxProfile(name)
					deleting = null
				}) { Text(stringResource(R.string.delete)) }
			},
			dismissButton = {
				TextButton(onClick = { deleting = null }) {
					Text(stringResource(R.string.cancel))
				}
			}
		)
	}
	fxPreview?.let { (name, patch) ->
		AlertDialog(
			onDismissRequest = { fxPreview = null },
			title = { Text(name) },
			text = {
				Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
					Text(
						text = stringResource(R.string.import_preview_question),
						style = MaterialTheme.typography.bodyMedium,
						modifier = Modifier.padding(bottom = 8.dp)
					)
					for (line in describeFxPatch(patch)) {
						Text(
							text = line,
							style = MaterialTheme.typography.bodySmall,
							modifier = Modifier.padding(vertical = 1.dp)
						)
					}
				}
			},
			confirmButton = {
				TextButton(onClick = {
					val saved = state.adoptFxProfile(name, patch)
					shareMsg = if (saved != null) ctx.getString(R.string.fx_share_imported, saved)
					else ctx.getString(R.string.fx_share_error)
					fxPreview = null
				}) { Text(stringResource(R.string.backup_do_import)) }
			},
			dismissButton = {
				TextButton(onClick = { fxPreview = null }) {
					Text(stringResource(R.string.backup_do_keep))
				}
			}
		)
	}
	fxWrong?.let { (actual, expected) ->
		AlertDialog(
			onDismissRequest = { fxWrong = null },
			title = { Text(stringResource(R.string.import_wrong_title)) },
			text = { Text(stringResource(R.string.import_wrong_msg, actual, expected)) },
			confirmButton = {
				TextButton(onClick = { fxWrong = null }) {
					Text(stringResource(R.string.backup_ok_button))
				}
			}
		)
	}
	if (profileOptions) {
		val name = state.fxProfile
		AlertDialog(
			onDismissRequest = { profileOptions = false },
			title = { Text(stringResource(R.string.fx_profile_options)) },
			text = {
				Column {
					TextButton(onClick = {
						profileOptions = false
						saving = true
					}) { Text(stringResource(R.string.fx_profile_save)) }
					if (name != null && name in state.fxProfiles) {
						TextButton(onClick = {
							profileOptions = false
							renaming = true
						}) { Text(stringResource(R.string.fx_profile_rename)) }
						TextButton(onClick = {
							profileOptions = false
							val json = state.exportProfileJson(name)
							if (json != null) {
								state.enqueueShare(
									"profiles", "$name.reloqfxp", "application/json",
									json.toByteArray(Charsets.UTF_8)
								)
							} else {
								shareMsg = ctx.getString(R.string.fx_share_error)
							}
						}) { Text(stringResource(R.string.fx_share_export)) }
						TextButton(onClick = {
							val json = state.exportProfileJson(name)
							if (json != null) {
								copyToClipboard(ctx, "reloquence", json)
								shareMsg = ctx.getString(R.string.fx_share_copied)
							}
							profileOptions = false
						}) { Text(stringResource(R.string.fx_share_copy)) }
						TextButton(onClick = {
							profileOptions = false
							deleting = name
						}) { Text(stringResource(R.string.fx_profile_delete)) }
					}
				}
			},
			confirmButton = {
				TextButton(onClick = { profileOptions = false }) {
					Text(stringResource(R.string.cancel))
				}
			}
		)
	}
	if (renaming) {
		val name = state.fxProfile
		var next by remember(name) { mutableStateOf(name ?: "") }
		AlertDialog(
			onDismissRequest = { renaming = false },
			title = { Text(stringResource(R.string.fx_profile_rename)) },
			text = {
				OutlinedTextField(
					value = next,
					onValueChange = { next = it },
					label = { Text(stringResource(R.string.fx_profile_name)) },
					singleLine = true
				)
			},
			confirmButton = {
				TextButton(onClick = {
					if (name != null && state.renameFxProfile(name, next)) renaming = false
				}) { Text(stringResource(R.string.save)) }
			},
			dismissButton = {
				TextButton(onClick = { renaming = false }) {
					Text(stringResource(R.string.cancel))
				}
			}
		)
	}
}

/** Historial: lo último que el lector pidió decir, lo más
 *  reciente primero. Tocar repite con la voz en vigor. */
@Composable
private fun HistoryScreen(state: SettingsModel, modifier: Modifier = Modifier, onBack: () -> Unit) {
	BackHandler(onBack = onBack)
	androidx.compose.runtime.LaunchedEffect(Unit) { state.refreshHistory() }
	Column(modifier = modifier.fillMaxSize()) {
		SubHeader(stringResource(R.string.category_history), onBack)
		if (state.history.isEmpty()) {
			Text(
				text = stringResource(R.string.history_empty),
				style = MaterialTheme.typography.bodyLarge,
				modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
			)
		} else {
			LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
				itemsIndexed(
					items = state.history,
					contentType = { _, _ -> "utterance" }
				) { _, text ->
					ValueRow(
						label = text,
						value = stringResource(R.string.history_chars, text.length),
						onClick = { state.replayHistory(text) }
					)
				}
			}
		}
		Row(
			modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
			horizontalArrangement = Arrangement.Start
		) {
			TextButton(onClick = { state.clearHistory() }) {
				Text(stringResource(R.string.history_clear))
			}
			TextButton(onClick = { state.speakHistoryAll() }) {
				Text(stringResource(R.string.history_speak_all))
			}
		}
	}
}

/** Copia de seguridad (.reloqbkp): todo lo ajustado más el
 *  diccionario. Al exportar se pide el nombre y luego la carpeta;
 *  al importar se aplica y deja todo en vigor. */
@Composable
private fun BackupScreen(state: SettingsModel, modifier: Modifier = Modifier, onBack: () -> Unit) {
	BackHandler(onBack = onBack)
	val ctx = LocalContext.current
	val importPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		uri?.let { state.importBackup(it) }
	}
	var naming by remember { mutableStateOf(false) }
	var resetting by remember { mutableStateOf(false) }
	Column(
		modifier = modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(bottom = 24.dp)
	) {
		SubHeader(stringResource(R.string.category_backup), onBack)
		if (state.backupBusy) {
			Text(
				text = stringResource(R.string.dict_working),
				style = MaterialTheme.typography.bodyLarge,
				modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
			)
		}
		ActionRow(
			stringResource(R.string.backup_export),
			{ if (!state.backupBusy) naming = true }
		)
		ActionRow(
			stringResource(R.string.backup_import),
			{ if (!state.backupBusy) importPick.launch(arrayOf("*/*")) }
		)
		ActionRow(
			stringResource(R.string.backup_reset),
			{ if (!state.backupBusy) resetting = true }
		)
	}
	if (resetting) {
		AlertDialog(
			onDismissRequest = { resetting = false },
			title = { Text(stringResource(R.string.backup_reset)) },
			text = { Text(stringResource(R.string.backup_reset_confirm)) },
			confirmButton = {
				TextButton(onClick = {
					state.resetAll()
					resetting = false
				}) { Text(stringResource(R.string.delete)) }
			},
			dismissButton = {
				TextButton(onClick = { resetting = false }) {
					Text(stringResource(R.string.cancel))
				}
			}
		)
	}
	if (naming) {
		var name by remember { mutableStateOf(state.defaultBackupName()) }
		AlertDialog(
			onDismissRequest = { naming = false },
			title = { Text(stringResource(R.string.backup_name_title)) },
			text = {
				OutlinedTextField(
					value = name,
					onValueChange = { name = it },
					label = { Text(stringResource(R.string.backup_name_label)) },
					singleLine = true
				)
			},
			confirmButton = {
				TextButton(onClick = {
					val clean = name.trim().ifEmpty { state.defaultBackupName() }
						.replace(Regex("[/\\\\:*?\"<>|]"), "_")
					naming = false
					state.shareBackup(clean.removeSuffix(".${BackupStore.EXTENSION}"))
				}) { Text(stringResource(R.string.save)) }
			},
			dismissButton = {
				TextButton(onClick = { naming = false }) {
					Text(stringResource(R.string.cancel))
				}
			}
		)
	}
	state.pendingBackup?.let { info ->
		AlertDialog(
			onDismissRequest = { state.cancelPendingBackup() },
			title = { Text(stringResource(R.string.backup_preview_title, info.name)) },
			text = {
				Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
					Text(
						text = stringResource(R.string.backup_preview_question),
						style = MaterialTheme.typography.bodyLarge,
						modifier = Modifier.padding(bottom = 8.dp)
					)
					for (warning in info.warnings) {
						Text(
							text = warning,
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.error,
							modifier = Modifier.padding(vertical = 2.dp)
						)
					}
					for (line in info.lines) {
						Text(
							text = line,
							style = MaterialTheme.typography.bodyMedium,
							modifier = Modifier.padding(vertical = 2.dp)
						)
					}
				}
			},
			confirmButton = {
				if (info.importable) {
					TextButton(onClick = { state.confirmPendingBackup() }) {
						Text(stringResource(R.string.backup_do_import))
					}
				} else {
					TextButton(onClick = { state.cancelPendingBackup() }) {
						Text(stringResource(R.string.backup_ok_button))
					}
				}
			},
			dismissButton = {
				if (info.importable) {
					TextButton(onClick = { state.cancelPendingBackup() }) {
						Text(stringResource(R.string.backup_do_keep))
					}
				}
			}
		)
	}
	state.backupResult?.let { msg ->
		AlertDialog(
			onDismissRequest = { state.clearBackupResult() },
			title = { Text(stringResource(R.string.category_backup)) },
			text = { Text(msg) },
			confirmButton = {
				TextButton(onClick = {
					val restart = state.backupNeedsRestart
					state.clearBackupResult()
					if (restart) (ctx as? android.app.Activity)?.recreate()
				}) { Text(stringResource(R.string.backup_ok_button)) }
			},
			dismissButton = {
				if (state.backupCanShare) {
					TextButton(onClick = {
						state.shareLastBackup(ctx)?.let { uri ->
							ctx.startActivity(
								android.content.Intent.createChooser(
									android.content.Intent(android.content.Intent.ACTION_SEND)
										.setType("application/json")
										.putExtra(android.content.Intent.EXTRA_STREAM, uri)
										.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION),
									null
								)
							)
						}
					}) { Text(stringResource(R.string.backup_share)) }
				}
			}
		)
	}
}

/** Permisos que mantienen vivo el servicio: batería sin
 *  optimizar, notificaciones y mostrarse sobre otras apps. Cada
 *  fila dice su estado y abre su propio apartado de ajustes. */
@Composable
private fun PermisosScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
	BackHandler(onBack = onBack)
	val ctx = LocalContext.current
	var battery by remember { mutableStateOf(true) }
	var notif by remember { mutableStateOf(true) }
	var overlay by remember { mutableStateOf(true) }
	fun reload() {
		battery = AppPermissions.batteryIgnored(ctx)
		notif = AppPermissions.notificationsOn(ctx)
		overlay = AppPermissions.overlayOn(ctx)
	}
	androidx.compose.runtime.LaunchedEffect(Unit) { reload() }
	// El estado se relee al volver (tras conceder en ajustes): sin
	// esto habría que tocar "Actualizar estado" a mano.
	val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
	androidx.compose.runtime.DisposableEffect(owner) {
		val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
			if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) reload()
		}
		owner.lifecycle.addObserver(obs)
		onDispose { owner.lifecycle.removeObserver(obs) }
	}
	val notifAsk = rememberLauncherForActivityResult(
		ActivityResultContracts.RequestPermission()
	) { reload() }
	Column(
		modifier = modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(bottom = 24.dp)
	) {
		SubHeader(stringResource(R.string.category_permisos), onBack)
		ValueRow(
			label = stringResource(R.string.perm_battery),
			value = stringResource(if (battery) R.string.perm_granted else R.string.perm_missing),
			onClick = {
				if (!battery) AppPermissions.open(ctx, notifAsk, AppPermissions.BATTERY)
				reload()
			}
		)
		ValueRow(
			label = stringResource(R.string.perm_notifications),
			value = stringResource(if (notif) R.string.perm_granted else R.string.perm_missing),
			onClick = {
				if (!notif) AppPermissions.open(ctx, notifAsk, AppPermissions.NOTIFICATIONS)
				reload()
			}
		)
		ValueRow(
			label = stringResource(R.string.perm_overlay),
			value = stringResource(if (overlay) R.string.perm_granted else R.string.perm_missing),
			onClick = {
				if (!overlay) AppPermissions.open(ctx, notifAsk, AppPermissions.OVERLAY)
				reload()
			}
		)
	}
}

/**
 * 5.2: el registro de errores.
 *
 * Está apagado de fábrica y se enciende solo cuando hace falta. El
 * texto de recomendación se enseña siempre, no solo al encenderlo:
 * es la explicación de para qué sirve y de cómo usarlo sin que
 * ocupe espacio de más.
 */
@Composable
private fun DiagnosticsScreen(state: SettingsModel, modifier: Modifier = Modifier, onBack: () -> Unit) {
	BackHandler(onBack = onBack)
	var clearing by remember { mutableStateOf(false) }
	Column(
		modifier = modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(bottom = 24.dp)
	) {
		SubHeader(stringResource(R.string.category_diagnostics), onBack)
		SectionHeader(stringResource(R.string.section_diagnostics))
		SwitchRow(
			label = stringResource(R.string.diagnostics_enable),
			checked = state.diagnosticsEnabled,
			onChange = state::chooseDiagnostics
		)
		Text(
			text = stringResource(R.string.diagnostics_recommendation),
			style = MaterialTheme.typography.bodyMedium,
			modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
		)
		ActionRow(
			stringResource(R.string.diagnostics_export),
			{ state.exportDiagnostics() }
		)
		ActionRow(
			stringResource(R.string.diagnostics_clear),
			{ clearing = true }
		)
	}
	if (clearing) {
		AlertDialog(
			onDismissRequest = { clearing = false },
			title = { Text(stringResource(R.string.diagnostics_clear)) },
			text = { Text(stringResource(R.string.diagnostics_clear_confirm)) },
			confirmButton = {
				TextButton(onClick = {
					state.clearDiagnostics()
					clearing = false
				}) { Text(stringResource(R.string.delete)) }
			},
			dismissButton = {
				TextButton(onClick = { clearing = false }) {
					Text(stringResource(R.string.cancel))
				}
			}
		)
	}
}

/** El diccionario: buscar, palabras, importar, exportar, añadir,
 *  editar, borrado múltiple y orden. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DictScreen(state: SettingsModel, modifier: Modifier = Modifier, onBack: () -> Unit) {
	BackHandler(onBack = onBack)
	val dictCtx = LocalContext.current
	val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		uri?.let { state.chooseDictionary(it) }
	}
	var exportFormat by remember { mutableStateOf<String?>(null) }
	var exportName by remember { mutableStateOf("diccionario") }
	var adding by remember { mutableStateOf(false) }
	var addingBulk by remember { mutableStateOf(false) }
	var bulkDone by remember { mutableStateOf<Int?>(null) }
	var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
	var clearing by remember { mutableStateOf(false) }
	var moreOptions by remember { mutableStateOf(false) }
	var libraryOpen by remember { mutableStateOf(false) }
	var renamingId by remember { mutableStateOf<String?>(null) }
	var deletingId by remember { mutableStateOf<String?>(null) }
	var removingDupes by remember { mutableStateOf(false) }
	var ordering by remember { mutableStateOf(false) }
	var selecting by remember { mutableStateOf(false) }
	var confirmingDelete by remember { mutableStateOf(false) }
	var query by remember { mutableStateOf("") }
	val selected = remember { mutableStateSetOf<String>() }
	val displayed = remember(query, state.listedEntries) {
		if (query.isBlank()) state.listedEntries
		else state.listedEntries.filter {
			it.first.contains(query, ignoreCase = true) || it.second.contains(query, ignoreCase = true)
		}
	}
	Column(
		modifier = modifier.fillMaxSize()
	) {
		// Arriba, todo en una barra: atrás a la izquierda, título
		// con el diccionario en uso, buscador y más opciones a la
		// derecha. En medio, la lista ocupa toda la pantalla.
		Row(
			modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp, end = 4.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			TextButton(onClick = onBack) {
				Text(stringResource(R.string.back_label))
			}
			val rawName = state.dictionary.substringBefore(",").ifBlank { state.dictionary }
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = stringResource(R.string.category_dictionary),
					style = MaterialTheme.typography.titleMedium
				)
				Text(
					text = stringResource(R.string.dict_in_use, rawName, displayed.size),
					style = MaterialTheme.typography.bodySmall
				)
			}
			OutlinedTextField(
				value = query,
				onValueChange = { query = it },
				label = { Text(stringResource(R.string.dict_search)) },
				singleLine = true,
				modifier = Modifier.weight(1.2f).padding(end = 4.dp)
			)
			TextButton(onClick = { moreOptions = true }) {
				Text(stringResource(R.string.dict_more_options))
			}
		}
		state.dictError?.let { err ->
			Text(
				text = err,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.error,
				modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
			)
		}
		Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
			LazyColumn(modifier = Modifier.fillMaxSize()) {
		if (selecting) {
			item {
				Text(
					text = stringResource(R.string.dict_selected_n, selected.size),
					style = MaterialTheme.typography.bodyLarge,
					modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
				)
			}
			item {
				ActionRow(stringResource(R.string.dict_delete_selected), { confirmingDelete = true })
			}
			item {
				ActionRow(stringResource(R.string.dict_done), {
					selecting = false
					selected.clear()
				})
			}
		}
		if (state.dictLoading || state.dictBusy) {
			item {
				Text(
					text = stringResource(R.string.dict_working),
					style = MaterialTheme.typography.bodyLarge,
					modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
				)
			}
		}
		itemsIndexed(
			items = displayed,
			key = { at, entry -> "$at:${entry.first}" },
			contentType = { _, _ -> "word" }
		) { _, entry ->
			val (word, say) = entry
			if (selecting) {
				SelectRow(
					label = word,
					value = say,
					checked = selected.contains(word),
					onToggle = { if (!selected.add(word)) selected.remove(word) }
				)
			} else {
				ValueRow(
					label = word,
					value = say,
					onClick = { editing = word to say }
				)
			}
		}
			}
		}
		// Abajo, de izquierda a derecha: vaciar todo, exportar,
		// importar y añadir palabra.
		Row(
			modifier = Modifier.fillMaxWidth()
				.horizontalScroll(rememberScrollState())
				.padding(horizontal = 8.dp, vertical = 4.dp),
			horizontalArrangement = Arrangement.spacedBy(4.dp)
		) {
			TextButton(onClick = { libraryOpen = true }) {
				Text(stringResource(R.string.dict_library))
			}
			TextButton(onClick = { clearing = true }) {
				Text(stringResource(R.string.dictionary_clear))
			}
			val busy = state.dictBusy
			TextButton(onClick = {
				if (!busy) {
					exportName = state.dictionary.substringBefore(",").ifBlank { "diccionario" }.take(60)
					exportFormat = "ask"
				}
			}) {
				Text(if (busy) stringResource(R.string.dict_working) else stringResource(R.string.dictionary_export))
			}
			TextButton(onClick = { if (!busy) pick.launch(arrayOf("*/*")) }) {
				Text(if (busy) stringResource(R.string.dict_working) else stringResource(R.string.dictionary_import))
			}
			TextButton(onClick = { adding = true }) {
				Text(stringResource(R.string.word_add))
			}
		}
	}
	if (moreOptions) {
		AlertDialog(
			onDismissRequest = { moreOptions = false },
			title = { Text(stringResource(R.string.dict_more_options)) },
			text = {
				Column {
					TextButton(onClick = {
						selecting = true
						selected.clear()
						moreOptions = false
					}) { Text(stringResource(R.string.dict_select_multi)) }
					TextButton(onClick = {
						ordering = true
						moreOptions = false
					}) { Text(stringResource(R.string.dict_order)) }
					TextButton(onClick = {
						addingBulk = true
						moreOptions = false
					}) { Text(stringResource(R.string.dict_add_many)) }
					// 4.2: quitar repetidas del diccionario en uso,
					// escribiendo el archivo, para compartirlo limpio
					// sin tener que volver a importarlo.
					if (state.duplicateCount() > 0) {
						TextButton(onClick = {
							removingDupes = true
							moreOptions = false
						}) { Text(stringResource(R.string.dict_dupes)) }
					}
				}
			},
			confirmButton = {
				TextButton(onClick = { moreOptions = false }) {
					Text(stringResource(R.string.cancel))
				}
			}
		)
	}
	if (removingDupes) {
		val count = state.duplicateCount()
		AlertDialog(
			onDismissRequest = { removingDupes = false },
			title = { Text(stringResource(R.string.dict_dupes_title)) },
			text = { Text(stringResource(R.string.dict_dupes_confirm, count)) },
			confirmButton = {
				TextButton(onClick = {
					state.removeDuplicates()
					removingDupes = false
				}) { Text(stringResource(R.string.save)) }
			},
			dismissButton = {
				TextButton(onClick = { removingDupes = false }) {
					Text(stringResource(R.string.cancel))
				}
			}
		)
	}
	// 4.2: lo que salió al quitar repetidas, como el aviso de la
	// copia de seguridad.
	state.dictNotice?.let { notice ->
		AlertDialog(
			onDismissRequest = { state.clearDictNotice() },
			title = { Text(stringResource(R.string.dict_dupes_title)) },
			text = { Text(notice) },
			confirmButton = {
				TextButton(onClick = { state.clearDictNotice() }) {
					Text(stringResource(R.string.backup_ok_button))
				}
			}
		)
	}
	if (ordering) {
		val orderLabels = listOf(
			stringResource(R.string.dict_order_file),
			stringResource(R.string.dict_order_alpha),
			stringResource(R.string.dict_order_long),
			stringResource(R.string.dict_order_short)
		)
		AlertDialog(
			onDismissRequest = { ordering = false },
			title = { Text(stringResource(R.string.dict_order)) },
			text = {
				Column {
					orderLabels.forEachIndexed { at, label ->
						TextButton(onClick = {
							state.chooseDictOrder(at)
							ordering = false
						}) {
							Text(
								if (at == state.dictOrder) "• $label"
								else label
							)
						}
					}
				}
			},
			confirmButton = {
				TextButton(onClick = { ordering = false }) {
					Text(stringResource(R.string.cancel))
				}
			}
		)
	}
	if (confirmingDelete) {
		AlertDialog(
			onDismissRequest = { confirmingDelete = false },
			title = { Text(stringResource(R.string.dict_delete_selected)) },
			text = { Text(stringResource(R.string.dict_selected_n, selected.size)) },
			confirmButton = {
				TextButton(onClick = {
					state.removeEntries(selected.toSet())
					selected.clear()
					selecting = false
					confirmingDelete = false
				}) { Text(stringResource(R.string.word_delete)) }
			},
			dismissButton = {
				TextButton(onClick = { confirmingDelete = false }) {
					Text(stringResource(R.string.cancel))
				}
			}
		)
	}
	if (clearing) {
		AlertDialog(
			onDismissRequest = { clearing = false },
			title = { Text(stringResource(R.string.dictionary_clear)) },
			text = { Text(stringResource(R.string.dictionary_clear_confirm)) },
			confirmButton = {
				TextButton(onClick = {
					state.clearAllDictionary()
					clearing = false
				}) { Text(stringResource(R.string.dictionary_clear_empty)) }
			},
			dismissButton = {
				TextButton(onClick = { clearing = false }) {
					Text(stringResource(R.string.cancel))
				}
			}
		)
	}
	if (exportFormat != null) {
		AlertDialog(
			onDismissRequest = { exportFormat = null },
			title = { Text(stringResource(R.string.dictionary_export)) },
			text = {
				Column {
					OutlinedTextField(
						value = exportName,
						onValueChange = { exportName = it },
						label = { Text(stringResource(R.string.dict_export_name_label)) },
						singleLine = true
					)
					TextButton(onClick = {
						val clean = exportName.trim()
							.ifBlank { state.defaultDictName() }
							.replace(Regex("[/\\\\:*?\"<>|]"), "_")
						exportName = clean
						exportFormat = null
						state.exportDictionary("cfd", clean)
					}) { Text(stringResource(R.string.format_cfd)) }
					TextButton(onClick = {
						val clean = exportName.trim()
							.ifBlank { state.defaultDictName() }
							.replace(Regex("[/\\\\:*?\"<>|]"), "_")
						exportName = clean
						exportFormat = null
						state.exportDictionary("reloqdic", clean)
					}) { Text(stringResource(R.string.format_reloqdic)) }
				}
			},
			confirmButton = {
				TextButton(onClick = { exportFormat = null }) {
					Text(stringResource(R.string.cancel))
				}
			}
		)
	}
	state.dictResult?.let { msg ->
		AlertDialog(
			onDismissRequest = { state.clearDictResult() },
			title = { Text(stringResource(R.string.category_dictionary)) },
			text = { Text(msg) },
			confirmButton = {
				TextButton(onClick = { state.clearDictResult() }) {
					Text(stringResource(R.string.backup_ok_button))
				}
			}
		)
	}
	state.pendingImport?.let { pending ->
		val available = EvvEngine.available.toList()
		var importName by remember(pending.tmpPath) {
			mutableStateOf(pending.suggestedName)
		}
		var importLanguage by remember(pending.tmpPath) {
			mutableStateOf(
				pending.language.takeIf { it in available }
					?: available.firstOrNull() ?: pending.language
			)
		}
		AlertDialog(
			onDismissRequest = { state.cancelPendingImport() },
			title = { Text(stringResource(R.string.dictionary_import)) },
			text = {
				Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
					Text(
						text = pending.pickedName,
						style = MaterialTheme.typography.bodySmall,
						modifier = Modifier.padding(bottom = 8.dp)
					)
					OutlinedTextField(
						value = importName,
						onValueChange = { importName = it },
						label = { Text(stringResource(R.string.dict_export_name_label)) },
						singleLine = true,
						modifier = Modifier.fillMaxWidth()
					)
					Text(
						text = stringResource(R.string.dict_language_label),
						style = MaterialTheme.typography.bodyLarge,
						modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
					)
					for (language in available) {
						TextButton(onClick = { importLanguage = language }) {
							Text(
								(if (language == importLanguage) "• " else "") +
									EngineLocale.displayName(dictCtx, language)
							)
						}
					}
					if (pending.duplicates > 0) {
						Text(
							text = stringResource(R.string.duplicates_message, pending.duplicates),
							style = MaterialTheme.typography.bodyMedium,
							modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
						)
					}
					// 4.2: vista previa, como la de la copia de
					// seguridad: cuántas entradas trae, en qué formato
					// y las primeras, para confirmar que se eligió el
					// archivo correcto.
					if (pending.preview.isNotEmpty()) {
						Text(
							text = stringResource(R.string.dict_preview_title, pending.pickedName),
							style = MaterialTheme.typography.titleSmall,
							modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
						)
						for (line in pending.preview) {
							Text(
								text = line,
								style = MaterialTheme.typography.bodySmall,
								modifier = Modifier.padding(vertical = 1.dp)
							)
						}
					}
					if (pending.near.isNotEmpty()) {
						Text(
							text = stringResource(R.string.near_duplicates, pending.near.size),
							style = MaterialTheme.typography.bodyMedium,
							modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
						)
						for ((a, b) in pending.near) {
							Text(
								text = stringResource(R.string.near_pair, a, b),
								style = MaterialTheme.typography.bodySmall,
								modifier = Modifier.padding(vertical = 1.dp)
							)
						}
					}
				}
			},
			confirmButton = {
				TextButton(onClick = {
					state.finishPendingImport(true, importLanguage, importName)
				}) { Text(stringResource(R.string.backup_do_import)) }
			},
			dismissButton = {
				Row {
					if (pending.groups.isNotEmpty()) {
						TextButton(onClick = {
							state.setPendingImportTarget(importLanguage, importName)
							state.beginResolvingOneByOne()
						}) { Text(stringResource(R.string.duplicates_one_by_one)) }
					}
					TextButton(onClick = { state.cancelPendingImport() }) {
						Text(stringResource(R.string.cancel))
					}
				}
			}
		)
	}
	if (libraryOpen) {
		AlertDialog(
			onDismissRequest = { libraryOpen = false },
			title = { Text(stringResource(R.string.dict_library)) },
			text = {
				Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
					if (state.dictionaryLibrary.isEmpty()) {
						Text(stringResource(R.string.dict_library_empty))
					}
					for (entry in state.dictionaryLibrary) {
						Column(modifier = Modifier.padding(vertical = 6.dp)) {
							Text(
								text = entry.name +
									if (entry.active) "  • ${stringResource(R.string.dict_active)}" else "",
								style = MaterialTheme.typography.bodyLarge
							)
							Text(
								text = EngineLocale.displayName(dictCtx, entry.language),
								style = MaterialTheme.typography.bodySmall
							)
							Row {
								if (!entry.active) {
									TextButton(onClick = { state.selectDictionary(entry.id) }) {
										Text(stringResource(R.string.dict_activate))
									}
								}
								TextButton(onClick = {
									libraryOpen = false
									renamingId = entry.id
								}) { Text(stringResource(R.string.fx_profile_rename)) }
								TextButton(onClick = {
									libraryOpen = false
									deletingId = entry.id
								}) { Text(stringResource(R.string.delete)) }
							}
						}
					}
				}
			},
			confirmButton = {
				TextButton(onClick = { libraryOpen = false }) {
					Text(stringResource(R.string.backup_ok_button))
				}
			},
			dismissButton = {
				TextButton(onClick = {
					libraryOpen = false
					pick.launch(arrayOf("*/*"))
				}) { Text(stringResource(R.string.dict_new)) }
			}
		)
	}
	renamingId?.let { id ->
		val entry = state.dictionaryLibrary.firstOrNull { it.id == id }
		var name by remember(id) { mutableStateOf(entry?.name.orEmpty()) }
		AlertDialog(
			onDismissRequest = { renamingId = null },
			title = { Text(stringResource(R.string.fx_profile_rename)) },
			text = {
				OutlinedTextField(
					value = name,
					onValueChange = { name = it },
					label = { Text(stringResource(R.string.dict_export_name_label)) },
					singleLine = true
				)
			},
			confirmButton = {
				TextButton(onClick = {
					if (state.renameDictionary(id, name)) renamingId = null
				}) { Text(stringResource(R.string.save)) }
			},
			dismissButton = {
				TextButton(onClick = { renamingId = null }) {
					Text(stringResource(R.string.cancel))
				}
			}
		)
	}
	deletingId?.let { id ->
		val entry = state.dictionaryLibrary.firstOrNull { it.id == id }
		AlertDialog(
			onDismissRequest = { deletingId = null },
			title = { Text(stringResource(R.string.dictionary_remove)) },
			text = { Text(entry?.name.orEmpty()) },
			confirmButton = {
				TextButton(onClick = {
					state.deleteDictionary(id)
					deletingId = null
				}) { Text(stringResource(R.string.delete)) }
			},
			dismissButton = {
				TextButton(onClick = { deletingId = null }) {
					Text(stringResource(R.string.cancel))
				}
			}
		)
	}
	// 4.2: la pantalla de una repetida. La palabra arriba, debajo
	// las pronunciaciones que hay en juego, cada una con su botón.
	// Al elegir una se pasa sola a la siguiente repetida, y al
	// terminar se importa.
	if (state.resolvingIndex >= 0) {
		val pending = state.pendingImport
		val group = pending?.groups?.getOrNull(state.resolvingIndex)
		if (pending != null && group != null) {
			val options = LinkedHashSet<String>()
			options.addAll(group.current)
			options.addAll(group.incoming)
			AlertDialog(
				onDismissRequest = { state.cancelPendingImport() },
				title = {
					Column {
						Text(stringResource(R.string.dup_pick_title))
						Text(
							text = stringResource(
								R.string.dup_pick_progress,
								state.resolvingIndex + 1, pending.groups.size
							),
							style = MaterialTheme.typography.bodySmall
						)
					}
				},
				text = {
					Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
						Text(
							text = stringResource(R.string.dup_pick_question, group.key),
							style = MaterialTheme.typography.bodyLarge,
							modifier = Modifier.padding(bottom = 8.dp)
						)
						for (say in options) {
							// De dónde viene cada opción, para que
							// la elección sea informada y no a ciegas.
							val fromFile = group.incoming.contains(say)
							val fromDict = group.current.contains(say)
							val tag = when {
								fromFile && fromDict -> stringResource(
									R.string.dup_both
								)
								fromFile -> stringResource(R.string.dup_from_file)
								else -> stringResource(R.string.dup_from_dict)
							}
							TextButton(
								onClick = { state.pickDuplicate(say) },
								modifier = Modifier.fillMaxWidth()
							) {
								Column(modifier = Modifier.fillMaxWidth()) {
									Text(text = say)
									Text(
										text = tag,
										style = MaterialTheme.typography.bodySmall
									)
								}
							}
						}
					}
				},
				confirmButton = {
					TextButton(onClick = { state.skipDuplicate() }) {
						Text(stringResource(R.string.dup_skip))
					}
				},
				dismissButton = {
					TextButton(onClick = { state.cancelPendingImport() }) {
						Text(stringResource(R.string.cancel))
					}
				}
			)
		}
	}
	if (adding) {
		WordDialog(
			title = stringResource(R.string.word_add),
			initialWord = "",
			initialSay = "",
			onDismiss = { adding = false },
			onSave = { w, s ->
				state.addWord(w, s).also { if (it) adding = false }
			},
			onDelete = null
		)
	}
	if (addingBulk) {
		var bulk by remember { mutableStateOf("") }
		AlertDialog(
			onDismissRequest = { addingBulk = false },
			title = { Text(stringResource(R.string.dict_add_many)) },
			text = {
				OutlinedTextField(
					value = bulk,
					onValueChange = { bulk = it },
					label = { Text(stringResource(R.string.dict_add_many_hint)) },
					minLines = 5,
					modifier = Modifier.fillMaxWidth()
				)
			},
			confirmButton = {
				TextButton(onClick = {
					bulkDone = state.addWordsBulk(bulk)
					addingBulk = false
				}) { Text(stringResource(R.string.word_save)) }
			},
			dismissButton = {
				TextButton(onClick = { addingBulk = false }) {
					Text(stringResource(R.string.cancel))
				}
			}
		)
	}
	bulkDone?.let { n ->
		AlertDialog(
			onDismissRequest = { bulkDone = null },
			title = { Text(stringResource(R.string.dict_add_many)) },
			text = { Text(stringResource(R.string.dict_added_n, n)) },
			confirmButton = {
				TextButton(onClick = { bulkDone = null }) {
					Text(stringResource(R.string.backup_ok_button))
				}
			}
		)
	}
	editing?.let { (word, say) ->
		WordDialog(
			title = stringResource(R.string.word_edit),
			initialWord = word,
			initialSay = say,
			onDismiss = { editing = null },
			onSave = { w, s ->
				state.editWord(word, w, s).also { if (it) editing = null }
			},
			onDelete = {
				state.removeEntry(word)
				editing = null
			}
		)
	}
}

/** Diálogo para añadir o editar una palabra u oración. Si no se
 *  puede guardar (vacío o larguísimo) se dice en el propio
 *  diálogo en vez de no pasar nada. */
@Composable
private fun WordDialog(
	title: String,
	initialWord: String,
	initialSay: String,
	onDismiss: () -> Unit,
	onSave: (String, String) -> Boolean,
	onDelete: (() -> Unit)?
) {
	var word by remember(initialWord) { mutableStateOf(initialWord) }
	var say by remember(initialSay) { mutableStateOf(initialSay) }
	var saveFailed by remember(initialWord, initialSay) { mutableStateOf(false) }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(title) },
		text = {
			Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
				OutlinedTextField(
					value = word,
					onValueChange = { word = it; saveFailed = false },
					label = { Text(stringResource(R.string.word_label)) },
					minLines = 2
				)
				OutlinedTextField(
					value = say,
					onValueChange = { say = it; saveFailed = false },
					label = { Text(stringResource(R.string.pronunciation_label)) },
					minLines = 3
				)
				if (saveFailed) {
					Text(
						text = stringResource(R.string.word_save_error),
						color = MaterialTheme.colorScheme.error,
						style = MaterialTheme.typography.bodyMedium,
						modifier = Modifier.padding(top = 8.dp)
					)
				}
			}
		},
		confirmButton = {
			TextButton(onClick = { saveFailed = !onSave(word, say) }) {
				Text(stringResource(R.string.word_save))
			}
		},
		dismissButton = {
			Row {
				if (onDelete != null) {
					TextButton(onClick = onDelete) {
						Text(stringResource(R.string.word_delete))
					}
				}
				TextButton(onClick = onDismiss) {
					Text(stringResource(R.string.cancel))
				}
			}
		}
	)
}

/**
 * 5.4: el apartado de actualizaciones.
 *
 * El orden es el que se pidió: buscar, ver los cambios de la versión
 * actual, la versión, el código y el interruptor de comprobación
 * automática. Debajo van la frecuencia, la última comprobación y lo
 * que haya pasado.
 *
 * La descarga y la instalación nunca empiezan solas: la
 * comprobación automática solo deja una notificación.
 */
@Composable
private fun UpdatesScreen(
	updates: UpdatesModel,
	modifier: Modifier = Modifier,
	onBack: () -> Unit
) {
	BackHandler(onBack = onBack)
	val lastCheck = remember(updates.lastCheckAt) {
		if (updates.lastCheckAt <= 0L) {
			null
		} else {
			java.text.DateFormat.getDateTimeInstance(
				java.text.DateFormat.MEDIUM,
				java.text.DateFormat.SHORT
			).format(java.util.Date(updates.lastCheckAt))
		}
	}
	Column(
		modifier = modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(bottom = 24.dp)
	) {
		SubHeader(stringResource(R.string.updates_label), onBack)
		SectionHeader(stringResource(R.string.updates_label))
		ActionRow(stringResource(R.string.update_check_now), updates::search)
		ActionRow(stringResource(R.string.update_current_changelog), updates::openChangelog)
		InfoRow(
			stringResource(R.string.update_current_version),
			com.angelonmx.reloquence.BuildConfig.VERSION_NAME
		)
		InfoRow(
			stringResource(R.string.update_current_code),
			com.angelonmx.reloquence.BuildConfig.VERSION_CODE.toString()
		)
		SwitchRow(
			label = stringResource(R.string.update_auto_check),
			checked = updates.autoCheck,
			onChange = updates::changeAutoCheck
		)
		if (updates.autoCheck) {
			val intervals = UpdatePrefs.INTERVALS
			ChoiceRow(
				label = stringResource(R.string.update_interval),
				options = intervals.map {
					stringResource(R.string.update_interval_hours, it)
				},
				chosen = intervals.indexOf(updates.intervalHours).coerceAtLeast(0),
				onChoose = { updates.setInterval(intervals[it]) }
			)
		}
		InfoRow(
			stringResource(R.string.update_last_check),
			lastCheck ?: stringResource(R.string.update_never_checked)
		)
		val statusText = updateStatusText(updates.status)
		if (statusText.isNotEmpty()) {
			Text(
				text = statusText,
				style = MaterialTheme.typography.bodyLarge,
				color = if (updates.status is UpdateStatus.Failed) {
					MaterialTheme.colorScheme.error
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp, vertical = 12.dp)
			)
		}
		if (updates.available != null && !updates.busy) {
			ActionRow(stringResource(R.string.update_install_now), updates::install)
		}
	}

	if (updates.showDialog) {
		updates.available?.let { info -> UpdateAvailableDialog(updates, info) }
	}
	if (updates.showChangelog) {
		ChangelogDialog(updates.changelog, updates::closeChangelog)
	}
}

@Composable
private fun UpdateAvailableDialog(updates: UpdatesModel, info: UpdateInfo) {
	val language = java.util.Locale.getDefault().language
	val notes = UpdateManifestParser.localizedNotes(info.notes, language)
	AlertDialog(
		onDismissRequest = updates::dismiss,
		title = { Text(stringResource(R.string.update_available_title)) },
		text = {
			Column(
				modifier = Modifier
					.heightIn(max = 420.dp)
					.verticalScroll(rememberScrollState())
			) {
				Text(
					stringResource(
						R.string.labelled_value,
						stringResource(R.string.update_current_version),
						com.angelonmx.reloquence.BuildConfig.VERSION_NAME
					)
				)
				Text(
					stringResource(
						R.string.labelled_value,
						stringResource(R.string.update_new_version),
						info.versionName
					)
				)
				if (notes.isNotEmpty()) {
					Text(
						text = stringResource(R.string.update_changes_title),
						style = MaterialTheme.typography.titleSmall,
						modifier = Modifier.padding(top = 12.dp)
					)
					notes.forEach { note ->
						Text(
							text = "• $note",
							style = MaterialTheme.typography.bodyMedium,
							modifier = Modifier.padding(top = 4.dp)
						)
					}
				}
				if (updates.status is UpdateStatus.Permission) {
					Text(
						text = stringResource(R.string.update_permission_needed),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.error,
						modifier = Modifier.padding(top = 12.dp)
					)
				}
			}
		},
		confirmButton = {
			TextButton(onClick = updates::install) {
				Text(stringResource(R.string.update_install_now))
			}
		},
		dismissButton = {
			TextButton(onClick = updates::dismiss) {
				Text(stringResource(R.string.update_not_now))
			}
		}
	)
}

@Composable
private fun ChangelogDialog(entry: ChangelogEntry?, onDismiss: () -> Unit) {
	val language = java.util.Locale.getDefault().language
	val notes = ChangelogStore.notes(entry, language)
	AlertDialog(
		onDismissRequest = onDismiss,
		title = {
			Text(
				stringResource(
					R.string.update_changelog_title,
					entry?.versionName ?: com.angelonmx.reloquence.BuildConfig.VERSION_NAME
				)
			)
		},
		text = {
			if (notes.isEmpty()) {
				Text(stringResource(R.string.update_changelog_empty))
			} else {
				Column(
					modifier = Modifier
						.heightIn(max = 420.dp)
						.verticalScroll(rememberScrollState())
				) {
					notes.forEach { note ->
						Text(
							text = "• $note",
							style = MaterialTheme.typography.bodyMedium,
							modifier = Modifier.padding(vertical = 4.dp)
						)
					}
				}
			}
		},
		confirmButton = {
			TextButton(onClick = onDismiss) {
				Text(stringResource(R.string.about_close))
			}
		}
	)
}

@Composable
private fun updateStatusText(status: UpdateStatus): String = when (status) {
	UpdateStatus.Idle -> ""
	UpdateStatus.Checking -> stringResource(R.string.update_checking)
	UpdateStatus.UpToDate -> stringResource(R.string.update_up_to_date)
	is UpdateStatus.Available -> stringResource(R.string.update_available, status.versionName)
	is UpdateStatus.Failed -> updateFailureText(status.failure)
	is UpdateStatus.Downloading -> status.percent?.let {
		stringResource(R.string.update_downloading, it)
	} ?: stringResource(R.string.update_downloading_unknown)
	UpdateStatus.Verifying -> stringResource(R.string.update_verifying)
	UpdateStatus.Installing -> stringResource(R.string.update_installing)
	UpdateStatus.Permission -> stringResource(R.string.update_permission_needed)
}

@Composable
private fun updateFailureText(failure: UpdateFailure): String = stringResource(
	when (failure) {
		UpdateFailure.Network -> R.string.update_error_network
		UpdateFailure.Server -> R.string.update_error_server
		UpdateFailure.NotNewer -> R.string.update_up_to_date
		UpdateFailure.Unsupported -> R.string.update_error_unsupported
		UpdateFailure.NoApk -> R.string.update_error_no_apk
		UpdateFailure.Download -> R.string.update_error_download
		UpdateFailure.Permission -> R.string.update_permission_needed
		UpdateFailure.Install -> R.string.update_error_install
		UpdateFailure.InvalidManifest,
		UpdateFailure.WrongApplication,
		UpdateFailure.WrongEdition,
		UpdateFailure.InvalidApk,
		UpdateFailure.Checksum,
		UpdateFailure.Signature -> R.string.update_error_invalid
		UpdateFailure.Unknown -> R.string.update_error_unknown
	}
)

/** Quién la hizo y cómo contactarlo. */
@Composable
private fun AboutScreen(state: SettingsModel, modifier: Modifier = Modifier, onBack: () -> Unit) {
	BackHandler(onBack = onBack)
	val ctx = LocalContext.current
	fun openUrl(url: String) {
		runCatching {
			ctx.startActivity(
				android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
					.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
			)
		}
	}
	Column(
		modifier = modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(bottom = 24.dp)
	) {
		SubHeader(stringResource(R.string.about_label), onBack)
		Text(
			text = stringResource(R.string.about_title),
			style = MaterialTheme.typography.titleLarge,
			modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
		)
		Text(
			text = stringResource(R.string.about_version,
				com.angelonmx.reloquence.BuildConfig.VERSION_NAME),
			style = MaterialTheme.typography.bodyLarge,
			modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
		)
		Text(
			text = stringResource(R.string.about_contact_hint),
			style = MaterialTheme.typography.bodyMedium,
			modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
		)
		ActionRow(stringResource(R.string.about_whatsapp), { openUrl("https://wa.me/523411119890") })
		ActionRow(stringResource(R.string.about_instagram), { openUrl("https://instagram.com/angjl25.lv") })
		ActionRow(stringResource(R.string.about_support_group), { openUrl("https://chat.whatsapp.com/C5GiTCXrD1t6Jj64uwT5An?s=cl&p=a&mlu=4&ilr=4") })
		ActionRow(stringResource(R.string.about_telegram), { openUrl("https://t.me/angjl25lv") })
		// Idioma de la aplicación: sistema o uno fijo. Cambiarlo
		// recrea la pantalla para que tome efecto al momento.
		SectionHeader(stringResource(R.string.app_language_label))
		val tags = AppLocale.SUPPORTED
		val tagNames = listOf(stringResource(R.string.app_language_system)) +
			tags.drop(1).map { AppLocale.displayName(it) }
		ChoiceRow(
			label = stringResource(R.string.app_language_label),
			options = tagNames,
			chosen = if (state.appLocale.isEmpty()) 0 else tags.indexOf(state.appLocale).coerceAtLeast(0),
			onChoose = { at ->
				state.chooseAppLocale(tags[at])
				(ctx as? android.app.Activity)?.recreate()
			}
		)
	}
}

/** Una fila que abre un diálogo de valores y aplica al elegir.
 *  Con [systemLabel] la primera opción es la del sistema:
 *  [isSystem] dice si está activa y [onChooseSystem] la pone. */
@Composable
private fun PercentRow(
	label: String,
	values: List<Int>,
	current: Int,
	systemLabel: String? = null,
	isSystem: Boolean = false,
	onChooseSystem: () -> Unit = {},
	onChoose: (Int) -> Unit
) {
	val options = remember(values, systemLabel) {
		if (systemLabel != null) listOf(systemLabel) + values.map { "$it%" }
		else values.map { "$it%" }
	}
	val shift = if (systemLabel != null) 1 else 0
	val chosen = if (systemLabel != null && isSystem) 0 else nearest(values, current) + shift
	ChoiceRow(
		label = label,
		options = options,
		chosen = chosen,
		onChoose = {
			if (systemLabel != null && it == 0) onChooseSystem() else onChoose(it - shift)
		}
	)
}

private fun nearest(options: List<Int>, value: Int): Int {
	var best = 0
	var bestDist = Int.MAX_VALUE
	options.forEachIndexed { at, option ->
		val dist = kotlin.math.abs(option - value)
		if (dist < bestDist) {
			bestDist = dist
			best = at
		}
	}
	return best
}

private val SPEED_OPTIONS = (0..500).toList()

private val PCT_0_100 = (0..100 step 5).toList()

private val TONE_OPTIONS = (0..100 step 5).toList()

private val VOLUME_OPTIONS = (0..500).toList()

private val PAUSE_OPTIONS = listOf(0, 50, 100, 150, 200, 250, 300, 400, 500, 750)

private val EQ_DB_OPTIONS = (-12..12).toList()

private val BITS_OPTIONS = listOf(4, 6, 8, 11, 14, 16)

private val REVERB_OPTIONS = (0..100 step 5).toList()

private val BOOST_OPTIONS = (0..200 step 10).toList()

private val MIX_WORDS = listOf(5, 10, 15, 20, 30, 50, 75, 100, 150, 200, 300, 500, 1000)

private val ECHO_TIMES = listOf(120, 220, 350)

private val TREMOLO_RATES = listOf(30, 55, 80)

private fun tremoloHz(tenths: Int): String = "${tenths / 10}.${tenths % 10} Hz"

@Composable
private fun describeFxPatch(patch: Settings.FxPatch): List<String> {
	val out = ArrayList<String>()
	if (patch.bands.any { it != 0 }) out.add(stringResource(R.string.eq_label))
	if (patch.reverb > 0) out.add("${stringResource(R.string.reverb_label)} ${patch.reverb}%")
	if (patch.echo > 0) out.add("${stringResource(R.string.echo_label)} ${patch.echo}%")
	if (patch.comp) out.add(stringResource(R.string.compressor_label))
	if (patch.boost != 100) out.add("${stringResource(R.string.boost_label)} ${patch.boost}%")
	if (patch.gate) out.add(stringResource(R.string.gate_label))
	if (patch.normalizer) out.add(stringResource(R.string.normalizer_label))
	if (patch.tremolo > 0) out.add("${stringResource(R.string.tremolo_label)} ${patch.tremolo}%")
	if (patch.chorus) out.add(stringResource(R.string.chorus_label))
	if (patch.crusher) out.add(stringResource(R.string.crusher_label))
	if (patch.flanger) out.add(stringResource(R.string.flanger_label))
	if (patch.phaser) out.add(stringResource(R.string.phaser_label))
	if (patch.vibrato > 0) out.add("${stringResource(R.string.vibrato_label)} ${patch.vibrato}%")
	if (patch.ringmod > 0) out.add("${stringResource(R.string.ringmod_label)} ${patch.ringmod}%")
	if (patch.fuzz > 0) out.add("${stringResource(R.string.fuzz_label)} ${patch.fuzz}%")
	if (patch.wah > 0) out.add("${stringResource(R.string.wah_label)} ${patch.wah}%")
	if (patch.maximizer > 0) out.add("${stringResource(R.string.maximizer_label)} ${patch.maximizer}%")
	if (patch.telephone) out.add(stringResource(R.string.telephone_label))
	if (patch.highpass) out.add(stringResource(R.string.highpass_label))
	if (patch.drive) out.add(stringResource(R.string.drive_label))
	if (patch.bass != 0) out.add("${stringResource(R.string.bass_label)} ${patch.bass} dB")
	if (patch.presence != 0) out.add("${stringResource(R.string.presence_label)} ${patch.presence} dB")
	if (patch.exciter > 0) out.add("${stringResource(R.string.exciter_label)} ${patch.exciter}%")
	if (patch.warmth) out.add(stringResource(R.string.warmth_label))
	if (patch.deesser) out.add(stringResource(R.string.deesser_label))
	if (out.isEmpty()) out.add(stringResource(R.string.fx_profile_manual))
	return out
}

/**
 * 4.4: el nombre de un idioma del motor, ya traducido.
 *
 * Los dos módulos de español llevan nombre propio en los recursos
 * porque el motor no nombra un país sino un dialecto: `esus` es el
 * español latinoamericano de IBM, no el de México, y con
 * `Locale.getDisplayName` salía justo eso. Ver [Eci.nameRes].
 */
@Composable
private fun engineName(language: Int): String {
	val res = Eci.nameRes(language)
	return if (res != 0) stringResource(res) else Eci.displayName(language)
}

@Composable
private fun describeVoicePatch(patch: Settings.VoicePatch): List<String> {
	val out = ArrayList<String>()
	out.add(Eci.PRESET_NAMES.getOrElse(patch.baseVoice) { "?" })
	val names = mapOf(
		Eci.VOICE_GENDER to stringResource(R.string.gender_label),
		Eci.VOICE_PITCH_BASELINE to stringResource(R.string.pitch_label),
		Eci.VOICE_PITCH_FLUCTUATION to stringResource(R.string.inflection_label),
		Eci.VOICE_HEAD_SIZE to stringResource(R.string.head_size_label),
		Eci.VOICE_ROUGHNESS to stringResource(R.string.roughness_label),
		Eci.VOICE_BREATHINESS to stringResource(R.string.breathiness_label),
		Eci.VOICE_VOLUME to stringResource(R.string.volume_label)
	)
	for ((param, value) in patch.params.toSortedMap()) {
		names[param]?.let { out.add("$it: $value") }
	}
	out.add(
		if (patch.systemSpeed) stringResource(R.string.speed_system)
		else "${stringResource(R.string.speed_label)}: ${patch.speed}"
	)
	out.add(
		if (patch.systemVolume) stringResource(R.string.volume_system)
		else "${stringResource(R.string.volume_label)}: ${patch.volume}%"
	)
	out.add(stringResource(R.string.hertz, patch.sampleRateHz))
	if (patch.body) out.add(stringResource(R.string.body_label))
	if (patch.hdEnhance) out.add(stringResource(R.string.hd_enhance_label))
	return out
}

@Composable
private fun ChoiceRow(
	label: String,
	options: List<String>,
	chosen: Int,
	onChoose: (Int) -> Unit
) {
	var open by remember { mutableStateOf(false) }
	val now = options.getOrElse(chosen) { "" }
	Text(
		text = stringResource(R.string.labelled_value, label, now),
		style = MaterialTheme.typography.bodyLarge,
		modifier = Modifier
			.fillMaxWidth()
			.clickable { open = true }
			.padding(horizontal = 16.dp, vertical = 14.dp)
			.clearAndSetSemantics {
				contentDescription = label
				stateDescription = now
				role = Role.Button
			}
	)
	if (open) {
		AlertDialog(
			onDismissRequest = { open = false },
			title = { Text(label) },
			text = {
				LazyColumn(
					modifier = Modifier
						.fillMaxWidth()
						.heightIn(max = 420.dp)
						.selectableGroup(),
					state = rememberLazyListState(
						initialFirstVisibleItemIndex = chosen.coerceIn(0, (options.size - 1).coerceAtLeast(0))
					)
				) {
					itemsIndexed(options, key = { at, _ -> at }) { at, option ->
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.selectable(
									selected = at == chosen,
									onClick = {
										onChoose(at)
										open = false
									},
									role = Role.RadioButton
								)
								.padding(vertical = 12.dp),
							verticalAlignment = Alignment.CenterVertically
						) {
							RadioButton(selected = at == chosen, onClick = null)
							Text(
								text = option,
								modifier = Modifier.padding(start = 12.dp),
								style = MaterialTheme.typography.bodyLarge
							)
						}
					}
				}
			},
			confirmButton = {
				TextButton(onClick = { open = false }) {
					Text(stringResource(R.string.cancel))
				}
			}
		)
	}
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
	val on = stringResource(if (checked) R.string.switch_on else R.string.switch_off)
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable { onChange(!checked) }
			.padding(horizontal = 16.dp, vertical = 14.dp)
			.clearAndSetSemantics {
				contentDescription = label
				stateDescription = on
				role = Role.Switch
			},
		verticalAlignment = Alignment.CenterVertically
	) {
		Text(
			text = label,
			style = MaterialTheme.typography.bodyLarge,
			modifier = Modifier.weight(1f)
		)
		Switch(checked = checked, onCheckedChange = null)
	}
}

/** Una etiqueta y su valor, sin acción: para el diagnóstico. */
@Composable
private fun InfoRow(label: String, value: String) {
	Text(
		text = stringResource(R.string.labelled_value, label, value),
		style = MaterialTheme.typography.bodyLarge,
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 10.dp)
			.clearAndSetSemantics {
				contentDescription = label
				stateDescription = value
			}
	)
}

/** Una etiqueta, lo que tiene, y algo que apretar para
 *  cambiarlo. */
@Composable
private fun ValueRow(label: String, value: String, onClick: () -> Unit) {
	Text(
		text = stringResource(R.string.labelled_value, label, value),
		style = MaterialTheme.typography.bodyLarge,
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(horizontal = 16.dp, vertical = 14.dp)
			.clearAndSetSemantics {
				contentDescription = label
				stateDescription = value
				role = Role.Button
			}
	)
}

/** Una palabra marcable para el borrado múltiple. */
@Composable
private fun SelectRow(label: String, value: String, checked: Boolean, onToggle: () -> Unit) {
	val on = stringResource(if (checked) R.string.switch_on else R.string.switch_off)
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onToggle)
			.padding(horizontal = 16.dp, vertical = 8.dp)
			.clearAndSetSemantics {
				contentDescription = label
				stateDescription = "$value. $on"
				role = Role.Checkbox
			},
		verticalAlignment = Alignment.CenterVertically
	) {
		Checkbox(checked = checked, onCheckedChange = null)
		Text(
			text = stringResource(R.string.labelled_value, label, value),
			style = MaterialTheme.typography.bodyLarge,
			modifier = Modifier
				.weight(1f)
				.padding(start = 12.dp)
		)
	}
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
	Text(
		text = label,
		style = MaterialTheme.typography.bodyLarge,
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(horizontal = 16.dp, vertical = 14.dp)
			.clearAndSetSemantics {
				contentDescription = label
				role = Role.Button
			}
	)
}
