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

## Versiones: siempre `mayor.importante.parche`

Las dos ediciones usan tres números y nunca se abrevian a dos. El tercero es el parche, el segundo la actualización importante y el primero la actualización mayor:

| tipo | qué sube | ejemplo | `versionCode` |
| --- | --- | --- | --- |
| parche | correcciones, latencia, estabilidad; sin funciones nuevas grandes | 5.4.1 → 5.4.2 | 5401 → 5402 |
| importante | funciones nuevas compatibles | 5.4.1 → 5.5.0 | 5401 → 5500 |
| mayor | cambio grande o incompatible | 5.4.1 → 6.0.0 | 5401 → 6000 |

### Qué número se sube según lo que se pida

El tipo de publicación lo decide **lo que pida el usuario**, no la cantidad de archivos tocados ni el tamaño del cambio:

| si el usuario pide | qué se hace | ejemplo |
| --- | --- | --- |
| un **parche** (correcciones, latencia, estabilidad) | sube solo el tercer número | 5.4.1 → 5.4.2; Lite 2.4.1 → 2.4.2 |
| una **actualización importante** | sube el segundo y el tercero vuelve a 0 | 5.4.1 → 5.5.0; Lite 2.4.1 → 2.5.0 |
| una **actualización mayor** o **madre** | sube el primero y los otros dos vuelven a 0 | 5.4.1 → 6.0.0; Lite 2.4.1 → 3.0.0 |

"Añade esto", "corrige aquello" o "prepara la siguiente" sin más contexto no autoriza a inventar el tipo: si no está claro si es parche, importante o mayor, se pregunta antes de tocar `version.properties`. Una publicación pequeña puede ser importante si así se pidió, y una grande puede ser parche si así se pidió.

Reglas:

- Siempre se escriben los tres números en `versionName` (`5.4.2`, no `5.4` ni `5.4.2-beta`).
- El parche va de 0 a 99. Al llegar a 99, se sube el importante y el parche vuelve a 0.
- `versionCode = mayor * 1000 + importante * 100 + parche`. Así 5.4.2 es 5402 y 2.4.2 es 2402. Android solo acepta una actualización si este número sube.
- Full y Lite mantienen series propias: full va por 54xx y Lite por 24xx. Las dos ediciones de una misma publicación suben el mismo tipo de número.
- El único archivo donde se cambian es `version.properties`.

## El repositorio y la sesión de GitHub

Al día de hoy, el repositorio ya existe y está configurado:

- https://github.com/angelonmx/reloquenceTTS — público, rama `main`.
- La app lee los manifiestos de `https://raw.githubusercontent.com/angelonmx/reloquenceTTS/main/updates/`.
- Los cuatro secretos de firma (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) ya están cargados en el repositorio.
- La última release publicada es la 5.4.2 (Lite 2.4.2) y los manifiestos están al día.

La sesión de GitHub CLI ya está iniciada en esta máquina. `gh auth login` guarda el token en el Administrador de credenciales de Windows, así que sobrevive a cerrar la terminal y a reiniciar el equipo. **No es permanente en sentido absoluto:** el token se puede revocar, caducar o perderse al cambiar de usuario o de máquina. Antes de publicar, comprobar:

```powershell
gh auth status
```

Debe decir `Logged in to github.com account angelonmx` y mostrar los scopes `repo` y `workflow`. Si falta `workflow` (lo necesita el flujo para crear la release y escribir los manifiestos):

```powershell
gh auth refresh -h github.com -s workflow
```

Para confirmar que el repositorio sigue público y en `main`:

```powershell
gh repo view angelonmx/reloquenceTTS --json name,visibility,defaultBranchRef
```

Debe responder `"visibility": "PUBLIC"` y `"name": "main"`.

Si no hay sesión, iniciarla de nuevo:

```powershell
gh auth login
```

Elegir GitHub.com, HTTPS y el inicio de sesión por navegador. No pegar el token en el chat ni en ningún archivo del repositorio: los flujos usan el token propio de GitHub Actions y los secretos de firma.

