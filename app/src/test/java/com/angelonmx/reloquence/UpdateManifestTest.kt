package com.angelonmx.reloquence

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 5.4: las reglas que deciden si una actualización se puede
 * instalar. Son Kotlin puro, sin red ni Android, así que se prueban
 * aquí y no en un teléfono.
 */
class UpdateManifestTest {

	private val sha = "a".repeat(64)

	private fun apk(
		abi: String = "arm64-v8a",
		url: String = "https://example.com/app.apk",
		hash: String = sha
	) = UpdateApk(abi = abi, url = url, sha256 = hash, size = 1234)

	private fun manifest(
		schema: Int = 1,
		applicationId: String = "com.angelonmx.reloquence",
		edition: String = "full",
		versionName: String = "5.4",
		versionCode: Int = 5400,
		minSdk: Int = 23,
		apks: List<UpdateApk> = listOf(apk())
	) = UpdateManifest(
		schema = schema,
		applicationId = applicationId,
		edition = edition,
		versionName = versionName,
		versionCode = versionCode,
		minSdk = minSdk,
		apks = apks
	)

	private fun validate(
		value: UpdateManifest,
		currentVersionCode: Int = 5300,
		applicationId: String = "com.angelonmx.reloquence",
		edition: String = "full",
		sdkInt: Int = 34
	): UpdateFailure? = UpdateManifestParser.validate(
		value,
		currentVersionCode,
		applicationId,
		edition,
		sdkInt
	)

	@Test
	fun `un manifiesto completo se acepta`() {
		assertNull(validate(manifest()))
	}

	@Test
	fun `el manifiesto tiene que ser de esta aplicacion`() {
		assertEquals(
			UpdateFailure.WrongApplication,
			validate(manifest(applicationId = "com.example.otra"))
		)
	}

	@Test
	fun `el manifiesto tiene que ser de esta edicion`() {
		assertEquals(
			UpdateFailure.WrongEdition,
			validate(manifest(edition = "lite"))
		)
	}

	@Test
	fun `una version futura del esquema no se interpreta`() {
		assertEquals(
			UpdateFailure.InvalidManifest,
			validate(manifest(schema = 2))
		)
	}

	@Test
	fun `la misma version o una anterior no es una actualizacion`() {
		assertEquals(UpdateFailure.NotNewer, validate(manifest(), currentVersionCode = 5400))
		assertEquals(UpdateFailure.NotNewer, validate(manifest(), currentVersionCode = 5500))
	}

	@Test
	fun `una version con mas codigo si es una actualizacion`() {
		assertNull(validate(manifest(versionCode = 5401), currentVersionCode = 5400))
	}

	@Test
	fun `un sistema demasiado viejo no recibe la actualizacion`() {
		assertEquals(
			UpdateFailure.Unsupported,
			validate(manifest(minSdk = 34), sdkInt = 33)
		)
	}

	@Test
	fun `un manifiesto nuevo sin APK no ofrece nada`() {
		assertEquals(UpdateFailure.NoApk, validate(manifest(apks = emptyList())))
	}

	@Test
	fun `las URL tienen que ser HTTPS`() {
		assertEquals(
			UpdateFailure.InvalidManifest,
			validate(manifest(apks = listOf(apk(url = "http://example.com/app.apk"))))
		)
		assertEquals(
			UpdateFailure.InvalidManifest,
			validate(manifest(apks = listOf(apk(url = "file:///app.apk"))))
		)
	}

	@Test
	fun `la huella tiene que ser SHA-256`() {
		assertEquals(
			UpdateFailure.InvalidManifest,
			validate(manifest(apks = listOf(apk(hash = "abc"))))
		)
		assertEquals(
			UpdateFailure.InvalidManifest,
			validate(manifest(apks = listOf(apk(hash = "g".repeat(64)))))
		)
	}

	@Test
	fun `se elige el APK de la ABI del telefono antes que el universal`() {
		val universal = apk(abi = "universal")
		val arm64 = apk(abi = "arm64-v8a")
		val arm32 = apk(abi = "armeabi-v7a")
		val chosen = UpdateManifestParser.chooseApk(
			listOf(universal, arm32, arm64),
			listOf("arm64-v8a", "armeabi-v7a")
		)
		assertEquals("arm64-v8a", chosen?.abi)
	}

	@Test
	fun `una instalacion universal descarga el APK exacto de su ABI`() {
		assertTrue(ApkAbi.isUniversal(ApkAbi.fromEntries(listOf(
			"lib/arm64-v8a/libengine.so",
			"lib/armeabi-v7a/libengine.so",
			"lib/x86_64/libengine.so"
		))))
		val chosen = UpdateManifestParser.chooseApk(
			listOf(apk(abi = "universal"), apk(abi = "arm64-v8a")),
			listOf("arm64-v8a")
		)
		assertEquals("aunque lo instalado sea original, baja arm64-v8a", "arm64-v8a", chosen?.abi)
	}

