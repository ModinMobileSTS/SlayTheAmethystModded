$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0

function Confirm-YesNo {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Prompt,
        [string]$Default = 'N'
    )

    $suffix = if ($Default -eq 'Y') { '[Y/n]' } else { '[y/N]' }

    while ($true) {
        $answer = Read-Host "$Prompt $suffix"
        if ([string]::IsNullOrWhiteSpace($answer)) {
            $answer = $Default
        }

        switch -Regex ($answer.Trim()) {
            '^(?i:y|yes)$' { return $true }
            '^(?i:n|no)$' { return $false }
            default { Write-Host '请输入 y 或 n。' }
        }
    }
}

function Get-GradleVersionName {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^application\.version\.name\s*=(.*)$') {
            return $Matches[1].Trim()
        }
    }

    return $null
}

function New-ReleaseNoteTemplate {
    param(
        [Parameter(Mandatory = $true)]
        [string]$NoteFile,
        [Parameter(Mandatory = $true)]
        [string]$NoteFileRelative,
        [Parameter(Mandatory = $true)]
        [string]$TagName
    )

    if (Test-Path -LiteralPath $NoteFile) {
        Write-Host "发布说明已存在，保留现有文件: $NoteFileRelative"
        return
    }

    $noteDir = Split-Path -Parent $NoteFile
    New-Item -ItemType Directory -Force -Path $noteDir | Out-Null

    $content = @(
        "发布日期: $(Get-Date -Format 'yyyy-MM-dd')",
        '',
        '## 新特性',
        '- ',
        '',
        '## 修复',
        '- '
    )

    Set-Content -LiteralPath $NoteFile -Value $content -Encoding UTF8
    Write-Host "已生成发布说明模板: $NoteFileRelative"
}

function Invoke-Git {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    & git @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "git 命令执行失败: git $($Arguments -join ' ')"
    }
}

