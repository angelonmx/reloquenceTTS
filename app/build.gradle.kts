import org.gradle.internal.os.OperatingSystem
import java.util.Properties
import java.nio.file.Files
import java.nio.file.StandardCopyOption
plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.kotlin.serialization)
}

val abis = (findProperty("evvdroid.abis") as String? ?: "arm64-v8a,armeabi-v7a")
	.split(",").map { it.trim() }.filter { it.isNotEmpty() }
val rulesForm = findProperty("evvdroid.rules") as String? ?: "c"

/**
 * 4.4: dos ediciones, y una es la misma app con menos idiomas.
 *
 * `full` es lo de siempre: los nueve módulos del motor, versión
 * 5.4. `lite` es exactamente la misma app --mismas funciones,
 * mismos efectos, mismos perfiles, mismo diccionario-- con solo los
 * dos módulos de español, versión 2.4.
 *
 * Conviven: la Lite lleva su propio `applicationId`, así que se
 * pueden instalar las dos en el mismo teléfono y elegir el motor en
 * los ajustes de texto a voz. Instalar una no borra la otra.
 *
 * Lo que cambia entre las dos, y solo esto:
 *
 *  - `applicationId`, y con él el sitio de los ajustes y del
 *    diccionario, que no se pisan;
 *  - `versionName`/`versionCode`;
 *  - la etiqueta de la app;
 *  - los módulos del motor que se compilan, que es lo que de
 *    verdad la hace ligera: sin los otros siete no hay trece
 *    megabytes de reglas de idioma que cargar ni que mantener en
 *    memoria;
 *  - y la autodetección por modelo, que en la Lite no puede dar
 *    ningún resultado (ver [needsMlKit]).
 *
 * ## Por qué cada una tiene su carpeta de compilación
 *
 * Las dos ediciones comparten `app/build` y se pisan de dos
 * maneras, y las dos se comprobaron al hacerlo:
 *
 *  1. Los APK salían con el mismo nombre, así que la segunda
 *     edición en construirse escribía encima de la primera.
 *  2. Con nombres distintos seguía faltando la mitad: el
 *     empaquetado de AGP **borra** `outputs/apk/release` antes de
 *     escribir, así que compilar la Lite después de la normal se
 *     llevaba por delante los APK de la normal. Darles nombre no
 *     arregla eso; hay que darles sitio.
 *
 * Por eso `buildDirectory` baja a `build/<edición>`, y por eso el
 * nombre del archivo lleva `lite` delante en su caso.
 */
val edition = (findProperty("evvdroid.edition") as String? ?: "full").lowercase()
if (edition != "full" && edition != "lite") {
	throw GradleException("evvdroid.edition debe ser full o lite; es \"$edition\"")
}
val isLite = edition == "lite"

/**
 * 5.4: la versión de cada edición, en un solo sitio.
 *
 * Las versiones viven en `version.properties`, en la raíz del
 * repositorio, porque las leen tres cosas distintas: Gradle para el
 * `versionName`/`versionCode`, los manifiestos de actualización y
 * los flujos de GitHub Actions. Antes estaban escritas dos veces
 * --en `defaultConfig` y en [renameApks]-- y las dos copias tenían
 * que cambiar a la vez: si se olvidaba una, el APK salía con un
 * nombre que no era el suyo y el aviso de actualización del sistema
 * no lo reconocía.
 */
val versionProps = Properties().apply {
	val file = rootProject.file("version.properties")
	if (!file.exists()) {
		throw GradleException("Falta version.properties en la raíz del repositorio")
	}
	file.inputStream().use { load(it) }
}

fun versionName(key: String): String =
	versionProps.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
		?: throw GradleException("Falta $key en version.properties")

fun versionCode(key: String): Int =
	versionName(key).toIntOrNull()?.takeIf { it > 0 }
		?: throw GradleException("$key en version.properties no es un entero positivo")

val fullVersionName = versionName("full.versionName")
val fullVersionCode = versionCode("full.versionCode")
val liteVersionName = versionName("lite.versionName")
val liteVersionCode = versionCode("lite.versionCode")
val editionVersionName = if (isLite) liteVersionName else fullVersionName
val editionVersionCode = if (isLite) liteVersionCode else fullVersionCode

