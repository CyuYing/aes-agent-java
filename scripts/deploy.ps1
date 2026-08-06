param(
    [switch]$NoOpen
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root

function Stop-WithMessage([string]$message) {
    Write-Host "`n部署失败：$message" -ForegroundColor Red
    exit 1
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Stop-WithMessage '未检测到 Docker Desktop，请先安装并启动 Docker Desktop。'
}

& docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Stop-WithMessage 'Docker 尚未启动，请启动 Docker Desktop 后重试。'
}

$envFile = Join-Path $root '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    Copy-Item -LiteralPath (Join-Path $root '.env.example') -Destination $envFile
    Write-Host '已创建本机配置 .env（该文件不会提交到 Git）。' -ForegroundColor Cyan
}

$envText = [IO.File]::ReadAllText($envFile)
function Set-EnvTextValue([string]$name, [string]$value) {
    $pattern = '(?m)^' + [regex]::Escape($name) + '=.*$'
    if ([regex]::IsMatch($script:envText, $pattern)) {
        $script:envText = [regex]::Replace(
            $script:envText, $pattern,
            [System.Text.RegularExpressions.MatchEvaluator]{ param($match) "$name=$value" })
    } else {
        $script:envText += "`n$name=$value`n"
    }
}

function New-HexSecret {
    $bytes = New-Object byte[] 32
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
    } finally {
        $generator.Dispose()
    }
    return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
}

foreach ($databaseSecret in @('MYSQL_ROOT_PASSWORD', 'MYSQL_PASSWORD', 'MYSQL_SANDBOX_PASSWORD')) {
    $match = [regex]::Match(
        $envText, '(?m)^' + [regex]::Escape($databaseSecret) + '=(.*)$')
    if (-not $match.Success -or [string]::IsNullOrWhiteSpace($match.Groups[1].Value)) {
        Set-EnvTextValue $databaseSecret (New-HexSecret)
    }
}

$gradingKeyMatch = [regex]::Match($envText, '(?m)^GRADING_API_KEY=(.*)$')
$deepSeekKeyMatch = [regex]::Match($envText, '(?m)^DEEPSEEK_API_KEY=(.*)$')
$currentKey = if ($gradingKeyMatch.Success -and -not [string]::IsNullOrWhiteSpace($gradingKeyMatch.Groups[1].Value)) {
    $gradingKeyMatch.Groups[1].Value.Trim()
} elseif ($deepSeekKeyMatch.Success) { $deepSeekKeyMatch.Groups[1].Value.Trim() } else { '' }
if ([string]::IsNullOrWhiteSpace($currentKey)) {
    Write-Host '请输入批改模型 API Key（支持百炼或 DeepSeek）；直接回车可稍后配置。' -ForegroundColor Yellow
    $secureKey = Read-Host 'Model API Key' -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
    try {
        $plainKey = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
    if (-not [string]::IsNullOrWhiteSpace($plainKey)) {
        if ($plainKey.StartsWith('sk-ws-', [StringComparison]::OrdinalIgnoreCase)) {
            Set-EnvTextValue 'GRADING_API_KEY' $plainKey
            Set-EnvTextValue 'GRADING_BASE_URL' 'https://dashscope.aliyuncs.com/compatible-mode/v1'
            Set-EnvTextValue 'GRADING_MODEL' 'qwen3.7-plus'
            Set-EnvTextValue 'VISION_ENABLED' 'true'
            Set-EnvTextValue 'VISION_API_KEY' $plainKey
            Set-EnvTextValue 'VISION_BASE_URL' 'https://dashscope.aliyuncs.com/compatible-mode/v1'
            Set-EnvTextValue 'VISION_MODEL' 'qwen3.7-plus'
        } else {
            Set-EnvTextValue 'DEEPSEEK_API_KEY' $plainKey
        }
        [IO.File]::WriteAllText($envFile, $envText, [Text.UTF8Encoding]::new($false))
    }
}

$securityMatch = [regex]::Match($envText, '(?m)^AES_SECURITY_ENABLED=(.*)$')
$securityEnabled = $securityMatch.Success -and
    $securityMatch.Groups[1].Value.Trim().Equals('true', [StringComparison]::OrdinalIgnoreCase)
$passwordMatch = [regex]::Match($envText, '(?m)^AES_SECURITY_PASSWORD=(.*)$')
$currentPassword = if ($passwordMatch.Success) { $passwordMatch.Groups[1].Value.Trim() } else { '' }
if ($securityEnabled -and [string]::IsNullOrWhiteSpace($currentPassword)) {
    Write-Host '服务器版已启用教师登录，请设置访问密码。' -ForegroundColor Yellow
    $securePassword = Read-Host '教师登录密码' -AsSecureString
    $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    try {
        $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
    if ([string]::IsNullOrWhiteSpace($plainPassword)) {
        Stop-WithMessage '已启用访问鉴权，教师登录密码不能为空。'
    }
    if ($passwordMatch.Success) {
        $envText = [regex]::Replace(
            $envText,
            '(?m)^AES_SECURITY_PASSWORD=.*$',
            [System.Text.RegularExpressions.MatchEvaluator]{ param($match) "AES_SECURITY_PASSWORD=$plainPassword" })
    } else {
        $envText += "`nAES_SECURITY_PASSWORD=$plainPassword`n"
    }
    [IO.File]::WriteAllText($envFile, $envText, [Text.UTF8Encoding]::new($false))
}

[IO.File]::WriteAllText($envFile, $envText, [Text.UTF8Encoding]::new($false))

Write-Host "`n正在构建并启动应用、MySQL 与 Chroma，请稍候……" -ForegroundColor Cyan
& docker compose -f compose.yaml up -d --build
if ($LASTEXITCODE -ne 0) {
    & docker compose -f compose.yaml logs --tail 80
    Stop-WithMessage '容器构建或启动失败，请查看上面的日志。'
}

$portMatch = [regex]::Match([IO.File]::ReadAllText($envFile), '(?m)^APP_PORT=(\d+)$')
$port = if ($portMatch.Success) { [int]$portMatch.Groups[1].Value } else { 8080 }
$healthUrl = "http://127.0.0.1:$port/api/health"
$ready = $false
for ($attempt = 1; $attempt -le 120; $attempt++) {
    try {
        $response = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 2
        if ($response.status -eq 'UP') {
            $ready = $true
            break
        }
    } catch {
        Start-Sleep -Seconds 1
    }
}

if (-not $ready) {
    & docker compose -f compose.yaml ps
    & docker compose -f compose.yaml logs app --tail 100
    Stop-WithMessage '服务未能在预期时间内通过健康检查。'
}

$appUrl = "http://localhost:$port"
Write-Host "`n部署完成：$appUrl" -ForegroundColor Green
Write-Host '停止服务：双击 stop.cmd；查看状态：docker compose ps'
if (-not $NoOpen) {
    Start-Process $appUrl
}
