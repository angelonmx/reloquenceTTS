# Compilación

Regla: siempre en **release**, con esta firma y separado por ABI. `full` y `lite` se compilan y firman por separado.
Solo compilar en debug si se pide explícitamente.

Firma: `keystore.properties` (no se toca ni se sube).
Siempre se firma con el almacén
`C:\Users\Angel\Documents\Mis proyectos\Documentos mios\mi_firma.keystore`,
alias `angelonmxGMZBestCreator`, cuyos datos están en
`C:\Users\Angel\Documents\Mis proyectos\Documentos mios\mi_firma.txt`.
Si `keystore.properties` no apunta ahí, se corrige antes de compilar.
Separación: `evvdroid.abiSplits` es `true` por defecto, sin recortar idiomas.

## Las dos ediciones: 5.4

Hay dos ediciones y se compilan por separado:

| edición | versión | `applicationId` | idiomas |
| --- | --- | --- | --- |
| `full` (la de siempre) | 5.4 (5400) | `com.angelonmx.reloquence` | los nueve módulos |
| `lite` | 2.4 (2400) | `com.angelonmx.reloquence.lite` | solo español (España y Latinoamérica) |

5.1 comprime las bibliotecas nativas (`useLegacyPackaging = true`): el APK baja, y Android las extrae al instalar. Esa extracción es el precio de la compresión; no se toca el motor ni la voz. 5.4 añade el apartado de actualizaciones y centraliza las versiones en `version.properties`.

Las versiones ya no se escriben en `app/build.gradle.kts`. El único sitio donde se cambian es `version.properties`, en la raíz:

```properties
full.versionName=5.4
full.versionCode=5400
lite.versionName=2.4
lite.versionCode=2400
```

Gradle lo lee al configurar, y `renameApks`, `publishApks`, los manifiestos de `updates/` y los flujos de GitHub Actions usan el mismo valor. Para verlas sin compilar:

```powershell
.\gradlew.bat :app:printVersions
```

Se eligen con `evvdroid.edition`:

```powershell
.\gradlew.bat clean assembleRelease
.\gradlew.bat clean assembleRelease "-Pevvdroid.edition=lite"
```

Cada `assembleRelease` ejecuta automáticamente `renameApks` y `publishApks`. **Orden recomendado:** compilar `full` primero y luego `lite`; cada edición usa su propia carpeta `app/build/<edition>` y se publica en `dist/<edition>/release/`.

**Arquitecturas:** solo `arm64-v8a` y `armeabi-v7a` (móviles ARM). **No x86_64** (solo emulador/Chromebook; si distribuyes APK directo a móviles no sirve).

**Nombrado de APK (5.4):** `reloquence-<edition>-<version>-<versionCode>-<abi>-release.apk`.  
Ejemplos:
- `reloquence-full-5.4-5400-arm64-v8a-release.apk`
- `reloquence-full-5.4-5400-armeabi-v7a-release.apk`
- `reloquence-full-5.4-5400-universal-release.apk`
- `reloquence-lite-2.4-2400-arm64-v8a-release.apk`
- `reloquence-lite-2.4-2400-armeabi-v7a-release.apk`
- `reloquence-lite-2.4-2400-universal-release.apk`

**Renombrado in-place (5.4):** la tarea `renameApks` renombra los archivos originales **en su lugar** (`Files.move` con `REPLACE_EXISTING`), no crea copias. Los archivos `app-*-release.apk` pasan a llamarse `reloquence-<edition>-<version>-<code>-<abi>-release.apk` directamente en `build/<edition>/outputs/apk/release/`.

Los APKs finales se copian automáticamente a `dist/<edition>/release/` por la tarea `publishApks` (enganchada a `assembleRelease`). Ahí es donde hay que recogerlos.

**Orden de compilación:**
1. `full` (compila, renombra y publica a `dist/full/`)
2. `lite` (compila, renombra y publica a `dist/lite/`)

## Firma: full y lite por separado

