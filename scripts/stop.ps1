param([switch]$QA, [switch]$KeepDatabase)
$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runDir = Join-Path $projectRoot $(if ($QA) { '.local/qa' } else { '.local/app' })
$pidFile = Join-Path $runDir 'server.pid'
$jar = Join-Path $projectRoot 'backend/target/ziji-local-1.0.0.jar'
if (Test-Path -LiteralPath $pidFile) {
    $serverId = [int](Get-Content -LiteralPath $pidFile -Raw).Trim()
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$serverId" -ErrorAction SilentlyContinue
    if ($null -ne $process -and $process.Name -eq 'java.exe' -and $process.CommandLine -and $process.CommandLine.Contains($jar)) {
        Stop-Process -Id $serverId
        Wait-Process -Id $serverId -Timeout 10 -ErrorAction SilentlyContinue
        Write-Output 'Ziji stopped.'
    }
    Remove-Item -LiteralPath $pidFile
}
if (-not $KeepDatabase) {
    $client = Join-Path $projectRoot '.local/mysql/client.cnf'
    if (Test-Path -LiteralPath $client) {
        $mysqlAdmin = (Get-Command mysqladmin.exe -ErrorAction Stop).Source
        & $mysqlAdmin "--defaults-extra-file=$client" shutdown
        if ($LASTEXITCODE -ne 0) { throw 'Project MySQL could not be stopped.' }
        Write-Output 'Project MySQL stopped.'
    }
}