`gh` suele estar en `C:\Program Files\GitHub CLI\gh.exe`. Si una terminal recién abierta no lo encuentra en el `PATH`, cerrarla y volver a abrirla, o llamarlo con esa ruta completa.

El repositorio debe seguir siendo **público**: `raw.githubusercontent.com` no sirve los manifiestos de un repositorio privado y la app no lleva token. Si algún día se hace privado, hay que cambiar `reloquence.updateBaseUrl` o servir los JSON desde GitHub Pages o un servidor propio con HTTPS.

## Las dos ediciones: 5.4.2

Hay dos ediciones y se compilan por separado:

| edición | versión | `applicationId` | idiomas |
| --- | --- | --- | --- |
| `full` (la de siempre) | 5.4.2 (5402) | `com.angelonmx.reloquence` | los nueve módulos |
| `lite` | 2.4.2 (2402) | `com.angelonmx.reloquence.lite` | solo español (España y Latinoamérica) |

5.1 comprime las bibliotecas nativas (`useLegacyPackaging = true`): el APK baja, y Android las extrae al instalar. Esa extracción es el precio de la compresión; no se toca el motor ni la voz. 5.4 añadió el apartado de actualizaciones y centralizó las versiones en `version.properties`; 5.4.1 fue el parche de latencia, prosodia y copias de seguridad, y 5.4.2 remata lo que quedaba: presets de voz, diccionario duplicado y descarga pendiente tras cerrar la app.

Las versiones ya no se escriben en `app/build.gradle.kts`. El único sitio donde se cambian es `version.properties`, en la raíz:

```properties
full.versionName=5.4.2
full.versionCode=5402
lite.versionName=2.4.2
lite.versionCode=2402
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

**Nombrado de APK (5.4.2):** `reloquence-<edition>-<version>-<versionCode>-<abi>-release.apk`.

Ejemplos:
- `reloquence-full-5.4.2-5402-arm64-v8a-release.apk`
- `reloquence-full-5.4.2-5402-armeabi-v7a-release.apk`
- `reloquence-full-5.4.2-5402-universal-release.apk`
- `reloquence-lite-2.4.2-2402-arm64-v8a-release.apk`
- `reloquence-lite-2.4.2-2402-armeabi-v7a-release.apk`
- `reloquence-lite-2.4.2-2402-universal-release.apk`

**Renombrado in-place (5.4):** la tarea `renameApks` renombra los archivos originales **en su lugar** (`Files.move` con `REPLACE_EXISTING`), no crea copias. Los archivos `app-*-release.apk` pasan a llamarse `reloquence-<edition>-<version>-<code>-<abi>-release.apk` directamente en `build/<edition>/outputs/apk/release/`.

Los APKs finales se copian automáticamente a `dist/<edition>/release/` por la tarea `publishApks` (enganchada a `assembleRelease`). Ahí es donde hay que recogerlos.

**Orden de compilación:**
1. `full` (compila, renombra y publica a `dist/full/`)
2. `lite` (compila, renombra y publica a `dist/lite/`)

## Compilar en esta máquina

La compilación de publicación la hace GitHub Actions en Linux. En Windows se compila igual para probar, pero los APK resultantes **no son los mismos bytes** que los publicados y no deben subirse a mano: la release y los manifiestos los genera el flujo.

Hace falta JDK 17 y el SDK de Android 37 con `build-tools;37.0.0`. El NDK `26.1.10909125` solo se usa cuando se compila el motor nativo.

Receta rápida, con las versiones actuales:

```powershell
# Ver qué versiones se van a compilar
.\gradlew.bat :app:printVersions

# Full, reutilizando el motor ya compilado
.\gradlew.bat assembleRelease "-Pevvdroid.skipNative=true"

