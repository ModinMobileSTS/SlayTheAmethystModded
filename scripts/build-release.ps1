[CmdletBinding()]
param(
    [string]$StoreFile,
    [string]$KeyAlias = 'upload',
    [string]$StorePassword,
    [string]$KeyPassword
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0

function ConvertTo-PlainText {
    param(
        [Parameter(Mandatory = $true)]
        [Security.SecureString]$SecureString
    )

    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureString)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
    }
}

function Read-SecretValue {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Prompt
    )

    $value = ConvertTo-PlainText -SecureString (Read-Host -Prompt $Prompt -AsSecureString)
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "Secret cannot be empty: $Prompt"
    }
    return $value
}

function Set-OrRestoreEnv {
    param(
        [Parameter(Mandatory = $true)]
        [hashtable]$Snapshot
    )

    foreach ($entry in $Snapshot.GetEnumerator()) {
        if ($entry.Value.Exists) {
            [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value.Value, 'Process')
        } else {
            Remove-Item "Env:$($entry.Key)" -ErrorAction SilentlyContinue
        }
    }
}

function Main {
    $scriptDir = $PSScriptRoot
    $repoRoot = [System.IO.Path]::GetFullPath((Join-Path $scriptDir '..'))
    $gradleWrapper = Join-Path $repoRoot 'gradlew.bat'
    $resolvedStoreFile = if ([string]::IsNullOrWhiteSpace($StoreFile)) {
        Join-Path $repoRoot 'signing\stamethyst-upload.jks'
    } else {
        $StoreFile
    }

    if (-not (Test-Path -LiteralPath $gradleWrapper)) {
        throw "Missing gradle wrapper: $gradleWrapper"
    }
    if (-not (Test-Path -LiteralPath $resolvedStoreFile)) {
        throw "Missing release keystore: $resolvedStoreFile"
    }

    $resolvedStorePassword = if ([string]::IsNullOrWhiteSpace($StorePassword)) {
        $env:RELEASE_STORE_PASSWORD
    } else {
        $StorePassword
    }
    $resolvedKeyPassword = if ([string]::IsNullOrWhiteSpace($KeyPassword)) {
        $env:RELEASE_KEY_PASSWORD
    } else {
        $KeyPassword
    }

    if ([string]::IsNullOrWhiteSpace($resolvedStorePassword)) {
        $resolvedStorePassword = Read-SecretValue -Prompt 'RELEASE_STORE_PASSWORD'
    }
    if ([string]::IsNullOrWhiteSpace($resolvedKeyPassword)) {
        $resolvedKeyPassword = Read-SecretValue -Prompt 'RELEASE_KEY_PASSWORD'
    }

    $envSnapshot = @{
        RELEASE_STORE_FILE = @{
            Exists = Test-Path Env:RELEASE_STORE_FILE
            Value = $env:RELEASE_STORE_FILE
        }
        RELEASE_STORE_PASSWORD = @{
            Exists = Test-Path Env:RELEASE_STORE_PASSWORD
            Value = $env:RELEASE_STORE_PASSWORD
        }
        RELEASE_KEY_ALIAS = @{
            Exists = Test-Path Env:RELEASE_KEY_ALIAS
            Value = $env:RELEASE_KEY_ALIAS
        }
        RELEASE_KEY_PASSWORD = @{
            Exists = Test-Path Env:RELEASE_KEY_PASSWORD
            Value = $env:RELEASE_KEY_PASSWORD
        }
    }

    $didPushLocation = $false
    try {
        [Environment]::SetEnvironmentVariable('RELEASE_STORE_FILE', $resolvedStoreFile, 'Process')
        [Environment]::SetEnvironmentVariable('RELEASE_STORE_PASSWORD', $resolvedStorePassword, 'Process')
        [Environment]::SetEnvironmentVariable('RELEASE_KEY_ALIAS', $KeyAlias, 'Process')
        [Environment]::SetEnvironmentVariable('RELEASE_KEY_PASSWORD', $resolvedKeyPassword, 'Process')

        Push-Location $repoRoot
        $didPushLocation = $true

        & $gradleWrapper :app:assembleRelease --stacktrace --console=plain
        if ($LASTEXITCODE -ne 0) {
            throw 'assembleRelease failed.'
        }

        $outputDir = Join-Path $repoRoot 'app\build\outputs\apk\release'
        Write-Host "Release APK directory: $outputDir"
    } finally {
        if ($didPushLocation) {
            Pop-Location
        }
        Set-OrRestoreEnv -Snapshot $envSnapshot
    }
}

Main
