# Sube la firma a los secretos de GitHub.
#
# Uso, desde la raíz del proyecto:
#
#     .\tools\publish-secrets.ps1 -Repo angelonmx/reloquenceTTS
#
# Requiere GitHub CLI (`gh`) y haber hecho `gh auth login`.
# No imprime contraseñas ni el contenido del almacén. No sube nada
# al repositorio: los secretos se guardan cifrados en GitHub.

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Repo
)

$ErrorActionPreference = "Stop"

function Fail([string]$Message) {
    Write-Host "error: $Message" -ForegroundColor Red
    exit 1
}

$root = Split-Path -Parent $PSScriptRoot
$propertiesPath = Join-Path $root "keystore.properties"
if (-not (Test-Path $propertiesPath)) {
    Fail "no existe keystore.properties en $root"
}

# Formato clave=valor, igual que lo lee Gradle.
$properties = @{}
Get-Content $propertiesPath | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq "" -or $line.StartsWith("#")) { return }
    $at = $line.IndexOf("=")
    if ($at -lt 1) { return }
    $properties[$line.Substring(0, $at).Trim()] = $line.Substring($at + 1).Trim()
}

foreach ($key in @("storeFile", "storePassword", "keyAlias", "keyPassword")) {
    if (-not $properties.ContainsKey($key) -or [string]::IsNullOrEmpty($properties[$key])) {
        Fail "falta $key en keystore.properties"
    }
}

$storePath = $properties["storeFile"]
if (-not [IO.Path]::IsPathRooted($storePath)) {
    $storePath = Join-Path $root $storePath
}
if (-not (Test-Path $storePath -PathType Leaf)) {
    Fail "no existe el almacén de claves: $storePath"
}

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    Fail "no se encontró GitHub CLI (gh). Instálalo desde https://cli.github.com/"
}

& gh auth status --hostname github.com 2>$null
if ($LASTEXITCODE -ne 0) {
    Fail "GitHub CLI no tiene sesión. Ejecuta: gh auth login"
}

Write-Host "codificando $([IO.Path]::GetFileName($storePath))…"
$base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($storePath))

# gh lee el valor de la entrada estándar cuando no se pasa --body.
$secrets = [ordered]@{
    KEYSTORE_BASE64     = $base64
    KEYSTORE_PASSWORD   = $properties["storePassword"]
    KEY_ALIAS           = $properties["keyAlias"]
    KEY_PASSWORD        = $properties["keyPassword"]
}

foreach ($name in $secrets.Keys) {
    Write-Host "subiendo $name…"
    $secrets[$name] | & gh secret set $name --repo $Repo
    if ($LASTEXITCODE -ne 0) {
        Fail "no se pudo guardar el secreto $name"
    }
}

Write-Host ""
Write-Host "secretos guardados en $Repo. Comprueba los nombres con:" -ForegroundColor Green
Write-Host "    gh secret list --repo $Repo"
