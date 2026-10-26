$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
& (Join-Path $PSScriptRoot 'stop.ps1') -KeepDatabase
& (Join-Path $PSScriptRoot 'stop.ps1') -QA -KeepDatabase
$repoPath = (Join-Path $projectRoot '.local/m2').Replace('\','/')
$npm = (Get-Command npm.cmd -ErrorAction Stop).Source
$maven = (Get-Command mvn.cmd -ErrorAction Stop).Source
Push-Location (Join-Path $projectRoot 'frontend')
try {
    if (-not (Test-Path -LiteralPath 'node_modules')) {
        & $npm ci --cache (Join-Path $projectRoot '.local/npm-cache') --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependencies could not be installed.' }
    }
    & $npm run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
} finally { Pop-Location }
$staticDir = Join-Path $projectRoot 'backend/src/main/resources/static'
$distDir = Join-Path $projectRoot 'frontend/dist'
New-Item -ItemType Directory -Path $staticDir -Force | Out-Null
# The static directory contains generated files only. Remove assets no longer in
# the current frontend build so old bundles do not accumulate in later packages.
$resolvedStatic = (Resolve-Path -LiteralPath $staticDir).Path
$expectedStatic = [System.IO.Path]::GetFullPath($staticDir)
if ($resolvedStatic -ne $expectedStatic -or (Get-Item -LiteralPath $staticDir).Attributes.HasFlag([IO.FileAttributes]::ReparsePoint)) {
    throw 'Static output must be the generated directory inside this project.'
}
foreach ($oldFile in Get-ChildItem -LiteralPath $staticDir -Recurse -File) {
    $relativePath = $oldFile.FullName.Substring($resolvedStatic.Length + 1)
    if (-not (Test-Path -LiteralPath (Join-Path $distDir $relativePath))) {
        Remove-Item -LiteralPath $oldFile.FullName
    }
}
Copy-Item -Path (Join-Path $distDir '*') -Destination $staticDir -Recurse -Force
Push-Location (Join-Path $projectRoot 'backend')
try {
    & $maven -B -ntp "-Dmaven.repo.local=$repoPath" package
    if ($LASTEXITCODE -ne 0) { throw 'Backend build or tests failed.' }
} finally { Pop-Location }
Write-Output 'Build complete.'