# Lite, reutilizando el motor ya compilado
.\gradlew.bat assembleRelease "-Pevvdroid.edition=lite" "-Pevvdroid.skipNative=true"
```

`-Pevvdroid.skipNative=true` evita recompilar el motor nativo y solo es válido si `app/build/full/native/jniLibs` y `app/build/lite/native/jniLibs` ya tienen las bibliotecas. Por eso estas órdenes **no llevan `clean`**: `clean` borra `app/build/<edición>` y se llevaría por delante las bibliotecas. Si hace falta una compilación limpia o se tocó el motor, usar `clean assembleRelease` sin `skipNative` y dejar que el NDK lo compile; tarda varios minutos por ABI.

Los APK quedan en:

- `dist\full\release\reloquence-full-5.4.2-5402-<abi>-release.apk`
- `dist\lite\release\reloquence-lite-2.4.2-2402-<abi>-release.apk`

`dist/` está ignorado por Git. Antes de instalar o repartir un APK local, comprobar firma y paquete:

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk\build-tools\37.0.0"
$apk = "dist\full\release\reloquence-full-5.4.2-5402-universal-release.apk"

& "$sdk\apksigner.bat" verify --verbose --print-certs $apk
& "$sdk\aapt2.exe" dump badging $apk | Select-String "^package:"
```

Debe aparecer `V3.0 Signer`, la huella `4e516988538b3d20a8fe9bf40a14edd4e277feee97275aac874f0de495578717` y el paquete `com.angelonmx.reloquence` (o `com.angelonmx.reloquence.lite` para Lite). La comprobación completa de las dos ediciones está en la sección siguiente.

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
$full = "dist\full\release\reloquence-full-5.4.2-5402-universal-release.apk"
$lite = "dist\lite\release\reloquence-lite-2.4.2-2402-universal-release.apk"

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

Son 177 y las de 4.5, 5.0, 5.1, 5.2, 5.4, 5.4.1 y 5.4.2 comprueban lo que aquí se cuenta:

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
| `BackupFormatTest` | que el JSON conserve los tipos, avise y omita valores inválidos, rechace versiones futuras, valide el diccionario, restaure la biblioteca completa del formato v3 y siga leyendo el formato de líneas viejo |
| `DictionariesTest` | que la sustitución sea de una sola pasada, que la frase gane a la palabra en el mismo inicio, que no se solapen ni se vuelvan a sustituir los reemplazos y que las claves protegidas (mayúsculas estrictas, números puros y letras sueltas) solo entren donde deben |
| `EvvEngineTest` | que el preset de la app (0..7) se traduzca al del motor (1..8) al aplicar la emisión y al anunciar el reinicio de prosodia |
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

## Siguiente versión: qué se modifica

El proceso completo, con la creación del repositorio, los secretos y los problemas frecuentes, está en [PUBLICAR.md](PUBLICAR.md). Esta es la lista corta para cada versión.

Ejemplo de parche: pasar de 5.4.1 / Lite 2.4.1 a 5.4.2 / Lite 2.4.2. Para preparar la versión solo se tocan dos archivos; todo lo demás lo hace el flujo de publicación.

| archivo | qué se cambia |
| --- | --- |
| `version.properties` | `versionName` y `versionCode` de `full` y de `lite` |
| `app/src/main/assets/changelog.json` | una entrada nueva por edición, con las notas |
| `updates/full.json`, `updates/lite.json` | **nada**: los genera el flujo con URL, tamaño y SHA-256 |
| `app/build.gradle.kts`, firma, `applicationId` | **nada**: las versiones ya no viven ahí y la firma no se cambia entre versiones |

### 1. Subir las versiones

Editar `version.properties` según el tipo de publicación:

```properties
# parche: 5.4.1 → 5.4.2
full.versionName=5.4.2
full.versionCode=5402
lite.versionName=2.4.2
lite.versionCode=2402

# importante: 5.4.2 → 5.5.0
# full.versionName=5.5.0
# full.versionCode=5500
# lite.versionName=2.5.0
# lite.versionCode=2500

# mayor: 5.4.2 → 6.0.0
# full.versionName=6.0.0
# full.versionCode=6000
# lite.versionName=3.0.0
# lite.versionCode=3000
```

