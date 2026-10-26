param([switch]$Build, [switch]$NoOpen, [switch]$QA)
$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$lockName = 'Local\ZijiStartup-' + [Convert]::ToBase64String([System.Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($projectRoot))).Replace('/','_').Replace('+','-')
$startupLock = New-Object System.Threading.Mutex($false, $lockName)
$lockOwned = $false
try {
try { $lockOwned = $startupLock.WaitOne(0) } catch [System.Threading.AbandonedMutexException] { $lockOwned = $true }
if (-not $lockOwned) { Write-Output 'Ziji startup is already in progress. Please wait.'; exit 0 }
$jar = Join-Path $projectRoot 'backend/target/ziji-local-1.0.0.jar'
if ($Build -or -not (Test-Path -LiteralPath $jar)) { & (Join-Path $PSScriptRoot 'build.ps1') }
& (Join-Path $PSScriptRoot 'start-database.ps1')
$runtime = Get-Content -LiteralPath (Join-Path $projectRoot '.local/runtime.json') -Raw | ConvertFrom-Json
$port = if ($QA) { 17891 } else { 17890 }
$database = if ($QA) { 'ziji_qa' } else { 'ziji' }
$runDir = Join-Path $projectRoot $(if ($QA) { '.local/qa' } else { '.local/app' })
New-Item -ItemType Directory -Path $runDir -Force | Out-Null
$url = "http://127.0.0.1:$port"
try { $status = Invoke-RestMethod -Uri "$url/api/auth/status" -TimeoutSec 2 } catch { $status = $null }
if ($null -ne $status -and $null -ne $status.configured) {
    Write-Output "Ziji is already running: $url"
    if (-not $NoOpen) { Start-Process $url }
    exit 0
}
$existingPid = Join-Path $runDir 'server.pid'
if (Test-Path -LiteralPath $existingPid) {
    $existingId = [int](Get-Content -LiteralPath $existingPid -Raw).Trim()
    $existingProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$existingId" -ErrorAction SilentlyContinue
    if ($existingProcess -and $existingProcess.CommandLine -and $existingProcess.Name -eq 'java.exe' -and $existingProcess.CommandLine.Contains($jar)) {
        throw 'Ziji is still starting or did not become ready. Check .local/app/server.log, or stop it before restarting.'
    }
}
$names = @('ZIJI_DB_URL','ZIJI_DB_USER','ZIJI_DB_PASSWORD','ZIJI_DATA_DIR','ZIJI_PORT')
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
try {
    $env:ZIJI_DB_URL = "jdbc:mysql://127.0.0.1:$($runtime.dbPort)/$database`?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false"
    $env:ZIJI_DB_USER = $runtime.dbUser
    $env:ZIJI_DB_PASSWORD = $runtime.dbPassword
    $env:ZIJI_DATA_DIR = $runDir
    $env:ZIJI_PORT = [string]$port
    $java = (Get-Command java.exe -ErrorAction Stop).Source
    $process = Start-Process -FilePath $java -ArgumentList @('-Xms128m','-Xmx512m','-Duser.timezone=Asia/Shanghai','-Dfile.encoding=UTF-8','-jar',('"{0}"' -f $jar)) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runDir 'server.log') -RedirectStandardError (Join-Path $runDir 'error.log')
    Set-Content -LiteralPath (Join-Path $runDir 'server.pid') -Value $process.Id -Encoding ASCII
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,$saved[$name],'Process') }
}
$ready = $false
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    Start-Sleep -Milliseconds 500
    $process.Refresh()
    if ($process.HasExited) { throw "Ziji stopped during startup. Read $runDir/server.log and error.log." }
    try { $status = Invoke-RestMethod -Uri "$url/api/auth/status" -TimeoutSec 1; $ready = $true; break } catch { }
}
if (-not $ready) { throw "Ziji is still starting. Read $runDir/server.log." }
Write-Output "Ziji is ready: $url"
if (-not $NoOpen) { Start-Process $url }
} finally {
    if ($lockOwned) { $startupLock.ReleaseMutex() }
    $startupLock.Dispose()
}
