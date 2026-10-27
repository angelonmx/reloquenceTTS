package com.angelonmx.reloquence

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Archivos que salen de la app: una copia en caché y el selector
 *  del sistema, sin pasar por "Guardar como". */
object Share {

	data class Target(val uri: Uri, val mime: String, val name: String)

	fun fileName(raw: String, fallback: String): String {
		val clean = raw.trim()
			.replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001f]"), "_")
			.take(80)
		return clean.ifEmpty { fallback }
	}

	fun prepare(
		context: Context,
		folder: String,
		name: String,
		mime: String,
		bytes: ByteArray
	): Target? = runCatching {
		val dir = File(context.cacheDir, folder).apply { mkdirs() }
		val file = File(dir, fileName(name, "archivo"))
		file.writeBytes(bytes)
		val uri = FileProvider.getUriForFile(
			context, "${context.packageName}.fileprovider", file
		)
		Target(uri, mime, file.name)
	}.getOrNull()

	fun send(context: Context, target: Target): Boolean = runCatching {
		val send = Intent(Intent.ACTION_SEND).apply {
			type = target.mime
			putExtra(Intent.EXTRA_STREAM, target.uri)
			clipData = ClipData.newRawUri(target.name, target.uri)
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
		}
		val chooser = Intent.createChooser(send, null).apply {
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
			if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		}
		context.startActivity(chooser)
		true
	}.getOrDefault(false)
}
