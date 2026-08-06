param(
    [string]$MySqlHome = 'C:\Program Files\MySQL\MySQL Server 8.0',
    [int]$MySqlPort = 33307,
    [int]$AppPort = 18080,
    [switch]$SkipMaven
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$targetRoot = [IO.Path]::GetFullPath((Join-Path $root 'target'))
$testRoot = [IO.Path]::GetFullPath((Join-Path $targetRoot 'mysql-integration'))
if (-not $testRoot.StartsWith($targetRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing to use an integration-test path outside target.'
}

$mysqld = Join-Path $MySqlHome 'bin\mysqld.exe'
$mysql = Join-Path $MySqlHome 'bin\mysql.exe'
$mysqlAdmin = Join-Path $MySqlHome 'bin\mysqladmin.exe'
foreach ($required in @($mysqld, $mysql, $mysqlAdmin)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing MySQL executable: $required" }
}

if (-not ('AesIntegration.ShortPath' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;
namespace AesIntegration {
    public static class ShortPath {
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern uint GetShortPathName(string longPath, StringBuilder shortPath, int size);
        public static string Convert(string path) {
            var buffer = new StringBuilder(4096);
            return GetShortPathName(path, buffer, buffer.Capacity) == 0 ? path : buffer.ToString();
        }
    }
}
'@
}

function Get-ShortPath([string]$path) {
    return [AesIntegration.ShortPath]::Convert([IO.Path]::GetFullPath($path))
}

function New-HexSecret {
    $bytes = New-Object byte[] 24
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
}

function Invoke-WithPassword([string]$password, [scriptblock]$action) {
    $oldPassword = $env:MYSQL_PWD
    try {
        if ([string]::IsNullOrEmpty($password)) { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
        else { $env:MYSQL_PWD = $password }
        & $action
    } finally {
        if ($null -eq $oldPassword) { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
        else { $env:MYSQL_PWD = $oldPassword }
    }
}

foreach ($port in @($MySqlPort, $AppPort)) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
        throw "Integration-test port $port is already in use."
    }
}

if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
$data = Join-Path $testRoot 'data'
$mysqlLog = Join-Path $testRoot 'mysql-error.log'
$appOut = Join-Path $testRoot 'app-console.log'
$appErr = Join-Path $testRoot 'app-error.log'
New-Item -ItemType Directory -Force -Path $data | Out-Null
$safeMySqlHome = Get-ShortPath $MySqlHome
$safeData = Get-ShortPath $data
$safeTestRoot = Get-ShortPath $testRoot
$safeMySqlLog = Join-Path $safeTestRoot 'mysql-error.log'

$rootPassword = New-HexSecret
$appPassword = New-HexSecret
$sandboxPassword = New-HexSecret
$mysqlProcess = $null
$appProcess = $null
$appPid = 0

try {
    Write-Host 'Initializing isolated MySQL 8 test instance...' -ForegroundColor Cyan
    & $mysqld --no-defaults --initialize-insecure "--basedir=$safeMySqlHome" "--datadir=$safeData" --console
    if ($LASTEXITCODE -ne 0) { throw 'mysqld --initialize-insecure failed.' }

    $mysqlArguments = @(
        '--no-defaults',
        "--basedir=$safeMySqlHome",
        "--datadir=$safeData",
        "--port=$MySqlPort",
        '--bind-address=127.0.0.1',
        '--mysqlx=0',
        '--character-set-server=utf8mb4',
        '--collation-server=utf8mb4_0900_ai_ci',
        '--secure-file-priv=NULL',
        '--local-infile=0',
        '--skip-log-bin',
        "--log-error=$safeMySqlLog"
    )
    $mysqlProcess = Start-Process -FilePath $mysqld -ArgumentList $mysqlArguments -WindowStyle Hidden -PassThru

    $ready = $false
    for ($attempt = 1; $attempt -le 120; $attempt++) {
        & $mysqlAdmin --no-defaults --protocol=TCP --host=127.0.0.1 --port=$MySqlPort --user=root ping --silent *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw 'Isolated MySQL did not become ready.' }

    $bootstrapSql = @"
CREATE DATABASE aes_agent CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE aes_sql_sandbox CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'aes_agent'@'127.0.0.1' IDENTIFIED BY '$appPassword';
GRANT ALL PRIVILEGES ON aes_agent.* TO 'aes_agent'@'127.0.0.1';
CREATE USER 'aes_sandbox'@'127.0.0.1' IDENTIFIED BY '$sandboxPassword';
GRANT ALL PRIVILEGES ON aes_sql_sandbox.* TO 'aes_sandbox'@'127.0.0.1';
ALTER USER 'root'@'localhost' IDENTIFIED BY '$rootPassword';
FLUSH PRIVILEGES;
"@
    $bootstrapSql | & $mysql --no-defaults --protocol=TCP --host=127.0.0.1 --port=$MySqlPort --user=root
    if ($LASTEXITCODE -ne 0) { throw 'MySQL database/user provisioning failed.' }

    $gradingUrl = "jdbc:mysql://127.0.0.1:$MySqlPort/aes_agent?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
    $sandboxUrl = "jdbc:mysql://127.0.0.1:$MySqlPort/aes_sql_sandbox?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
    $env:AES_TEST_MYSQL_URL = $gradingUrl
    $env:AES_TEST_MYSQL_USERNAME = 'aes_agent'
    $env:AES_TEST_MYSQL_PASSWORD = $appPassword
    $env:AES_TEST_MYSQL_SANDBOX_URL = $sandboxUrl
    $env:AES_TEST_MYSQL_SANDBOX_USERNAME = 'aes_sandbox'
    $env:AES_TEST_MYSQL_SANDBOX_PASSWORD = $sandboxPassword

    if (-not $SkipMaven) {
        Write-Host 'Running the complete Maven test suite against real MySQL...' -ForegroundColor Cyan
        & mvn -q test
        if ($LASTEXITCODE -ne 0) { throw 'MySQL integration tests failed.' }

        Invoke-WithPassword $appPassword {
            & $mysql --no-defaults --protocol=TCP --host=127.0.0.1 --port=$MySqlPort --user=aes_agent --database=aes_agent --execute='SET FOREIGN_KEY_CHECKS=0; TRUNCATE grading_question; TRUNCATE grading_record; SET FOREIGN_KEY_CHECKS=1;'
            if ($LASTEXITCODE -ne 0) { throw 'Could not clean the test grading database.' }
        }

        & mvn -q -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Application packaging failed.' }
    }

    $env:AES_GRADING_DATABASE_URL = $gradingUrl
    $env:AES_GRADING_DATABASE_USERNAME = 'aes_agent'
    $env:AES_GRADING_DATABASE_PASSWORD = $appPassword
    $env:AES_SQL_SANDBOX_URL = $sandboxUrl
    $env:AES_SQL_SANDBOX_USERNAME = 'aes_sandbox'
    $env:AES_SQL_SANDBOX_PASSWORD = $sandboxPassword
    $env:CHROMA_ENABLED = 'false'
    $env:AES_SECURITY_ENABLED = 'false'
    $env:DEEPSEEK_API_KEY = 'not-configured'
    $env:GRADING_API_KEY = ''
    $env:VISION_ENABLED = 'false'

    $jar = Get-ChildItem -LiteralPath (Join-Path $root 'target') -Filter 'aes-agent-*.jar' |
        Where-Object { $_.Name -notLike '*.original' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $jar) { throw 'Executable JAR was not produced.' }

    $appArguments = "-jar `"$($jar.FullName)`" --server.port=$AppPort"
    $appProcess = Start-Process -FilePath java -ArgumentList $appArguments -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput $appOut -RedirectStandardError $appErr -PassThru
    $appPid = $appProcess.Id
    $health = $null
    for ($attempt = 1; $attempt -le 120; $attempt++) {
        if ($appProcess.HasExited) { break }
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$AppPort/api/health" -TimeoutSec 2
            if ($health.status -eq 'UP') { break }
        } catch { Start-Sleep -Seconds 1 }
        $appProcess.Refresh()
    }
    if ($null -eq $health -or $health.status -ne 'UP') {
        if (Test-Path -LiteralPath $appErr) { Get-Content -Tail 80 -LiteralPath $appErr }
        if (Test-Path -LiteralPath $appOut) { Get-Content -Tail 80 -LiteralPath $appOut }
        throw 'Application health check failed.'
    }

    $storage = Invoke-RestMethod -Uri "http://127.0.0.1:$AppPort/api/grading/storage" -TimeoutSec 5
    Write-Host "Storage check: engine=$($storage.engine), records=$($storage.recordCount), questions=$($storage.questionCount)"
    if ($storage.engine -ne 'MySQL' -or $storage.recordCount -ne 6 -or $storage.questionCount -ne 24) {
        throw "Unexpected migrated data: $($storage | ConvertTo-Json -Compress)"
    }
    $records = Invoke-RestMethod -Uri "http://127.0.0.1:$AppPort/api/grading/records?limit=20" -TimeoutSec 5
    Write-Host "Record query check: $(@($records).Count) records"
    if (@($records).Count -ne 6) { throw 'Record query did not return all six migrated records.' }

    $sandboxCouldRead = Invoke-WithPassword $sandboxPassword {
        $deniedOut = Join-Path $testRoot 'sandbox-access.out'
        $deniedErr = Join-Path $testRoot 'sandbox-access.err'
        $deniedArguments = @(
            '--no-defaults', '--protocol=TCP', '--host=127.0.0.1',
            "--port=$MySqlPort", '--user=aes_sandbox', '--database=aes_sql_sandbox',
            '--execute="SELECT COUNT(*) FROM aes_agent.grading_record"'
        )
        $denied = Start-Process -FilePath $mysql -ArgumentList $deniedArguments -Wait -PassThru -NoNewWindow -RedirectStandardOutput $deniedOut -RedirectStandardError $deniedErr
        return $denied.ExitCode -eq 0
    }
    Write-Host "Sandbox isolation check: grading database readable=$sandboxCouldRead"
    if ($sandboxCouldRead) { throw 'Sandbox account unexpectedly accessed the grading database.' }

    [pscustomobject]@{
        tests = 'passed'
        appHealth = $health.status
        engine = $storage.engine
        records = $storage.recordCount
        questions = $storage.questionCount
        sandboxIsolation = 'passed'
    } | ConvertTo-Json -Compress
} catch {
    $diagnostic = ($_ | Out-String)
    [IO.File]::WriteAllText(
        (Join-Path $testRoot 'failure.txt'), $diagnostic, [Text.UTF8Encoding]::new($false))
    Write-Host ("Integration failure: " + $_.Exception.Message) -ForegroundColor Red
    throw
} finally {
    if ($appPid -gt 0) {
        $runningApp = Get-Process -Id $appPid -ErrorAction SilentlyContinue
        if ($runningApp) {
            Stop-Process -Id $appPid -Force -ErrorAction SilentlyContinue
            $null = $runningApp.WaitForExit(10000)
        }
    }
    $listener = Get-NetTCPConnection -State Listen -LocalPort $AppPort -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listener) {
        $listenerProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)" -ErrorAction SilentlyContinue
        if ($listenerProcess.CommandLine -like "*aes-agent-java*--server.port=$AppPort*") {
            Stop-Process -Id $listener.OwningProcess -Force -ErrorAction SilentlyContinue
        }
    }
    if ($mysqlProcess -and -not $mysqlProcess.HasExited) {
        try {
            Invoke-WithPassword $rootPassword {
                & $mysqlAdmin --no-defaults --protocol=TCP --host=127.0.0.1 --port=$MySqlPort --user=root shutdown *> $null
            }
        } catch { }
        $mysqlProcess.Refresh()
        if (-not $mysqlProcess.HasExited) {
            Stop-Process -Id $mysqlProcess.Id -Force -ErrorAction SilentlyContinue
        }
    }
}
