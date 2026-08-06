$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$java = Join-Path $root 'runtime\bin\java.exe'
$jar = Join-Path $root 'app\aes-agent.jar'
$config = Join-Path $root 'config\application.properties'
$mysqld = Join-Path $root 'mysql\bin\mysqld.exe'
$mysql = Join-Path $root 'mysql\bin\mysql.exe'
$mysqlAdmin = Join-Path $root 'mysql\bin\mysqladmin.exe'
$data = Join-Path $root 'data'
$mysqlRoot = Join-Path $data 'mysql'
$credentialsFile = Join-Path $mysqlRoot 'credentials.properties'
$mysqlPidFile = Join-Path $root 'run\mysql.pid'
$samples = Join-Path $root 'samples'
$appPort = if ($env:PORT -match '^\d+$') { [int]$env:PORT } else { 8080 }
$mysqlPort = if ($env:MYSQL_PORT -match '^\d+$') { [int]$env:MYSQL_PORT } else { 3307 }
$failed = $false

function Check-Path([string]$label, [string]$path) {
    if (Test-Path -LiteralPath $path) { Write-Host "[PASS] $label" -ForegroundColor Green }
    else { Write-Host "[FAIL] ${label}: $path" -ForegroundColor Red; $script:failed = $true }
}

function Check-FileCount([string]$label, [string]$path, [string]$filter, [int]$minimum) {
    $count = if (Test-Path -LiteralPath $path) {
        @(Get-ChildItem -LiteralPath $path -Filter $filter -File -ErrorAction SilentlyContinue).Count
    } else { 0 }
    if ($count -ge $minimum) { Write-Host "[PASS] ${label}: $count" -ForegroundColor Green }
    else { Write-Host "[FAIL] ${label}: 至少需要 $minimum，实际 $count" -ForegroundColor Red; $script:failed = $true }
}

Write-Host 'AES Agent Windows 便携包自检' -ForegroundColor Cyan
Check-Path '内置 Java 运行时' $java
Check-Path '应用 JAR' $jar
Check-Path '应用配置' $config
Check-Path '内置 MySQL Server' $mysqld
Check-Path 'MySQL 客户端' $mysql
Check-Path 'MySQL 管理工具' $mysqlAdmin
Check-Path '可写数据目录' $data
Check-FileCount '演示指南' $samples '*.txt' 1
Check-FileCount '演示作业' $samples '*.docx' 2
Check-FileCount '图片答案' $samples '*.png' 2

