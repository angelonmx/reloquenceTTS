# Manifiestos de actualización

La app no consulta la API de GitHub. Descarga uno de estos dos archivos
pequeños y estáticos:

```
updates/full.json   -> edición full  (com.angelonmx.reloquence)
updates/lite.json   -> edición lite  (com.angelonmx.reloquence.lite)
```

Los genera `.github/workflows/release.yml` con `tools/update_manifest.py`
después de compilar y firmar los APK. En el repositorio quedan versionados
para que la app pueda leerlos desde `raw.githubusercontent.com`.

## Forma

```json
{
  "schema": 1,
  "applicationId": "com.angelonmx.reloquence",
  "edition": "full",
  "versionName": "5.4",
  "versionCode": 5400,
  "publishedAt": "2026-10-27T00:00:00Z",
  "minSdk": 23,
  "notes": {
    "es": ["..."],
    "en": ["..."]
  },
  "apks": [
    {
      "abi": "arm64-v8a",
      "url": "https://github.com/angelonmx/reloquenceTTS/releases/download/v5.4/reloquence-full-5.4-5400-arm64-v8a-release.apk",
      "sha256": "64 dígitos hexadecimales",
      "size": 12345678
    }
  ]
}
```

## Reglas

- `versionCode` es lo único que decide si hay una versión nueva. El
  `versionName` solo se enseña.
- `applicationId` y `edition` tienen que coincidir con la instalación.
  Así un APK de Lite no se ofrece a la app full.
- `apks` puede estar vacío mientras no haya release. En ese caso, si la
  versión es la misma, la app responde «ya estás al día»; si la versión
  es más nueva, responde que no hay APK.
- Cada APK lleva su `sha256` y su `size`. La app los comprueba mientras
  descarga; después comprueba el paquete, el `versionCode` y el
  certificado de firma antes de abrir el instalador.
- Las URL tienen que ser HTTPS.

## A mano

```powershell
python tools/update_manifest.py `
  --edition full `
  --version-name 5.4 --version-code 5400 `
  --application-id com.angelonmx.reloquence `
  --apk-dir dist/full/release `
  --base-url https://github.com/angelonmx/reloquenceTTS/releases/download/v5.4 `
  --output updates/full.json
```

No edites los manifiestos a mano para una release: el flujo los
sobrescribe. Sí puedes editarlos para probar una URL o un servidor propio.

## Repositorio privado

`raw.githubusercontent.com` no entrega archivos de repositorios privados
sin token, y la app no lleva token. Para que la comprobación funcione, el
repositorio (o al menos esta carpeta) tiene que ser público, o hay que
servir los JSON desde GitHub Pages o desde un servidor propio con HTTPS.