// Antes de que nada lea `layout.buildDirectory`.
layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("app/build/$edition"))

// Los idiomas que funcionan en este puente.
//
// El japonés entra en 4.3. Estaba fuera por dos razones, y las dos
// estaban mal:
//   - "aquí solo llegan bytes latinos". Es verdad que el motor
//     japonés no habla UTF-8, pero eso lo arregla Kotlin, no el
//     puente: ver Eci.codeSetFor. Medido con el motor en la
//     máquina, こんにちは daba 0 muestras en UTF-8 y 15.345 en
//     Shift-JIS, que es lo que el motor pide.
//   - "necesita Shift-JIS y aquí no llega". Reachability: el módulo
//     está entero, 956 reglas y un romanizador de 23.666 líneas, y
//     compila para Android arm64 con sus 201 objetos.
//
// El polaco sigue fuera, y ahora también su código: `lang/plpl` en
// openevv está al 95 % en italiano (1725 de 1804 reglas carácter a
// carácter, tools/module/census.py), así que una voz etiquetada
// "Polaco" hablaría con fonología italiana.
//
// 4.4: `eses` y `esus` son los dos módulos de español, y son lo
// único que lleva la edición Lite. No es "español (España)" y
// "español (México)": `esus` es el español latinoamericano de IBM,
// que cubre todo lo que no es España.
val fullLangs = "lang/enus lang/eses lang/esus lang/engb lang/frfr lang/frca lang/dede lang/itit lang/jajp"
val liteLangs = "lang/eses lang/esus"
val defaultLangs = if (isLite) liteLangs else fullLangs
val languages = findProperty("evvdroid.langs") as String? ?: defaultLangs
// Cada edición compila en su propia carpeta: si compartieran, la
// segunda en construirse encontraría la biblioteca de la primera
// con los siete módulos de más y no se habría recompilado nada.
val nativeOut = layout.buildDirectory.dir("native/jniLibs")

/**
 * 4.4: si la autodetección por modelo cabe en esta edición.
 *
 * ML Kit trae su biblioteca nativa y su modelo dentro del APK, y
 * son **1,27 MB** comprimidos por ABI (0,97 de `liblanguage_id_l2c_jni.so`
 * más 0,30 de `tflite_langid.tflite.jpg`). En el APK universal, que
 * lleva los dos ABI, son 1,85.
 *
 * En la edición Lite no puede cambiar nada, y esto es lo que lo
 * demuestra:
 *
 *  - Lo que ML Kit devuelve es una **etiqueta de idioma** ("spa",
 *    "eng"...), nunca una variante.
 *  - Lo que se hace con ella es [AutoLang.preferredEngine], que
 *    busca los motores disponibles cuya familia sea esa, y devuelve
 *    **nada** si no hay ninguno (`matches.isEmpty()`).
 *  - En la Lite los únicos motores son los dos de español. Así que
 *    para cualquier etiqueta que no sea española el resultado es
 *    "cambia de motor" → `null`, igual que si la etiqueta no se
 *    hubiera detectado nunca.
 *  - Y para la española los dos motores son de la misma familia, así
 *    que la elección cae en la preferencia que el usuario ya tiene
	 * dónde escribiría la tarea, no qué hay escrito.
 *
 * O sea: el resultado de ML Kit y el resultado de la heurística de
 * palabras que ya viene de serie convergen en todos los casos que se
 * pueden dar con dos motores de español. Lo que se quita es una
 * entrada que no puede tener efecto distinto, no una función.
 *
 * Lo que NO se puede quitar y sigue ahí: la heurística
 * ([AutoLang.heuristic]) y el interruptor de autodetección, que es lo
 * que la persona oye. Lo único que se pierde es la detección por
 * modelo de un texto que no está en español, que en la Lite no
 * tenía a dónde ir igualmente.
 *
 * Con esto la Lite pasa de 11,7 a unos 10,4 MB por ABI. Lo que queda
 * es sobre todo el motor (6,98 MB de reglas ya stripped), que no se
 * puede reducir sin tocar la voz.
 */
val needsMlKit = (findProperty("evvdroid.mlkit") as String?)?.toBoolean() ?: !isLite