$courseCaseCount = if (Test-Path -LiteralPath $samples) {
    @(Get-ChildItem -LiteralPath $samples -Filter '*.docx' -File -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^(Java-|DB-)' }).Count
} else { 0 }
if ($courseCaseCount -ge 8) { Write-Host "[PASS] 批量批改课程案例：$courseCaseCount" -ForegroundColor Green }
else { Write-Host "[FAIL] 批量课程案例：至少需要 8，实际 $courseCaseCount" -ForegroundColor Red; $failed = $true }

Check-Path '结构化答案库' (Join-Path $data 'answer_keys')
Check-Path '旧 JSON 迁移/备份目录' (Join-Path $data 'grading_records')

if (Test-Path -LiteralPath $java) {
    Write-Host ('[INFO] ' + ((& $java --version | Select-Object -First 1) -join ''))
}
if (Test-Path -LiteralPath $mysqld) {
    Write-Host ('[INFO] ' + ((& $mysqld --version | Select-Object -First 1) -join ''))
}

try {
    New-Item -ItemType Directory -Force -Path $mysqlRoot | Out-Null
    $probe = Join-Path $mysqlRoot ('.write-test-' + [Guid]::NewGuid().ToString('N'))
    [IO.File]::WriteAllText($probe, 'ok', [Text.UTF8Encoding]::new($false))
    Remove-Item -LiteralPath $probe -Force
    Write-Host '[PASS] MySQL 数据目录可写。' -ForegroundColor Green
} catch {
    Write-Host '[FAIL] 目录不可写；请完整解压到硬盘，或解除 U 盘写保护。' -ForegroundColor Red
    $failed = $true
}

$mysqlProcess = $null
if (Test-Path -LiteralPath $mysqlPidFile) {
    $pidValue = 0
    if ([int]::TryParse((Get-Content -Raw -LiteralPath $mysqlPidFile), [ref]$pidValue)) {
        $mysqlProcess = Get-Process -Id $pidValue -ErrorAction SilentlyContinue
    }
}

if ($mysqlProcess) {
    if (-not (Test-Path -LiteralPath $credentialsFile)) {
        Write-Host '[FAIL] MySQL 正在运行，但凭据文件缺失。' -ForegroundColor Red
        $failed = $true
    } else {
        $values = @{}
        foreach ($line in Get-Content -Encoding UTF8 -LiteralPath $credentialsFile) {
            if ($line -match '^([^#=]+)=(.*)$') { $values[$matches[1].Trim()] = $matches[2].Trim() }
        }
        if ($values['port'] -match '^\d+$' -and -not ($env:MYSQL_PORT -match '^\d+$')) {
            $mysqlPort = [int]$values['port']
        }
        $oldPassword = $env:MYSQL_PWD
        try {
            $env:MYSQL_PWD = $values['app.password']
            $probeOut = Join-Path $root 'logs\doctor-mysql.out'
            $probeErr = Join-Path $root 'logs\doctor-mysql.err'
            New-Item -ItemType Directory -Force -Path (Join-Path $root 'logs') | Out-Null
            $probeArguments = @(
                '--no-defaults', '--protocol=TCP', '--host=127.0.0.1',
                "--port=$mysqlPort", '--user=aes_agent', '--database=aes_agent',
                '--execute="SELECT 1"'
            )
            $probe = Start-Process -FilePath $mysql -ArgumentList $probeArguments -Wait -PassThru -NoNewWindow -RedirectStandardOutput $probeOut -RedirectStandardError $probeErr
            if ($probe.ExitCode -eq 0) { Write-Host "[PASS] 便携 MySQL 正在运行：127.0.0.1:$mysqlPort" -ForegroundColor Green }
            else { Write-Host '[FAIL] MySQL 账号连接失败。' -ForegroundColor Red; $failed = $true }
        } finally {
            if ($null -eq $oldPassword) { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
            else { $env:MYSQL_PWD = $oldPassword }
        }
    }
} elseif (Test-Path -LiteralPath (Join-Path $mysqlRoot 'data\mysql')) {
    if (Test-Path -LiteralPath $credentialsFile) { Write-Host '[PASS] 已存在可随文件夹迁移的 MySQL 数据，当前服务未启动。' -ForegroundColor Green }
    else { Write-Host '[FAIL] 已有 MySQL 数据但凭据文件缺失，请恢复完整目录。' -ForegroundColor Red; $failed = $true }
} else {
    Write-Host "[INFO] MySQL 尚未初始化；首次运行 start.cmd 时会自动初始化端口 $mysqlPort。"
}

try {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$appPort/api/health" -TimeoutSec 2
    if ($health.status -eq 'UP') { Write-Host "[PASS] 应用正在运行：http://127.0.0.1:$appPort" -ForegroundColor Green }
} catch { Write-Host "[INFO] 应用尚未运行；start.cmd 将使用端口 $appPort。" }

if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY) -and
    [string]::IsNullOrWhiteSpace($env:GRADING_API_KEY)) {
    Write-Host '[INFO] 未预设模型密钥；start.cmd 会安全询问，且不会把密钥写入便携包。'
} else { Write-Host '[PASS] 已检测到模型密钥环境变量（值已隐藏）。' -ForegroundColor Green }

if ($failed) { exit 1 }
Write-Host '全部检查通过，可以运行 start.cmd。' -ForegroundColor Green
