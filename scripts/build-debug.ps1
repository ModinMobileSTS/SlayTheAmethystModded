[CmdletBinding()]
param(
    [string]$ApplicationId = 'io.stamethyst.debug'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0

function Main {
    $scriptDir = $PSScriptRoot
    $repoRoot = [System.IO.Path]::GetFullPath((Join-Path $scriptDir '..'))
    $gradleWrapper = Join-Path $repoRoot 'gradlew.bat'

    if (-not (Test-Path -LiteralPath $gradleWrapper)) {
        throw "Missing gradle wrapper: $gradleWrapper"
    }
    if ([string]::IsNullOrWhiteSpace($ApplicationId)) {
        throw 'ApplicationId cannot be empty.'
    }

    $didPushLocation = $false
    try {
        Push-Location $repoRoot
        $didPushLocation = $true

        & $gradleWrapper `
            ":app:assembleDebug" `
            "-Papplication.id=$ApplicationId" `
            --stacktrace `
            --console=plain
        if ($LASTEXITCODE -ne 0) {
            throw 'assembleDebug failed.'
        }

        $outputDir = Join-Path $repoRoot 'app\build\outputs\apk\debug'
        Write-Host "Debug APK directory: $outputDir"
        Write-Host "Temporary applicationId: $ApplicationId"
    } finally {
        if ($didPushLocation) {
            Pop-Location
        }
    }
}

Main
