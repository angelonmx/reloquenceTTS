# Publicar una versión

Esta es la parte que se hace **una vez** para dejar el repositorio listo, y
después se repite en cada versión. La compilación y la publicación las hace
GitHub Actions; no hay que subir APK a mano.

## 1. El repositorio tiene que ser público

La app busca las actualizaciones en:

```
https://raw.githubusercontent.com/<propietario>/<repositorio>/main/updates/full.json
https://raw.githubusercontent.com/<propietario>/<repositorio>/main/updates/lite.json
```

`raw.githubusercontent.com` no sirve archivos de repositorios privados sin un
token. Como la app no lleva token, **el repositorio tiene que ser público** para
que la comprobación funcione. Si no quieres que el código sea público, las
alternativas son:

- publicar solo la carpeta `updates/` en otro repositorio público y cambiar
  `reloquence.updateBaseUrl` en `gradle.properties`;
- servir los dos JSON desde GitHub Pages o desde un servidor propio con HTTPS.

La app rechaza cualquier URL que no sea HTTPS.

## 2. Crear el repositorio y subir el código

El repositorio remoto ya está creado en `angelonmx/reloquenceTTS` y la copia
local ya tiene `origin`. Esta sección solo se necesita si se crea otro
repositorio desde cero; para publicar la siguiente versión, ir directamente a
la sección 4.

Si no tienes GitHub CLI:

```powershell
winget install --id GitHub.cli --exact
```

En esta copia el repositorio local ya está inicializado, en `main` y con el
primer commit hecho. Si partes de una copia sin `.git`, ejecuta antes estas
cuatro líneas:

```powershell
git init
git add .
git commit -m "reloquenceTTS 5.4.2"
git branch -M main
```

Después, en la carpeta del proyecto:

```powershell
gh repo create angelonmx/reloquenceTTS --public --source=. --remote=origin --push
```

`native/openevv` es un submódulo. Si el clon no lo trae:

```powershell
git submodule update --init --recursive
```

Comprueba que Git lo trata como submódulo y no como una carpeta normal:

```powershell
git submodule status
```

## 3. Guardar la firma en GitHub Secrets

**Nunca** subas `mi_firma.keystore`, `keystore.properties` ni las contraseñas al
repositorio. El `.gitignore` ya los excluye, pero conviene saberlo.

Inicia sesión en GitHub CLI:

```powershell
gh auth login
```

Después, desde la raíz del proyecto:

```powershell
.\tools\publish-secrets.ps1 -Repo angelonmx/reloquenceTTS
```

El script lee `keystore.properties`, comprueba que el almacén existe y crea estos
cuatro secretos del repositorio:

| secreto | qué es |
| --- | --- |
| `KEYSTORE_BASE64` | el archivo `.keystore` codificado en base64 |
| `KEYSTORE_PASSWORD` | la contraseña del almacén |
| `KEY_ALIAS` | el alias de la clave |
| `KEY_PASSWORD` | la contraseña de la clave |

No imprime los valores. Si prefieres hacerlo a mano:

```powershell
$bytes = [IO.File]::ReadAllBytes("C:\ruta\mi_firma.keystore")
[Convert]::ToBase64String($bytes) | gh secret set KEYSTORE_BASE64 --repo angelonmx/reloquenceTTS
"la-contraseña-del-almacén" | gh secret set KEYSTORE_PASSWORD --repo angelonmx/reloquenceTTS
"angelonmxGMZBestCreator" | gh secret set KEY_ALIAS --repo angelonmx/reloquenceTTS
"la-contraseña-de-la-clave" | gh secret set KEY_PASSWORD --repo angelonmx/reloquenceTTS
```

Comprueba que están los cuatro, sin ver el contenido:

```powershell
gh secret list --repo angelonmx/reloquenceTTS
```

Si alguna vez se filtra la clave, cambia la contraseña del almacén, genera uno
nuevo y actualiza los secretos. Una clave filtrada permite firmar APK que
Android aceptaría como actualizaciones de esta app.

El flujo de publicación comprueba que la huella SHA-256 del certificado sea
`4e516988538b3d20a8fe9bf40a14edd4e277feee97275aac874f0de495578717`, la de la
clave con la que se firmaron las versiones 5.4.2 y 2.4.2. Si algún día cambias de
almacén a propósito, actualiza `EXPECTED_CERT` en
`.github/workflows/release.yml`; las instalaciones existentes no podrán
actualizarse con una clave distinta.

