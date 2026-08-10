$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$appPidFile = Join-Path $root 'run\aes-agent.pid'
$mysqlPidFile = Join-Path $root 'run\mysql.pid'
$credentialsFile = Join-Path $root 'data\mysql\credentials.properties'
$mysqlAdmin = Join-Path $root 'mysql\bin\mysqladmin.exe'
$mysqlPort = if ($env:MYSQL_PORT -match '^\d+$') { [int]$env:MYSQL_PORT } else { 3307 }
$stopped = $false

if (Test-Path -LiteralPath $appPidFile) {
    $servicePid = 0
    if ([int]::TryParse((Get-Content -Raw -LiteralPath $appPidFile), [ref]$servicePid)) {
        $process = Get-Process -Id $servicePid -ErrorAction SilentlyContinue
        if ($process) {
            Stop-Process -Id $servicePid -Force
            $null = $process.WaitForExit(10000)
            $stopped = $true
        }
    }
    Remove-Item -LiteralPath $appPidFile -Force -ErrorAction SilentlyContinue
}

$mysqlProcess = $null
if (Test-Path -LiteralPath $mysqlPidFile) {
    $mysqlPid = 0
    if ([int]::TryParse((Get-Content -Raw -LiteralPath $mysqlPidFile), [ref]$mysqlPid)) {
        $mysqlProcess = Get-Process -Id $mysqlPid -ErrorAction SilentlyContinue
    }
}

if ($mysqlProcess) {
    $rootPassword = ''
    if (Test-Path -LiteralPath $credentialsFile) {
        foreach ($line in Get-Content -Encoding UTF8 -LiteralPath $credentialsFile) {
            if ($line -match '^root\.password=(.*)$') { $rootPassword = $matches[1].Trim() }
            if ($line -match '^port=(\d+)$' -and -not ($env:MYSQL_PORT -match '^\d+$')) {
                $mysqlPort = [int]$matches[1]
            }
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($rootPassword) -and (Test-Path -LiteralPath $mysqlAdmin)) {
        $oldPassword = $env:MYSQL_PWD
        try {
            $env:MYSQL_PWD = $rootPassword
            $shutdownOut = Join-Path $root 'logs\mysql-shutdown.out'
            $shutdownErr = Join-Path $root 'logs\mysql-shutdown.err'
            $shutdownArguments = @(
                '--no-defaults', '--protocol=TCP', '--host=127.0.0.1',
                "--port=$mysqlPort", '--user=root', 'shutdown'
            )
            $shutdown = Start-Process -FilePath $mysqlAdmin -ArgumentList $shutdownArguments -Wait -PassThru -NoNewWindow -RedirectStandardOutput $shutdownOut -RedirectStandardError $shutdownErr
        } finally {
            if ($null -eq $oldPassword) { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
            else { $env:MYSQL_PWD = $oldPassword }
        }
        if ($shutdown.ExitCode -eq 0) { $null = $mysqlProcess.WaitForExit(15000) }
    }
    $mysqlProcess.Refresh()
    if (-not $mysqlProcess.HasExited) {
        $expected = [IO.Path]::GetFullPath((Join-Path $root 'mysql\bin\mysqld.exe'))
        $actual = $null
        try { $actual = (Get-CimInstance Win32_Process -Filter "ProcessId=$($mysqlProcess.Id)").ExecutablePath } catch { }
        if ($actual -and [IO.Path]::GetFullPath($actual).Equals($expected, [StringComparison]::OrdinalIgnoreCase)) {
            Stop-Process -Id $mysqlProcess.Id -Force
        } else {
            throw 'MySQL 未能正常关闭，且进程路径无法确认；为避免误杀其他程序，请手动检查。'
        }
    }
    $stopped = $true
}
Remove-Item -LiteralPath $mysqlPidFile -Force -ErrorAction SilentlyContinue

if ($stopped) { Write-Host '应用与便携 MySQL 已停止。' -ForegroundColor Green }
else { Write-Host '服务当前没有运行。' }
