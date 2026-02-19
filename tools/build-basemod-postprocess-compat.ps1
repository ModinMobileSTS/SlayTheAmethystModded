param(
    [string]$DesktopJar = "tools/desktop-1.0.jar",
    [string]$BaseModJar = "app/src/main/assets/components/mods/BaseMod.jar",
    [string]$SourceFile = "tools/compat-patch-src/basemod/patches/com/megacrit/cardcrawl/core/CardCrawlGame/ApplyScreenPostProcessor.java",
    [string]$OutputJar = "app/src/main/assets/components/gdx_patch/basemod-postprocess-fbo-compat.jar"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $DesktopJar)) {
    throw "Missing desktop jar: $DesktopJar"
}
if (-not (Test-Path $BaseModJar)) {
    throw "Missing BaseMod jar: $BaseModJar"
}
if (-not (Test-Path $SourceFile)) {
    throw "Missing source file: $SourceFile"
}

$buildDir = "tools/compat-patch-classes"
if (Test-Path $buildDir) {
    cmd /c "rmdir /s /q $buildDir" | Out-Null
}
New-Item -ItemType Directory -Force -Path $buildDir | Out-Null

$classpath = "$BaseModJar;$DesktopJar"
javac --release 8 -proc:none -cp $classpath -d $buildDir $SourceFile

$entry = "basemod/patches/com/megacrit/cardcrawl/core/CardCrawlGame/ApplyScreenPostProcessor.class"
jar cf $OutputJar -C $buildDir $entry

Write-Host "Built $OutputJar"
