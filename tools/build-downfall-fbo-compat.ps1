param(
    [string]$DesktopJar = "tools/desktop-1.0.jar",
    [string]$BaseModJar = "app/src/main/assets/components/mods/BaseMod.jar",
    [string]$DoubleOrbSource = "tools/compat-patch-src/downfall/collector/util/DoubleEnergyOrb.java",
    [string]$NpcSource = "tools/compat-patch-src/downfall/downfall/vfx/CustomAnimatedNPC.java",
    [string]$PortalStubSource = "tools/compat-patch-src/downfall/downfall/vfx/PortalBorderEffect.java",
    [string]$OutputJar = "app/src/main/assets/components/gdx_patch/downfall-fbo-compat.jar"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $DesktopJar)) {
    throw "Missing desktop jar: $DesktopJar"
}
if (-not (Test-Path $BaseModJar)) {
    throw "Missing BaseMod jar: $BaseModJar"
}
if (-not (Test-Path $DoubleOrbSource)) {
    throw "Missing source file: $DoubleOrbSource"
}
if (-not (Test-Path $NpcSource)) {
    throw "Missing source file: $NpcSource"
}
if (-not (Test-Path $PortalStubSource)) {
    throw "Missing source file: $PortalStubSource"
}

$buildDir = "tools/compat-patch-classes-downfall"
if (Test-Path $buildDir) {
    cmd /c "rmdir /s /q `"$buildDir`"" | Out-Null
}
New-Item -ItemType Directory -Force -Path $buildDir | Out-Null

$classpath = "$BaseModJar;$DesktopJar"
javac --release 8 -proc:none -cp $classpath -d $buildDir `
    $DoubleOrbSource `
    $NpcSource `
    $PortalStubSource

$doubleOrbEntry = "collector/util/DoubleEnergyOrb.class"
$npcEntry = "downfall/vfx/CustomAnimatedNPC.class"
jar cf $OutputJar -C $buildDir $doubleOrbEntry -C $buildDir $npcEntry

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($OutputJar)
try {
    $entrySet = New-Object System.Collections.Generic.HashSet[string]
    foreach ($entry in $zip.Entries) {
        [void]$entrySet.Add($entry.FullName)
    }
    foreach ($required in @($doubleOrbEntry, $npcEntry)) {
        if (-not $entrySet.Contains($required)) {
            throw "Build output missing required class entry: $required"
        }
    }
} finally {
    $zip.Dispose()
}

Write-Host "Built $OutputJar"