`versionCode` siempre sube: es lo que Android compara para aceptar una actualización. `versionName` es solo el texto que se muestra, pero también lleva los tres números. Cada edición mantiene su propia serie (full va por 54xx, Lite por 24xx) y las dos suben el mismo tipo de número. No cambiar el `applicationId` ni la clave de firma: Android rechazaría la actualización.

### 2. Añadir las notas

En `app/src/main/assets/changelog.json`, añadir al principio de `editions.full` y de `editions.lite` una entrada con el `versionCode` nuevo:

```json
{
  "versionName": "5.4.2",
  "versionCode": 5402,
  "date": "2026-10-27",
  "notes": {
    "es": ["Lo que cambia en esta versión."],
    "en": ["What changes in this version."]
  }
}
```

Para Lite, el bloque equivalente usa `"versionName": "2.4.2"` y `"versionCode": 2402`; la fecha es la del día. La release busca la entrada por `versionCode`, no por posición, pero las más nuevas van primero. De ahí salen las notas del diálogo de la app, el registro que se enseña tras instalar una actualización y las notas de GitHub Releases. Si falta la entrada, la release se publica con esa sección vacía.

### 3. Commit, push y esperar al CI

```powershell
git add version.properties app/src/main/assets/changelog.json
git commit -m "Preparar la versión 5.4.2"
git push origin main

gh run list --repo angelonmx/reloquenceTTS --limit 5
```

El CI ejecuta las 177 pruebas y compila Lite en cada push que toque código o configuración. Los cambios que solo afectan a `**/*.md` o `updates/**` no lo lanzan. No lanzar la release hasta que el CI esté en verde.

### 4. Lanzar la publicación

Antes de lanzar, `gh auth status` debe seguir mostrando la sesión de `angelonmx` y el scope `workflow`.

```powershell
gh workflow run release.yml --repo angelonmx/reloquenceTTS
gh run watch --repo angelonmx/reloquenceTTS
```

Si hay más de un flujo en cola, `gh run watch` puede pedir elegir; el de la release se llama **Publicar**. También se puede lanzar desde Actions > **Publicar** > **Run workflow**.

La etiqueta por defecto es `v<versión full>`, en el ejemplo `v5.4.2`. Para usar otra:

```powershell
gh workflow run release.yml --repo angelonmx/reloquenceTTS -f tag=v5.4.2
```

También se puede publicar empujando una etiqueta:

```powershell
git tag v5.4.2
git push origin v5.4.2
```

El flujo compila el motor nativo y los seis APK (full y Lite; arm64-v8a, armeabi-v7a y universal), comprueba la firma, crea o actualiza la release y escribe los manifiestos en `main`. La primera vez tarda más por el motor; las siguientes reutilizan la caché. Si hay que repetirlo, volver a lanzarlo es seguro: actualiza los APK de la release y vuelve a generar los manifiestos.

### 5. Comprobar el resultado

```powershell
gh release view v5.4.2 --repo angelonmx/reloquenceTTS
```

Deben aparecer los seis APK y las notas de las dos ediciones. Después, comprobar los manifiestos:

```powershell
Invoke-RestMethod https://raw.githubusercontent.com/angelonmx/reloquenceTTS/main/updates/full.json | ConvertTo-Json -Depth 6
Invoke-RestMethod https://raw.githubusercontent.com/angelonmx/reloquenceTTS/main/updates/lite.json | ConvertTo-Json -Depth 6
```

Tienen que mostrar la versión nueva y, en `apks`, una entrada por arquitectura con su `sha256`. `raw.githubusercontent.com` puede tardar unos minutos en servir la versión nueva por la caché; no es un fallo de la app. Por último, en el teléfono: **Ajustes > Actualizaciones > Buscar actualizaciones**. Android pedirá confirmación antes de instalar; no hay instalación silenciosa.

Solo si se pide debug:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```