`full` y `lite` se **firman por separado**. No se firma una edición y se copia el APK a la otra: cada `assembleRelease` produce y firma sus propios APK (por ABI y universal) en `build/<edition>/` y los publica en `dist/<edition>/release/`.

Las dos ediciones deben usar **la misma clave** de `keystore.properties` (la de `mi_firma.keystore`). Así cada paquete puede actualizarse después sin desinstalar. Lo que cambia entre ediciones es el `applicationId` y el contenido, no la firma.

La firma la aplica Gradle automáticamente al compilar; no hay que volver a firmar los APK publicados. El orden correcto es:

```powershell
.\gradlew.bat assembleRelease
.\gradlew.bat assembleRelease "-Pevvdroid.edition=lite"
```

Cada ejecución firma sus propios APK con la configuración `release` y `enableV3Signing = true`. No se debe reutilizar el APK de `full` para `lite` ni al contrario.

**Comprobar que ambas quedaron firmadas:**

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk\build-tools\37.0.0"
$full = "dist\full\release\reloquence-full-5.4-5400-universal-release.apk"
$lite = "dist\lite\release\reloquence-lite-2.4-2400-universal-release.apk"

& "$sdk\apksigner.bat" verify --verbose --print-certs $full
& "$sdk\apksigner.bat" verify --verbose --print-certs $lite

& "$sdk\aapt2.exe" dump badging $full | Select-String "^package:"
& "$sdk\aapt2.exe" dump badging $lite | Select-String "^package:"
```

Las dos deben mostrar `V3.0 Signer`, el mismo DN y la misma huella SHA-256. Los paquetes deben ser distintos: `com.angelonmx.reloquence` para full y `com.angelonmx.reloquence.lite` para Lite. Si alguna no está firmada o la huella no coincide, no distribuirla: revisar `keystore.properties` y `mi_firma.txt`.

**Firma manual (solo si falla la automática):** partir del APK sin firmar, alinear antes de firmar y repetir el proceso para cada edición por separado.

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk\build-tools\37.0.0"
& "$sdk\zipalign.exe" -f -p 4 "entrada-sin-firmar.apk" "alineado.apk"
& "$sdk\apksigner.bat" sign `
	--ks "C:\Users\Angel\Documents\Mis proyectos\Documentos mios\mi_firma.keystore" `
	--ks-key-alias angelonmxGMZBestCreator `
	--out "salida-firmada.apk" `
	"alineado.apk"
& "$sdk\apksigner.bat" verify --verbose --print-certs "salida-firmada.apk"
```

`apksigner` pedirá las contraseñas; no escribirlas en el repositorio ni en este documento. Repetir con el APK de Lite y un nombre de salida distinto. No firmar los dos usando el mismo archivo de salida.

**El problema:** "lo detengo desde aplicaciones activas y suena raro; suena distinto hasta que toco un ajuste y ya suena bien".

**La causa:** tres puntos donde la primera emisión tras un reinicio podía salir con la voz por defecto en vez de la del usuario:
1. El motor calentado (`prewarm`) se abría sin voz del usuario; la primera emisión tenía que aplicársela, y si fallaba salía con voz por defecto.
2. `applySettings` fallaba en silencio en `readyEngine`: si el motor estaba ocupado, la emisión salía con la voz que tuviera el motor.
3. Si la primera petición llegaba mientras `prewarm` aún aplicaba voz, `ensureEngine` creaba un motor nuevo sin voz.

**La solución (4.5):**
* `prewarm` aplica la voz del usuario **antes** de guardar el motor calentado, con reintentos.
* `readyEngine` **nunca deja pasar una emisión sin voz**: reintenta `applySettings` hasta 4 veces; si agotara los intentos, descarta el motor y devuelve `null`.
* `ensureEngine` aplica la voz **también a motores nuevos** (cuando no hay warm engine), antes de guardarlos.

Resultado: la primera emisión tras CUALQUIER reinicio (system kill, user kill, reboot, actualización) **SIEMPRE suena con la voz que el usuario configuró**.

## Antes de compilar: las pruebas

