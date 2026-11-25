# Switch the launcher visual theme to any built-in theme in cleanyourtv\themes\ (or back to my-theme)
# while preserving your custom dock apps, app labels, App Library settings, Gamepad Passthrough, and remote keymap.
#
# Usage:
#   .\switch-theme.ps1 -List
#   .\switch-theme.ps1 aurora -TvHost 192.168.1.50
#   .\switch-theme.ps1 my-theme -TvHost 192.168.1.50
param(
    [Parameter(Position = 0)][string]$ThemeName,
    [string]$TvHost,
    [switch]$List
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$themesDir = Join-Path $root 'cleanyourtv\themes'
$themeScript = Join-Path $root 'cleanyourtv\tools\theme.ps1'

if ($List -or -not $ThemeName) {
    Write-Host "Available themes (custom: my-theme):"
    if (Test-Path $themesDir) {
        Get-ChildItem $themesDir -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'config.json') } |
            Select-Object -ExpandProperty Name | Sort-Object
    } else {
        Write-Host "  (Run .\apply-setup.ps1 first to clone cleanyourtv/themes/)"
    }
    if (-not $ThemeName) { exit 0 }
}

if (-not $TvHost) {
    throw "Please provide -TvHost <TV_IP> (e.g. .\switch-theme.ps1 aurora -TvHost 192.168.1.50)."
}

if ($ThemeName -eq 'my-theme') {
    $myTheme = Join-Path $root 'my-theme'
    powershell -ExecutionPolicy Bypass -File $themeScript -TvHost $TvHost $myTheme
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    exit 0
}

$sourceDir = Join-Path $themesDir $ThemeName
if (-not (Test-Path (Join-Path $sourceDir 'config.json'))) {
    throw "Theme '$ThemeName' does not exist in $themesDir. Run '.\switch-theme.ps1 -List' to see available themes."
}

$stagingDir = Join-Path $root '.active-theme'
if (Test-Path $stagingDir) { Remove-Item $stagingDir -Recurse -Force }
Copy-Item -Path $sourceDir -Destination $stagingDir -Recurse -Force

# Preserve personal functional keys from my-theme\config.json across visual theme switches
$baseCfg = Get-Content (Join-Path $root 'my-theme\config.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$themeCfg = Get-Content (Join-Path $stagingDir 'config.json') -Raw -Encoding UTF8 | ConvertFrom-Json

$keepKeys = @(
    'dock_apps', 'hidden_apps', 'app_labels', 'app_keys',
    'app_library', 'library_button', 'show_library_pill', 'library_ok_opens',
    'gamepad_passthrough'
)
foreach ($key in $keepKeys) {
    $val = $baseCfg.$key
    if ($null -ne $val) {
        $themeCfg | Add-Member -MemberType NoteProperty -Name $key -Value $val -Force
    }
}

$jsonText = $themeCfg | ConvertTo-Json -Depth 10
[System.IO.File]::WriteAllText((Join-Path $stagingDir 'config.json'), $jsonText, [System.Text.UTF8Encoding]::new($false))

powershell -ExecutionPolicy Bypass -File $themeScript -TvHost $TvHost $stagingDir
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