	@Test
	fun `las ABIs se leen de lib y se normalizan`() {
		val names = listOf(
			"AndroidManifest.xml",
			"lib/arm64-v8a/libengine.so",
			"lib/arm64-v8a/libz.so",
			"lib/armeabi-v7a/libengine.so"
		)
		val abis = ApkAbi.fromEntries(names)
		assertEquals(setOf("arm64-v8a", "armeabi-v7a"), abis)
		assertEquals("universal", ApkAbi.label(abis))
		assertTrue(ApkAbi.isUniversal(abis))

		val specific = ApkAbi.fromEntries(listOf("lib/arm64-v8a/libengine.so"))
		assertEquals("arm64-v8a", ApkAbi.label(specific))
		assertFalse(ApkAbi.isUniversal(specific))
		assertEquals(setOf("arm64-v8a"), ApkAbi.normalize(listOf("arm64")))
		assertEquals(emptySet<String>(), ApkAbi.fromEntries(listOf("lib/readme.txt")))
	}

	@Test
	fun `si no hay ABI exacta se usa el universal`() {
		val universal = apk(abi = "universal")
		val chosen = UpdateManifestParser.chooseApk(
			listOf(universal, apk(abi = "x86_64")),
			listOf("arm64-v8a")
		)
		assertEquals("universal", chosen?.abi)
	}

	@Test
	fun `sin APK compatible no se elige nada`() {
		assertNull(
			UpdateManifestParser.chooseApk(
				listOf(apk(abi = "x86_64")),
				listOf("arm64-v8a")
			)
		)
	}

	@Test
	fun `las notas caen al ingles y despues al espanol`() {
		val notes = mapOf(
			"es" to listOf("hola"),
			"en" to listOf("hello"),
			"fr" to listOf("bonjour")
		)
		assertEquals(listOf("bonjour"), UpdateManifestParser.localizedNotes(notes, "fr-CA"))
		assertEquals(listOf("hello"), UpdateManifestParser.localizedNotes(notes, "de"))
		assertEquals(
			listOf("hola"),
			UpdateManifestParser.localizedNotes(mapOf("es" to listOf("hola")), "de")
		)
		assertTrue(UpdateManifestParser.localizedNotes(emptyMap(), "es").isEmpty())
	}

	@Test
	fun `el JSON ignora campos que no conoce`() {
		val raw = """
			{
			  "schema": 1,
			  "applicationId": "com.angelonmx.reloquence",
			  "edition": "full",
			  "versionName": "5.4",
			  "versionCode": 5400,
			  "campoDelFuturo": true,
			  "apks": [
			    {
			      "abi": "arm64-v8a",
			      "url": "https://example.com/app.apk",
			      "sha256": "$sha",
			      "size": 1234
			    }
			  ]
			}
		""".trimIndent()
		val parsed = UpdateManifestParser.parse(raw)
		assertEquals(5400, parsed.versionCode)
		assertEquals(1, parsed.apks.size)
	}

	@Test
	fun `isHttps rechaza lo que no es una URL absoluta`() {
		assertTrue(UpdateManifestParser.isHttps("https://example.com/a.json"))
		assertTrue(UpdateManifestParser.isHttps("HTTPS://example.com/a.json"))
		assertFalse(UpdateManifestParser.isHttps("http://example.com/a.json"))
		assertFalse(UpdateManifestParser.isHttps("https:///a.json"))
		assertFalse(UpdateManifestParser.isHttps("https://example.com/a b.json"))
		assertFalse(UpdateManifestParser.isHttps("file:///a.json"))
	}

	@Test
	fun `los manifiestos del repositorio son validos`() {
		for (edition in listOf("full", "lite")) {
			val file = File("../updates/$edition.json")
			assertTrue("falta $file", file.exists())
			val manifest = UpdateManifestParser.parse(file.readText())
			assertEquals(1, manifest.schema)
			assertEquals(edition, manifest.edition)
			assertEquals(
				if (edition == "lite") "com.angelonmx.reloquence.lite"
				else "com.angelonmx.reloquence",
				manifest.applicationId
			)
			assertTrue(manifest.versionCode > 0)
		}
	}

	@Test
	fun `el changelog trae las versiones instaladas`() {
		val versions = File("../version.properties").readLines()
			.mapNotNull { line ->
				val clean = line.trim()
				if (clean.isEmpty() || clean.startsWith("#") || !clean.contains("=")) {
					null
				} else {
					clean.substringBefore("=").trim() to clean.substringAfter("=").trim()
				}
			}
			.toMap()
		val fullCode = versions.getValue("full.versionCode").toInt()
		val liteCode = versions.getValue("lite.versionCode").toInt()
		val file = File("src/main/assets/changelog.json")
		assertTrue("falta $file", file.exists())
		val changelog = Json {
			ignoreUnknownKeys = true
			explicitNulls = false
		}.decodeFromString(Changelog.serializer(), file.readText())
		val full = changelog.editions["full"].orEmpty()
		val lite = changelog.editions["lite"].orEmpty()
		assertNotNull(full.firstOrNull { it.versionCode == fullCode })
		assertNotNull(lite.firstOrNull { it.versionCode == liteCode })
		assertTrue(full.first { it.versionCode == fullCode }.notes.containsKey("es"))
		assertTrue(lite.first { it.versionCode == liteCode }.notes.containsKey("en"))
	}
}