4.3: el puente de efectos (`AudioFx.kt`), el codificador de texto
(`Prosody.kt`) y las reglas de lectura (`Dictionaries.kt`) son Kotlin
puro, sin nada de Android, así que se pueden medir en la máquina sin
un aparato. Eso es lo que comprueba que cada efecto haga lo que dice
y que apagado no haga nada, que es donde estaban los bugs:

```powershell
.\gradlew.bat testDebugUnitTest
```

En una máquina sin el motor nativo compilado se puede saltar su compilación:

```powershell
.\gradlew.bat testDebugUnitTest "-Pevvdroid.skipNative=true"
```

No hacen falta oídos: se mete un tono de una frecuencia y se mira
cuánto sale de esa misma frecuencia. "Entra 4 kHz, ¿cuánto sale?" es
la pregunta más limpia que se le puede hacer a un filtro, y tiene
respuesta exacta.

Las pruebas viven en `app\src\test\java` (no en `src\test\kotlin`)
porque el source set `test` no sale en el informe de AGP 8.13 y
registrarlo a mano deja la tarea en `NO-SOURCE`. Kotlin compila los
`.kt` que encuentra ahí sin decir nada más.

Son 167 y las de 4.5, 5.0, 5.1, 5.2 y 5.4 comprueban lo que aquí se cuenta:

| prueba | qué mira |
| --- | --- |
| `ProsodyTest` | que la predicción de frase salga apagada, y que un acento suelto no se coma la palabra que va detrás |
| `WesternTextSafetyTest` | que ningún carácter raro llegue al motor como cero, control o trozo perdido |
| `LanguageNamesTest` | que los dos módulos de español no se llamen igual, y que ninguno se llame México |
| `TtsPacerTest` | que el reloj de entrega empiece con el primer trozo y que un reinicio no arrastre la cuenta anterior |
| `TtsHealthTest` | que los contadores, la latencia válida y el idioma de la emisión lleguen intactos al diagnóstico |
| `PausesTest` | que los decimales, las horas y los nombres de archivo sigan sin ser puntuación, y que `¡`/`¿` no se hayan tocado |
| `PronunciationTest` | que la `y` suelta se lea "y griega" pero la conjunción no, que `muay thai`, grados y enteros suenen como se pide, y que `$` se lea completo en pesos y centavos |
| `CldrAssetsTest` | que las tablas CLDR y de nombres Unicode sigan generadas y empaquetadas |
| `BackupFormatTest` | que el JSON conserve los tipos, avise y omita valores inválidos, rechace versiones futuras, valide el diccionario y siga leyendo el formato de líneas viejo |
| `BackupGateTest` | que la puerta de importación conozca todas las claves y que cada una tenga un solo tipo esperado |
| `UpdateManifestTest` | que el manifiesto rechace otra app, otra edición, otra versión, URLs que no son HTTPS y huellas mal formadas, y que elija el APK de la ABI correcta |

## GitHub Actions

Hay dos flujos:

- `.github/workflows/ci.yml` — pruebas unitarias y compilación de la edición Lite en cada push o pull request. Usa `-Pevvdroid.skipNative=true` porque las pruebas no necesitan el motor nativo.
- `.github/workflows/release.yml` — compila el motor y los APK de las dos ediciones, los firma, crea la release y actualiza `updates/full.json` y `updates/lite.json`.

El flujo de publicación necesita cuatro secretos (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) y un repositorio público para que la app pueda leer los manifiestos. El proceso completo, con el script `tools/publish-secrets.ps1`, está en [PUBLICAR.md](PUBLICAR.md).

La URL base de las actualizaciones se inyecta al compilar:

```powershell
.\gradlew.bat assembleRelease `
  "-Preloquence.updateBaseUrl=https://raw.githubusercontent.com/tu-usuario/tu-repo/main/updates"
```

El valor por defecto está en `gradle.properties`. Tiene que ser HTTPS: la app rechaza cualquier otra cosa antes de descargar nada.

Solo si se pide debug:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```
