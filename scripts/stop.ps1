$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Host '未检测到 Docker。' -ForegroundColor Red
    exit 1
}

& docker compose -f compose.yaml down
if ($LASTEXITCODE -eq 0) {
    Write-Host '服务已停止，MySQL 业务数据与知识库索引均已保留。' -ForegroundColor Green
} else {
    exit $LASTEXITCODE
}