function Main {
    $scriptDir = $PSScriptRoot
    $repoRoot = [System.IO.Path]::GetFullPath((Join-Path $scriptDir '..'))
    $didPushLocation = $false

    $null = & git -C $repoRoot rev-parse --show-toplevel 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw '当前脚本不在 Git 仓库中，无法继续。'
    }

    try {
        Push-Location $repoRoot
        $didPushLocation = $true

        $gradleFile = Join-Path $repoRoot 'gradle.properties'
        if (-not (Test-Path -LiteralPath $gradleFile)) {
            throw "未找到 gradle.properties: $gradleFile"
        }

        $versionName = Get-GradleVersionName -Path $gradleFile
        if ([string]::IsNullOrWhiteSpace($versionName)) {
            throw '无法从 gradle.properties 读取 application.version.name。'
        }

        $tagName = "v$versionName"
        $noteFileRelative = "docs/release/note/$tagName.md"
        $noteFile = Join-Path $repoRoot $noteFileRelative

        if (-not (Confirm-YesNo -Prompt "是否要发布版本 $tagName？")) {
            Write-Host '已取消发布。'
            return
        }

        $currentBranch = (& git branch --show-current).Trim()
        if ([string]::IsNullOrWhiteSpace($currentBranch)) {
            throw '当前处于 detached HEAD，无法自动推送分支。'
        }

        $upstreamRef = (& git rev-parse --abbrev-ref --symbolic-full-name '@{upstream}' 2>$null).Trim()
        $remoteName = 'origin'
        $remoteBranch = $currentBranch

        if (-not [string]::IsNullOrWhiteSpace($upstreamRef) -and $upstreamRef.Contains('/')) {
            $parts = $upstreamRef.Split('/', 2)
            $remoteName = $parts[0]
            $remoteBranch = $parts[1]
        }

        & git remote get-url $remoteName *> $null
        if ($LASTEXITCODE -ne 0) {
            throw "未找到可用远端: $remoteName"
        }

        & git rev-parse -q --verify "refs/tags/$tagName" *> $null
        if ($LASTEXITCODE -eq 0) {
            throw "本地已存在 tag: $tagName"
        }

        New-ReleaseNoteTemplate -NoteFile $noteFile -NoteFileRelative $noteFileRelative -TagName $tagName

        Write-Host "请编辑 $noteFileRelative 并填写更新日志。"
        $noteReady = $false
        while (-not $noteReady) {
            $ready = Read-Host '填写完成后输入 y 继续，输入 n 取消本次发布'
            switch -Regex ($ready.Trim()) {
                '^(?i:y|yes)$' {
                    $noteReady = $true
                }
                '^(?i:n|no)$' {
                    Write-Host '已取消发布。'
                    return
                }
                default { Write-Host '请输入 y 或 n。' }
            }
        }

        $currentVersionName = Get-GradleVersionName -Path $gradleFile
        $currentTagName = "v$currentVersionName"
        if ($currentTagName -ne $tagName) {
            throw "gradle.properties 中的版本已变更为 $currentTagName，请重新运行脚本。"
        }

        if (-not (Test-Path -LiteralPath $noteFile)) {
            throw "未找到发布说明文件: $noteFileRelative"
        }

        $gradleDirty = -not [string]::IsNullOrWhiteSpace((& git status --porcelain -- 'gradle.properties'))

        Write-Host ''
        Write-Host '即将发布以下内容:'
        Write-Host "  版本: $tagName"
        Write-Host "  说明文件: $noteFileRelative"
        if ($gradleDirty) {
            Write-Host '  附带文件: gradle.properties'
        } else {
            Write-Host '  附带文件: 无 gradle.properties 本地改动'
        }
        Write-Host "  提交信息: chore(release): prepare $tagName"
        Write-Host "  推送目标: $remoteName/$remoteBranch"
        Write-Host ''

        if (-not (Confirm-YesNo -Prompt '确认提交并推送本次发布？')) {
            Write-Host '已取消发布，未创建 commit 或 tag。'
            return
        }

        $remoteTagOutput = (& git ls-remote --tags $remoteName "refs/tags/$tagName" 2>$null)
        if ($LASTEXITCODE -ne 0) {
            throw '无法查询远端 tag，请检查网络或仓库权限后重试。'
        }
        if (-not [string]::IsNullOrWhiteSpace(($remoteTagOutput | Out-String).Trim())) {
            throw "远端已存在 tag: $tagName"
        }

        Invoke-Git -Arguments @('add', '--', $noteFileRelative)
        $commitPaths = @($noteFileRelative)
        if ($gradleDirty) {
            Invoke-Git -Arguments @('add', '--', 'gradle.properties')
            $commitPaths += 'gradle.properties'
        }

        $diffArgs = @('diff', '--cached', '--quiet', '--', $noteFileRelative)
        if ($gradleDirty) {
            $diffArgs += 'gradle.properties'
        }
        & git @diffArgs
        if ($LASTEXITCODE -eq 0) {
            throw "没有可提交的发布变更。请确认已编辑 $noteFileRelative 或修改 gradle.properties。"
        }
        if ($LASTEXITCODE -gt 1) {
            throw 'git diff --cached 执行失败。'
        }

        $commitMessage = "chore(release): prepare $tagName"
        $commitArgs = @('commit', '--only', '-m', $commitMessage, '--') + $commitPaths
        Invoke-Git -Arguments $commitArgs
        Invoke-Git -Arguments @('tag', '-a', $tagName, '-m', "Release $tagName")
        Invoke-Git -Arguments @('push', $remoteName, "HEAD:$remoteBranch")
        Invoke-Git -Arguments @('push', $remoteName, "refs/tags/$tagName")

        Write-Host ''
        Write-Host '发布准备完成:'
        Write-Host "  commit: $commitMessage"
        Write-Host "  tag: $tagName"
        Write-Host "  已推送到: $remoteName/$remoteBranch"
    }
    finally {
        if ($didPushLocation) {
            Pop-Location
        }
    }
}

try {
    Main
}
catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