/**
 * 5.4: dónde están los dos manifiestos de actualización.
 *
 * La app no lleva la URL de GitHub escrita en el código: se inyecta
 * aquí para que el mismo fuente sirva para un fork, para GitHub Pages
 * o para un servidor propio. El valor de `gradle.properties` es el de
 * este repositorio; GitHub Actions lo sustituye por el repositorio
 * real con `-Preloquence.updateBaseUrl=...`, así que un APK compilado
 * en Actions siempre apunta a su propio `updates/`.
 *
 * Tiene que ser HTTPS: la app rechaza cualquier otra cosa antes de
 * descargar nada.
 */
val updateBaseUrl = (
	findProperty("reloquence.updateBaseUrl") as String?
		?: System.getenv("RELOQUENCE_UPDATE_BASE_URL")
		?: "https://raw.githubusercontent.com/angelonmx/reloquenceTTS/main/updates"
).trim().trimEnd('/')

/**
 * Permite omitir el motor nativo en comprobaciones de CI que solo
 * compilan Kotlin. El flujo de release construye el motor expresamente
 * antes de empaquetar cada edición.
 */
val skipNative = (findProperty("evvdroid.skipNative") as String?)?.toBoolean() ?: false

// Release signing, from a keystore.properties the repo does not carry. Without
// it a clone still builds and the release APK comes out unsigned.
val keystore = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { where ->
	Properties().apply { where.inputStream().use { stream -> load(stream) } }
}

// The engine is built by its own Makefile rather than by CMake, because that
// Makefile writes the language rules with Python before it compiles them and
// nothing here has a reason to reimplement that.
val buildNative by tasks.registering(Exec::class) {
	group = "build"
	description = "Cross-compiles the openevv engine and the JNI bridge for each ABI."
	val engine = rootProject.layout.projectDirectory.dir("native/openevv")
	val script = rootProject.layout.projectDirectory.file("native/build-native.sh")
	// Deliberately not the engine's own sources. The script patches them, so a
	// task declaring them would be rewriting its own inputs every time it ran
	// and would never settle: the first build after any change fails and the
	// next one succeeds. What decides the output is the submodule's commit, the
	// patches laid on it, and the files named here. Editing native/openevv by
	// hand wants --rerun-tasks. The patches are a file tree rather than a
	// directory because there are none at the moment, and git does not carry an
	// empty one.
	inputs.files(rootProject.fileTree("native/patches"))
	inputs.file(rootProject.layout.projectDirectory.file("native/android.mk"))
	inputs.file(rootProject.layout.projectDirectory.file(".gitmodules"))
	inputs.file(script)
	inputs.file(layout.projectDirectory.file("src/main/cpp/evv_jni.c"))
	inputs.property("abis", abis)
	inputs.property("rules", rulesForm)
	inputs.property("langs", languages)
	// 4.4: la edición va como entrada porque decide los módulos que
	// se compilan. Sin esto, construir la Lite después de la normal
	// daría por buena la biblioteca de la normal, que ya trae los
	// siete módulos de más.
	inputs.property("edition", edition)
	// 5.4: el commit del motor también decide el resultado. Sin esto,
	// la caché de compilación de GitHub Actions podría reutilizar las
	// bibliotecas de un commit anterior después de actualizar el
	// submódulo.
	val engineCommit = providers.exec {
		commandLine("git", "-C", engine.asFile.absolutePath, "rev-parse", "HEAD")
		isIgnoreExitValue = true
	}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }
	inputs.property("engineCommit", engineCommit)
	outputs.dir(nativeOut)
	val shell = if (OperatingSystem.current().isWindows) {
		val git = System.getenv("PROGRAMFILES")?.let { file("$it/Git/bin/bash.exe") }
		if (git != null && git.exists()) git.absolutePath else "bash"
	} else {
		"bash"
	}
	commandLine(shell, script.asFile.absolutePath)
	environment("ABIS", abis.joinToString(" "))
	environment("RULES", rulesForm)
	environment("LANGS", languages)
	environment("OUT", nativeOut.get().asFile.absolutePath)
	doFirst {
		if (!engine.file("Makefile").asFile.exists()) {
			throw GradleException("native/openevv is empty. Run: git submodule update --init --recursive")
		}
	}
}

