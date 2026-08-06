param(
    [switch]$NoOpen
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$java = Join-Path $root 'runtime\bin\java.exe'
$jar = Join-Path $root 'app\aes-agent.jar'
$config = Join-Path $root 'config\application.properties'
$mysqlHome = Join-Path $root 'mysql'
$mysqld = Join-Path $mysqlHome 'bin\mysqld.exe'
$mysql = Join-Path $mysqlHome 'bin\mysql.exe'
$mysqlAdmin = Join-Path $mysqlHome 'bin\mysqladmin.exe'
$runDirectory = Join-Path $root 'run'
$logDirectory = Join-Path $root 'logs'
$mysqlRoot = Join-Path $root 'data\mysql'
$mysqlData = Join-Path $mysqlRoot 'data'
$credentialsFile = Join-Path $mysqlRoot 'credentials.properties'
$mysqlConfig = Join-Path $mysqlRoot 'my.ini'
$appPidFile = Join-Path $runDirectory 'aes-agent.pid'
$mysqlPidFile = Join-Path $runDirectory 'mysql.pid'
$mysqlServerPidFile = Join-Path $runDirectory 'mysql-server.pid'
$mysqlLog = Join-Path $logDirectory 'mysql-error.log'
$mysqlPort = if ($env:MYSQL_PORT -match '^\d+$') { [int]$env:MYSQL_PORT } else { 3307 }
$appPort = if ($env:PORT -match '^\d+$') { [int]$env:PORT } else { 8080 }
$url = "http://127.0.0.1:$appPort"

New-Item -ItemType Directory -Force -Path $runDirectory, $logDirectory, $mysqlRoot | Out-Null

if (-not ('AesPortable.ShortPath' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;
namespace AesPortable {
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

function Get-MySqlSafePath([string]$path) {
    $fullPath = [IO.Path]::GetFullPath($path)
    $shortPath = [AesPortable.ShortPath]::Convert($fullPath)
    if ($shortPath -match '[^\x00-\x7F]') {
        throw 'MySQL 无法安全使用当前路径。请把 aes-agent-portable-windows 英文文件夹放到磁盘或 U 盘根目录后重试。'
    }
    return $shortPath
}

function Get-MySqlSafeFilePath([string]$path) {
    return Join-Path (Get-MySqlSafePath (Split-Path $path -Parent)) (Split-Path $path -Leaf)
}

foreach ($required in @($java, $jar, $config, $mysqld, $mysql, $mysqlAdmin)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "便携包文件不完整，请重新解压：$required"
    }
}
if ($mysqlPort -lt 1024 -or $mysqlPort -gt 65535) {
    throw 'MYSQL_PORT 必须是 1024 到 65535 之间的端口。'
}

function New-HexSecret {
    $bytes = New-Object byte[] 32
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
}

function Read-Credentials {
    if (-not (Test-Path -LiteralPath $credentialsFile)) { return $null }
    $values = @{}
    foreach ($line in Get-Content -Encoding UTF8 -LiteralPath $credentialsFile) {
        if ($line -match '^([^#=]+)=(.*)$') { $values[$matches[1].Trim()] = $matches[2].Trim() }
    }
    foreach ($name in @('root.password', 'app.password', 'sandbox.password')) {
        if ([string]::IsNullOrWhiteSpace($values[$name])) {
            throw "MySQL 凭据文件不完整（缺少 $name），请恢复完整便携目录。"
        }
    }
    return $values
}

function Save-Credentials([hashtable]$values) {
    $content = @(
        '# AES Agent portable MySQL credentials - generated locally; do not share separately.'
        "port=$mysqlPort"
        "root.password=$($values['root.password'])"
        "app.password=$($values['app.password'])"
        "sandbox.password=$($values['sandbox.password'])"
    ) -join "`r`n"
    [IO.File]::WriteAllText($credentialsFile, $content + "`r`n", [Text.UTF8Encoding]::new($false))
}

function Invoke-WithPassword([string]$password, [scriptblock]$action) {
    $oldPassword = $env:MYSQL_PWD
    try {
        if ([string]::IsNullOrEmpty($password)) {
            Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
        } else {
            $env:MYSQL_PWD = $password
        }
        return & $action
    } finally {
        if ($null -eq $oldPassword) {
            Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
        } else {
            $env:MYSQL_PWD = $oldPassword
        }
    }
}

function Invoke-MySqlProbe([string]$user, [string]$password, [string]$database) {
    $probeOut = Join-Path $logDirectory 'mysql-probe.out'
    $probeErr = Join-Path $logDirectory 'mysql-probe.err'
    $probeArguments = @(
        '--no-defaults', '--protocol=TCP', '--host=127.0.0.1',
        "--port=$mysqlPort", "--user=$user", '--batch', '--skip-column-names',
        '--execute="SELECT 1"'
    )
    if (-not [string]::IsNullOrWhiteSpace($database)) {
        $probeArguments += "--database=$database"
    }
    return Invoke-WithPassword $password {
        $probe = Start-Process -FilePath (Get-MySqlSafePath $mysql) -ArgumentList $probeArguments -Wait -PassThru -NoNewWindow -RedirectStandardOutput $probeOut -RedirectStandardError $probeErr
        return $probe.ExitCode -eq 0
    }
}

function Test-MySqlRoot([string]$password) {
    return Invoke-MySqlProbe 'root' $password ''
}

function Test-MySqlUser([string]$user, [string]$password, [string]$database) {
    return Invoke-MySqlProbe $user $password $database
}

function Test-PortInUse([int]$port) {
    $client = New-Object Net.Sockets.TcpClient
    try {
        $result = $client.BeginConnect('127.0.0.1', $port, $null, $null)
        if (-not $result.AsyncWaitHandle.WaitOne(350, $false)) { return $false }
        try { $client.EndConnect($result); return $client.Connected } catch { return $false }
    } finally {
        $client.Close()
    }
}

function Write-MySqlConfig {
    $forwardHome = (Get-MySqlSafePath $mysqlHome).Replace('\', '/')
    $forwardData = (Get-MySqlSafePath $mysqlData).Replace('\', '/')
    $forwardPid = (Get-MySqlSafeFilePath $mysqlServerPidFile).Replace('\', '/')
    $forwardLog = (Get-MySqlSafeFilePath $mysqlLog).Replace('\', '/')
    $content = @"
[mysqld]
basedir=$forwardHome
datadir=$forwardData
port=$mysqlPort
bind-address=127.0.0.1
mysqlx=0
character-set-server=utf8mb4
collation-server=utf8mb4_0900_ai_ci
secure-file-priv=NULL
local-infile=0
skip-log-bin
skip-symbolic-links
max-connections=50
innodb-buffer-pool-size=128M
pid-file=$forwardPid
log-error=$forwardLog
"@
    [IO.File]::WriteAllText($mysqlConfig, $content, [Text.UTF8Encoding]::new($false))
}

function Initialize-MySql {
    if (Test-Path -LiteralPath (Join-Path $mysqlData 'mysql')) { return }
    if (Test-Path -LiteralPath $mysqlData) {
        $remaining = @(Get-ChildItem -Force -LiteralPath $mysqlData -ErrorAction SilentlyContinue)
        if ($remaining.Count -gt 0) {
            throw 'data\mysql\data 中存在不完整数据，无法安全初始化；请恢复完整便携目录。'
        }
    }
    New-Item -ItemType Directory -Force -Path $mysqlData | Out-Null
    Write-Host '首次运行：正在初始化便携 MySQL 8 数据目录……' -ForegroundColor Cyan
    $safeHome = Get-MySqlSafePath $mysqlHome
    $safeData = Get-MySqlSafePath $mysqlData
    & (Get-MySqlSafePath $mysqld) --no-defaults --initialize-insecure "--basedir=$safeHome" "--datadir=$safeData" --console
    if ($LASTEXITCODE -ne 0) { throw 'MySQL 数据目录初始化失败，请查看上方输出。' }
}

function Provision-MySql([hashtable]$values) {
    $rootPassword = $values['root.password']
    $appPassword = $values['app.password']
    $sandboxPassword = $values['sandbox.password']
    $sql = @"
CREATE DATABASE IF NOT EXISTS aes_agent CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS aes_sql_sandbox CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'aes_agent'@'127.0.0.1' IDENTIFIED BY '$appPassword';
ALTER USER 'aes_agent'@'127.0.0.1' IDENTIFIED BY '$appPassword' WITH MAX_USER_CONNECTIONS 12;
GRANT ALL PRIVILEGES ON aes_agent.* TO 'aes_agent'@'127.0.0.1';
CREATE USER IF NOT EXISTS 'aes_sandbox'@'127.0.0.1' IDENTIFIED BY '$sandboxPassword';
ALTER USER 'aes_sandbox'@'127.0.0.1' IDENTIFIED BY '$sandboxPassword' WITH MAX_USER_CONNECTIONS 4;
GRANT ALL PRIVILEGES ON aes_sql_sandbox.* TO 'aes_sandbox'@'127.0.0.1';
ALTER USER 'root'@'localhost' IDENTIFIED BY '$rootPassword';
FLUSH PRIVILEGES;
"@
    Invoke-WithPassword '' {
        $sql | & (Get-MySqlSafePath $mysql) --no-defaults --protocol=TCP --host=127.0.0.1 --port=$mysqlPort --user=root --default-character-set=utf8mb4
        if ($LASTEXITCODE -ne 0) { throw 'MySQL 数据库与账号初始化失败。' }
    } | Out-Null
}

function Stop-StartedMySql([hashtable]$values) {
    if ($null -ne $values) {
        Invoke-WithPassword $values['root.password'] {
            & (Get-MySqlSafePath $mysqlAdmin) --no-defaults --protocol=TCP --host=127.0.0.1 --port=$mysqlPort --user=root shutdown *> $null
        } | Out-Null
    }
}

$startedMySql = $false
$credentials = Read-Credentials
$existingMySql = $null
if (Test-Path -LiteralPath $mysqlPidFile) {
    $storedPid = 0
    if ([int]::TryParse((Get-Content -Raw -LiteralPath $mysqlPidFile), [ref]$storedPid)) {
        $existingMySql = Get-Process -Id $storedPid -ErrorAction SilentlyContinue
    }
    if (-not $existingMySql) { Remove-Item -LiteralPath $mysqlPidFile -Force }
}

if (-not $existingMySql) {
    if (Test-PortInUse $mysqlPort) {
        throw "MySQL 端口 $mysqlPort 已被其他程序占用。请关闭占用程序，或设置 MYSQL_PORT 后重试。"
    }
    Initialize-MySql
    Write-MySqlConfig
    Write-Host "正在启动便携 MySQL（127.0.0.1:$mysqlPort）……" -ForegroundColor Cyan
    $mysqlStart = @{
        FilePath = Get-MySqlSafePath $mysqld
        ArgumentList = "--defaults-file=`"$(Get-MySqlSafePath $mysqlConfig)`""
        WorkingDirectory = $root
        WindowStyle = 'Hidden'
        PassThru = $true
    }
    $mysqlProcess = Start-Process @mysqlStart
    [IO.File]::WriteAllText($mysqlPidFile, [string]$mysqlProcess.Id, [Text.UTF8Encoding]::new($false))
    $existingMySql = $mysqlProcess
    $startedMySql = $true
}

if ($null -eq $credentials) {
    $credentials = @{
        'root.password' = New-HexSecret
        'app.password' = New-HexSecret
        'sandbox.password' = New-HexSecret
    }
}
Save-Credentials $credentials

$rootReady = $false
$insecureReady = $false
for ($attempt = 1; $attempt -le 120; $attempt++) {
    if ($existingMySql.HasExited) { break }
    if (Test-MySqlRoot $credentials['root.password']) { $rootReady = $true; break }
    if (Test-MySqlRoot '') { $insecureReady = $true; break }
    Start-Sleep -Milliseconds 500
    $existingMySql.Refresh()
}

if ($insecureReady) {
    Write-Host '首次运行：正在创建正式记录库与隔离 SQL 沙箱……' -ForegroundColor Cyan
    Provision-MySql $credentials
    $rootReady = Test-MySqlRoot $credentials['root.password']
}
if (-not $rootReady) {
    if ($startedMySql -and -not $existingMySql.HasExited) { Stop-Process -Id $existingMySql.Id -Force }
    Remove-Item -LiteralPath $mysqlPidFile -Force -ErrorAction SilentlyContinue
    throw '便携 MySQL 未能就绪。请运行 doctor.cmd 并查看 logs\mysql-error.log。'
}
$actualMySqlPid = 0
if ((Test-Path -LiteralPath $mysqlServerPidFile) -and
    [int]::TryParse((Get-Content -Raw -LiteralPath $mysqlServerPidFile), [ref]$actualMySqlPid)) {
    $actualMySqlProcess = Get-Process -Id $actualMySqlPid -ErrorAction SilentlyContinue
    if ($actualMySqlProcess) {
        $existingMySql = $actualMySqlProcess
        [IO.File]::WriteAllText($mysqlPidFile, [string]$actualMySqlPid, [Text.UTF8Encoding]::new($false))
    }
}
if (-not (Test-MySqlUser 'aes_agent' $credentials['app.password'] 'aes_agent') -or
    -not (Test-MySqlUser 'aes_sandbox' $credentials['sandbox.password'] 'aes_sql_sandbox')) {
    throw 'MySQL 账号自检失败，正式记录库或 SQL 沙箱不可用。'
}

$env:AES_GRADING_DATABASE_URL = "jdbc:mysql://127.0.0.1:$mysqlPort/aes_agent?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
$env:AES_GRADING_DATABASE_USERNAME = 'aes_agent'
$env:AES_GRADING_DATABASE_PASSWORD = $credentials['app.password']
$env:AES_SQL_SANDBOX_URL = "jdbc:mysql://127.0.0.1:$mysqlPort/aes_sql_sandbox?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
$env:AES_SQL_SANDBOX_USERNAME = 'aes_sandbox'
$env:AES_SQL_SANDBOX_PASSWORD = $credentials['sandbox.password']

if (Test-Path -LiteralPath $appPidFile) {
    $existingPid = 0
    if ([int]::TryParse((Get-Content -Raw -LiteralPath $appPidFile), [ref]$existingPid)) {
        $existing = Get-Process -Id $existingPid -ErrorAction SilentlyContinue
        if ($existing) {
            Write-Host "服务已经运行：$url" -ForegroundColor Green
            if (-not $NoOpen) { Start-Process $url }
            exit 0
        }
    }
    Remove-Item -LiteralPath $appPidFile -Force
}

if ([string]::IsNullOrWhiteSpace($env:GRADING_API_KEY) -and
    [string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)) {
    Write-Host '请输入批改模型 API Key（支持百炼或 DeepSeek）；直接回车可先使用本地功能。' -ForegroundColor Yellow
    $secureKey = Read-Host 'Model API Key' -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
    try { $plainKey = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    if ([string]::IsNullOrWhiteSpace($plainKey)) {
        $env:DEEPSEEK_API_KEY = 'not-configured'
    } elseif ($plainKey.StartsWith('sk-ws-', [StringComparison]::OrdinalIgnoreCase)) {
        $env:GRADING_API_KEY = $plainKey
    } else {
        $env:DEEPSEEK_API_KEY = $plainKey
    }
}

if (-not [string]::IsNullOrWhiteSpace($env:GRADING_API_KEY) -and
    $env:GRADING_API_KEY.StartsWith('sk-ws-', [StringComparison]::OrdinalIgnoreCase)) {
    if ([string]::IsNullOrWhiteSpace($env:GRADING_BASE_URL)) {
        $env:GRADING_BASE_URL = 'https://dashscope.aliyuncs.com/compatible-mode/v1'
    }
    if ([string]::IsNullOrWhiteSpace($env:GRADING_MODEL)) { $env:GRADING_MODEL = 'qwen3.7-plus' }
    if ([string]::IsNullOrWhiteSpace($env:VISION_API_KEY)) { $env:VISION_API_KEY = $env:GRADING_API_KEY }
    if ([string]::IsNullOrWhiteSpace($env:VISION_BASE_URL)) { $env:VISION_BASE_URL = $env:GRADING_BASE_URL }
    if ([string]::IsNullOrWhiteSpace($env:VISION_MODEL)) { $env:VISION_MODEL = 'qwen3.7-plus' }
    if ([string]::IsNullOrWhiteSpace($env:VISION_ENABLED)) { $env:VISION_ENABLED = 'true' }
    Write-Host '已按百炼 qwen3.7-plus 启用文字与多模态批改（密钥不落盘）。' -ForegroundColor Cyan
}

$stdout = Join-Path $logDirectory 'console.log'
$stderr = Join-Path $logDirectory 'error.log'
$configUri = 'file:' + $config.Replace('\', '/')
$arguments = "--enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 -XX:MaxRAMPercentage=75 -jar `"$jar`" `"--spring.config.additional-location=$configUri`""

Write-Host '正在启动教学作业智能评估平台……' -ForegroundColor Cyan
$appStart = @{
    FilePath = $java
    ArgumentList = $arguments
    WorkingDirectory = $root
    WindowStyle = 'Hidden'
    RedirectStandardOutput = $stdout
    RedirectStandardError = $stderr
    PassThru = $true
}
$process = Start-Process @appStart
[IO.File]::WriteAllText($appPidFile, [string]$process.Id, [Text.UTF8Encoding]::new($false))

$ready = $false
for ($attempt = 1; $attempt -le 120; $attempt++) {
    if ($process.HasExited) { break }
    try {
        $health = Invoke-RestMethod -Uri "$url/api/health" -TimeoutSec 2
        if ($health.status -eq 'UP') { $ready = $true; break }
    } catch { Start-Sleep -Seconds 1 }
    $process.Refresh()
}

if (-not $ready) {
    if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force }
    Remove-Item -LiteralPath $appPidFile -Force -ErrorAction SilentlyContinue
    if ($startedMySql) { Stop-StartedMySql $credentials }
    Write-Host '启动失败，最后的应用日志如下：' -ForegroundColor Red
    if (Test-Path -LiteralPath $stderr) { Get-Content -Tail 40 -LiteralPath $stderr }
    if (Test-Path -LiteralPath $stdout) { Get-Content -Tail 40 -LiteralPath $stdout }
    exit 1
}

Write-Host "启动成功：$url（批改记录与 SQL 沙箱均使用便携 MySQL）" -ForegroundColor Green
if (-not $NoOpen) { Start-Process $url }