## 4. Preparar la versión

Las versiones viven en `version.properties` y en ningún otro sitio:

```properties
full.versionName=5.4.2
full.versionCode=5402
lite.versionName=2.4.2
lite.versionCode=2402
```

Reglas:

- `versionCode` siempre sube. Es lo que compara la app; el `versionName` es solo
  el texto que se enseña.
- Las dos ediciones pueden tener números distintos, pero cada una mantiene su
  propia serie.
- Añade la entrada de la versión nueva en `app/src/main/assets/changelog.json`,
  en `full` y en `lite`. De ahí salen las notas del diálogo y de la release.
- No cambies la firma entre versiones de la misma edición: Android rechazaría la
  actualización.

Después:

```powershell
git add version.properties app/src/main/assets/changelog.json
git commit -m "Preparar la versión 5.4.2"
git push
```

## 5. Lanzar la publicación

Desde Actions, entra en **Publicar** y pulsa **Run workflow**. También puedes
usar la terminal:

```powershell
gh workflow run release.yml --repo angelonmx/reloquenceTTS
gh run watch --repo angelonmx/reloquenceTTS
```

El flujo hace esto:

1. compila el motor nativo y los APK de `full` y `lite` en paralelo, para
   `arm64-v8a`, `armeabi-v7a` y universal;
2. firma cada APK con los secretos;
3. comprueba la firma con `apksigner`;
4. crea o actualiza la release `v<versión full>` con todos los APK;
5. genera `updates/full.json` y `updates/lite.json` con tamaño, SHA-256 y URL de
   cada APK;
6. hace commit de esos dos manifiestos en `main`.

La etiqueta por defecto es `v5.4.2` para la versión full. Si quieres otra, escribe
el nombre en el campo **Etiqueta** al lanzar el flujo. También puedes publicar
empujando una etiqueta:

```powershell
git tag v5.4.2
git push origin v5.4.2
```

La primera compilación tarda más porque compila el motor desde cero. Las
siguientes reutilizan la caché del submódulo y de Gradle.

## 6. Comprobar que la app lo ve

Cuando termine el flujo, abre en el navegador:

```
https://raw.githubusercontent.com/angelonmx/reloquenceTTS/main/updates/full.json
```

Tiene que aparecer la versión nueva y, en `apks`, una entrada por arquitectura
con su `sha256`. Si el archivo sigue con `"apks": []`, el paso de los manifiestos
no llegó a `main`.

En el teléfono, abre **Ajustes > Actualizaciones > Buscar actualizaciones**. Si
ya tienes la versión nueva, dirá que estás al día; si tienes la anterior, ofrecerá
la descarga y el instalador de Android pedirá confirmación.

`raw.githubusercontent.com` puede tardar unos minutos en servir la versión nueva
por la caché. No es un fallo de la app.

## 7. Problemas frecuentes

| síntoma | causa probable |
| --- | --- |
| `Falta el secreto KEYSTORE_BASE64` | no se ejecutó `publish-secrets.ps1` o el secreto está en otro repositorio |
| `apksigner` falla | la contraseña, el alias o el archivo no coinciden con `keystore.properties` |
| la release se crea pero la app no ve nada | el repositorio es privado, o el commit de `updates/*.json` no llegó a `main` |
| `git push` del flujo falla | `main` está protegida y el bot no puede escribir; permite a GitHub Actions o desactiva la protección |
| la app dice «no válida» | el APK del manifiesto no coincide en SHA-256, paquete, versión o certificado; vuelve a generar los manifiestos |
| una instalación desde GitHub no actualiza la de Play | Play App Signing vuelve a firmar; los APK de Play y los de GitHub no son intercambiables |

## Play Store

Si publicas también en Google Play, ten en cuenta:

- Play exige usar su sistema de facturación para contenido digital, pero esta app
  no vende nada.
- Play App Signing puede volver a firmar el APK. Ese APK no se puede actualizar
  con el de GitHub, ni al revés, porque el certificado cambia.
- Las políticas de Play sobre actualizaciones fuera de la tienda afectan a las
  apps distribuidas en Play. Esta app usa su propio actualizador porque se
  distribuye como APK directo; si algún día entra en Play, hay que revisar ese
  punto antes de publicar.