android {
	namespace = "com.angelonmx.reloquence"
	// Android 17 (API 37). Es una versión con minors: el SDK
	// instalado es android-37.0, que es lo que pide compileSdkMinor.
	compileSdk = 37
	compileSdkMinor = 0
	ndkVersion = "26.1.10909125"

	// 4.4: el nombre del archivo de salida. Sin esto las dos
	// ediciones se pisan: Gradle mete las dos en
	// `app/build/outputs/apk/release` y con el mismo nombre de tarea
	// el APK de la Lite se come el de la normal, así que compilar
	// las dos seguidas deja una sola en el disco. La normal conserva
	// su nombre de siempre y la Lite lleva "lite" delante.
	base {

	}

	defaultConfig {
		// 4.4: la edición Lite va en su propia app para que conviva
		// con la normal. El `suffix` es lo único que las separa en
		// el paquete: los ajustes, el diccionario y los perfiles van
		// en almacenamiento propio, así que una no pisa a la otra.
		applicationId = "com.angelonmx.reloquence" +
			if (isLite) ".lite" else ""
		buildConfigField("boolean", "LITE", isLite.toString())
		// La constante que R8 reduce para sacar a ML Kit del dex.
		// `final` y sin setter es lo que la hace plegable; el
		// `boolean` en vez de `Boolean` importa por lo mismo.
		buildConfigField(
			"boolean", "MLKIT",
			needsMlKit.toString()
		)
		// 5.4: el manifiesto que le corresponde a esta edición y la
		// base desde la que se descarga. Ver [updateBaseUrl].
		buildConfigField("String", "UPDATE_EDITION", "\"${if (isLite) "lite" else "full"}\"")
		buildConfigField(
			"String", "UPDATE_BASE_URL",
			"\"${updateBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\""
		)
		// La etiqueta sale del recurso, que es lo único que cambia
		// de una a otra en la lista de apps.
		resValue("string", "app_name",
			if (isLite) "reloquenceTTS Lite" else "reloquenceTTS")
		resValue("string", "engine_name",
			if (isLite) "reloquenceTTS Lite" else "reloquenceTTS")
		minSdk = 23
		targetSdk = 37
		// 5.2: la versión sale de [editionVersionName], no de aquí.
		versionCode = editionVersionCode
		versionName = editionVersionName
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		ndk {
			abiFilters += abis
		}
	}

	sourceSets.getByName("androidTest") {
		kotlin.srcDirs("src/androidTest/kotlin")
	}

	// 4.3: las pruebas de JVM viven en src/test/java y no en
	// src/test/kotlin, porque el source set "test" ya no sale en el
	// informe de AGP 8.13 y registrarlo a mano deja la tarea en
	// NO-SOURCE. Kotlin compila los .kt que encuentra ahi sin que
	// haya que decir nada mas.

	sourceSets.getByName("main") {
		kotlin.srcDirs("src/main/kotlin")
		jniLibs.srcDirs(nativeOut)
	}

	signingConfigs {
		keystore?.let { props ->
			create("release") {
				storeFile = file(props.getProperty("storeFile"))
				storePassword = props.getProperty("storePassword")
				keyAlias = props.getProperty("keyAlias")
				keyPassword = props.getProperty("keyPassword")
				// Off by default in AGP. It is what lets the key be rotated
				// later without every install having to be thrown away.
				enableV3Signing = true
			}
		}
	}

	buildTypes {
		release {
			signingConfig = signingConfigs.findByName("release")
			isMinifyEnabled = true
			isShrinkResources = true
			proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
		}
		debug {
			isJniDebuggable = true
		}
	}

	packaging {
		jniLibs {
			// 5.1: las .so viajan comprimidas y Android las extrae al
			// instalar. El APK baja mucho; se paga la extracción inicial.
			useLegacyPackaging = true
			// 4.4: la biblioteca de ML Kit no se empaqueta en la Lite.
			// Su código sale del dex por la constante de BuildConfig, así
			// que no hay nada que la cargue y quitarla es seguro. Son
			// 0,97 MB por ABI.
			if (!needsMlKit) excludes += "**/liblanguage_id_l2c_jni.so"
		}
	}

	// 4.4: ni el modelo de ML Kit, 0,30 MB.
	//
	// Por las vías de AGP no se puede. Se probaron las dos y ninguna
	// llega a los assets que vienen de una dependencia fusionada:
	// `packaging.resources.excludes` y
	// `androidResources.ignoreAssetsPattern` se aplican a los assets
	// de la app, y el archivo se cuela sin decir nada. Se borra del
	// directorio de fusionados justo después de fusionarlos, que es
	// el punto en el que sí existe.
	if (!needsMlKit) {
		tasks.matching { it.name.matches(Regex("merge.+Assets")) }.configureEach {
			doLast {
				var gone = 0
				outputs.files.files
					.filter { it.isDirectory }
					.forEach { dir ->
						dir.walkTopDown()
							.filter { it.isFile && it.name == "tflite_langid.tflite.jpg" }
							.forEach { if (it.delete()) gone++ }
					}
				if (gone > 0) logger.lifecycle("lite: fuera el modelo de ML Kit ($gone)")
			}
		}
	}

	splits {
		abi {
			isEnable = (findProperty("evvdroid.abiSplits") as String?)?.toBoolean() ?: true
			reset()
			include(*abis.toTypedArray())
			isUniversalApk = true
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}

	buildFeatures {
		buildConfig = true
		compose = true
	}
}

// 5.2: `kotlinOptions.jvmTarget` está obsoleto en Kotlin 2.x y avisa
// en cada configuración del proyecto. El destino es el mismo (17, el
// que piden las bibliotecas de AndroidX y el que usa `compileOptions`
// arriba), solo cambia la forma de decirlo.
kotlin {
	compilerOptions {
		jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
	}
}


// 4.5: renombrar APKs en su lugar (move, no copy) al patrón:
// reloquence-<edition>-<version>-<versionCode>-<abi>-release.apk
val renameApks = tasks.register("renameApks") {
	group = "distribution"
	description = "Renombra los APKs in-place al patrón con versión, código y ABI."
	doLast {
		val edition = if (isLite) "lite" else "full"
		val versionName = editionVersionName
		val versionCode = editionVersionCode
		val dir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
		dir.listFiles(object : java.io.FileFilter {
			override fun accept(f: java.io.File): Boolean =
				f.name.endsWith("-release.apk") && f.name.startsWith("app-")
		})?.forEach { file ->
			val base = file.name.removeSuffix("-release.apk")
			val abi = base.removePrefix("app-")
			val newName = if (abi == "universal") {
				"reloquence-$edition-$versionName-$versionCode-universal-release.apk"
			} else {
				"reloquence-$edition-$versionName-$versionCode-$abi-release.apk"
			}
			val target = file.toPath().resolveSibling(newName)
				Files.move(file.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
			logger.lifecycle("renamed: ${file.name} -> $newName")
		}
	}
}

tasks.named("preBuild") {
	// El release ya construyó el motor como paso explícito.
	if (!skipNative) dependsOn(buildNative)
}



/**
 * 4.4: los APK se copian a `dist/<edición>/release/`, que es donde
 * hay que cogerlos.
 *
 * No es por gusto. `buildDirectory` depende de `evvdroid.edition`, así
 * que compilar la edición contraria le dice a Gradle que la carpeta
 * de salida anterior está obsoleta, y Gradle la **borra**: compilar la
 * normal después de la Lite se llevaba sus tres APK, y al revés
 * igual. Se comprobó las dos veces.
 *
 * Depender solo de carpetas distintas dentro de `build/` no vale,
 * el aviso de "salida obsoleta" es del propio Gradle, y pregunta dónde
 * escribiría la tarea, no qué hay escrito.
 *
 * `dist/` está fuera de `build/` y no la toca nada de esto, así que
 * las dos ediciones se quedan ahí juntas. Va automáticamente con
 * `assembleRelease`, no hay que acordarse de copiar nada.
 */
val distDir = rootProject.layout.projectDirectory.dir("dist/$edition/release")

/**
 * 5.4: copia a `dist/<edición>/release/` los APK de esta versión.
 *
 * No se usa una tarea `Copy` a propósito: `renameApks` mueve los
 * archivos por fuera de las APIs de Gradle y, en Windows, la caché
 * de sistema de archivos (VFS) puede seguir viendo la carpeta vacía
 * o con los nombres viejos. El resultado era un `NO-SOURCE` con los
 * APK perfectamente escritos en disco. Listar y copiar aquí, en el
 * momento de ejecutar, evita esa caché.
 */
val publishApks by tasks.registering {
	group = "distribution"
	description = "Copia los APK de la edición $edition a dist/$edition."
	dependsOn(renameApks)
	outputs.upToDateWhen { false }
	doLast {
		val source = layout.buildDirectory.dir("outputs/apk/release").get().asFile
		val target = distDir.asFile
		val prefix = "reloquence-$edition-$editionVersionName-$editionVersionCode-"
		val apks = source.listFiles { file ->
			file.isFile && file.name.startsWith(prefix) && file.name.endsWith(".apk")
		}.orEmpty()
		if (apks.isEmpty()) {
			throw GradleException(
				"No hay APK de $edition $editionVersionName ($editionVersionCode) en $source"
			)
		}
		target.mkdirs()
		apks.forEach { apk ->
			val destination = target.resolve(apk.name)
			Files.copy(apk.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
			logger.lifecycle("copied: ${apk.name} -> $destination")
		}
	}
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
	finalizedBy(publishApks)
}

/**
 * 5.4: las dos versiones en una línea, para que un flujo de trabajo
 * o un script no tengan que interpretar `version.properties` a mano.
 */
tasks.register("printVersions") {
	group = "help"
	description = "Imprime las versiones de las dos ediciones."
	doLast {
		println("full.versionName=$fullVersionName")
		println("full.versionCode=$fullVersionCode")
		println("lite.versionName=$liteVersionName")
		println("lite.versionCode=$liteVersionCode")
	}
}

dependencies {
	implementation(libs.androidx.core.ktx)
	implementation(libs.material)
	implementation(libs.androidx.activity.compose)
	implementation(platform(libs.compose.bom))
	implementation(libs.compose.ui)
	implementation(libs.compose.material3)
	implementation(libs.compose.material.icons.extended)
	// 4.1: hilos modernos, ViewModel + navegación, perfiles en JSON,
	// registro y perfiles base en dispositivos viejos.
	implementation(libs.kotlinx.coroutines.android)
	implementation(libs.androidx.lifecycle.viewmodel)
	implementation(libs.androidx.lifecycle.viewmodel.compose)
	implementation(libs.androidx.lifecycle.runtime.compose)
	implementation(libs.androidx.lifecycle.runtime.ktx)
	implementation(libs.androidx.navigation.compose)
	implementation(libs.kotlinx.serialization.json)
	implementation(libs.timber)
	implementation(libs.commons.text)
	// 4.4: ML Kit se enlaza siempre, pero en la edición Lite el
	// código acaba fuera del APK y su biblioteca y su modelo no se
	// empaquetan.
	//
	// La dependencia no puede desaparecer del classpath de
	// compilación, porque [AutoLang] tiene el tipo de ML Kit en la
	// firma de su campo `client`, y sin la clase no compila. Lo que
	// se hace es lo de siempre con esto: poner la llamada detrás de
	// una constante de BuildConfig, que R8 reduce antes de quitar el
	// código muerto. Con la rama fuera, todo lo de ML Kit queda
	// inalcanzable y se va del dex; y como ya no se carga, su
	// biblioteca nativa y su modelo no hacen falta en el paquete.
	//
	// El interruptor de autodetección y la heurística de palabras no
	// dependen de esto: es la persona la que decide si autodetecta, y
	// lo que oye es lo mismo. Ver [needsMlKit] para la demostración
	// de que el resultado no puede cambiar nada con dos motores de
	// español, que es lo que hace falta para quitarlo.
	implementation(libs.mlkit.language.id)
	implementation(libs.androidx.profileinstaller)

	testImplementation(libs.junit)

	androidTestImplementation(libs.junit)
	androidTestImplementation(libs.androidx.test.junit)
	androidTestImplementation(libs.androidx.test.runner)
	androidTestImplementation(libs.androidx.test.core)
}
