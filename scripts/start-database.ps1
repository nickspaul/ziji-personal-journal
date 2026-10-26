param([int]$Port = 13316)
$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtimeDir = Join-Path $projectRoot '.local'
$mysqlDir = Join-Path $runtimeDir 'mysql'
$dataDir = Join-Path $mysqlDir 'data'
$runtimeFile = Join-Path $runtimeDir 'runtime.json'
New-Item -ItemType Directory -Path $mysqlDir -Force | Out-Null
$mysqlServer = (Get-Command mysqld.exe -ErrorAction Stop).Source
$mysqlClient = (Get-Command mysql.exe -ErrorAction Stop).Source
$utf8 = New-Object System.Text.UTF8Encoding($false)
if (Test-Path -LiteralPath $runtimeFile) {
    $runtime = Get-Content -LiteralPath $runtimeFile -Raw | ConvertFrom-Json
    $Port = [int]$runtime.dbPort
} else {
    $runtime = [ordered]@{
        dbPort = $Port
        dbName = 'ziji'
        dbUser = 'ziji'
        dbPassword = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
        rootPassword = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
    }
    [System.IO.File]::WriteAllText($runtimeFile, ($runtime | ConvertTo-Json), $utf8)
}
$mysqlConfig = Join-Path $mysqlDir 'my.ini'
$dataUnix = $dataDir.Replace('\','/')
$mysqlUnix = $mysqlDir.Replace('\','/')
$config = "[mysqld]`nport=$Port`nbind-address=127.0.0.1`nmysqlx=0`ndatadir=$dataUnix`nlog-error=$mysqlUnix/server.log`npid-file=$mysqlUnix/server.pid`ncharacter-set-server=utf8mb4`ncollation-server=utf8mb4_unicode_ci`ndefault-time-zone=+08:00`ninnodb-buffer-pool-size=64M`nmax-connections=30`n"
[System.IO.File]::WriteAllText($mysqlConfig,$config,$utf8)
$clientConfig = Join-Path $mysqlDir 'client.cnf'
[System.IO.File]::WriteAllText($clientConfig,"[client]`nprotocol=tcp`nhost=127.0.0.1`nport=$Port`nuser=root`npassword=$($runtime.rootPassword)`ndefault-character-set=utf8mb4`n",$utf8)
if (-not (Test-Path -LiteralPath (Join-Path $dataDir 'mysql'))) {
    New-Item -ItemType Directory -Path $dataDir -Force | Out-Null
    & $mysqlServer "--defaults-file=$mysqlConfig" --initialize-insecure --console
    if ($LASTEXITCODE -ne 0) { throw 'The dedicated MySQL data directory could not be initialized. See .local/mysql/server.log.' }
}
$ErrorActionPreference = 'Continue'
$probe = & $mysqlClient "--defaults-extra-file=$clientConfig" --connect-timeout=2 --batch --skip-column-names --execute='SELECT 1' 2>$null
$ErrorActionPreference = 'Stop'
if ($LASTEXITCODE -eq 0 -and "$probe" -eq '1') {
    Write-Output "Project MySQL is ready at 127.0.0.1:$Port."
    exit 0
}
$initPath = Join-Path $mysqlDir 'bootstrap.sql'
$sql = "ALTER USER 'root'@'localhost' IDENTIFIED BY '$($runtime.rootPassword)';`nCREATE DATABASE IF NOT EXISTS ziji CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;`nCREATE USER IF NOT EXISTS 'ziji'@'localhost' IDENTIFIED BY '$($runtime.dbPassword)';`nALTER USER 'ziji'@'localhost' IDENTIFIED BY '$($runtime.dbPassword)';`nGRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES, DROP ON ziji.* TO 'ziji'@'localhost';`n"
[System.IO.File]::WriteAllText($initPath,$sql,$utf8)
$argsList = @(('"--defaults-file={0}"' -f $mysqlConfig),('"--init-file={0}"' -f $initPath))
$serverProcess = Start-Process -FilePath $mysqlServer -ArgumentList $argsList -WindowStyle Hidden -PassThru
[System.IO.File]::WriteAllText((Join-Path $mysqlDir 'launcher.pid'),[string]$serverProcess.Id,$utf8)
$ready = $false
for ($attempt = 0; $attempt -lt 40; $attempt++) {
    Start-Sleep -Milliseconds 500
    $serverProcess.Refresh()
    if ($serverProcess.HasExited) { throw 'Dedicated MySQL exited during startup. See .local/mysql/server.log.' }
    $ErrorActionPreference = 'Continue'
$probe = & $mysqlClient "--defaults-extra-file=$clientConfig" --connect-timeout=1 --batch --skip-column-names --execute='SELECT 1' 2>$null
    $ErrorActionPreference = 'Stop'
if ($LASTEXITCODE -eq 0 -and "$probe" -eq '1') { $ready = $true; break }
}
if (-not $ready) { throw 'Dedicated MySQL did not become ready. See .local/mysql/server.log.' }
Remove-Item -LiteralPath $initPath
Write-Output "Project MySQL is ready at 127.0.0.1:$Port."
