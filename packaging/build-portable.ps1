param(
    [switch]$SkipBuild,
    [ValidatePattern('^[A-Za-z0-9._-]+$')]
    [string]$OutputName = 'aes-agent-portable-windows'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$targetRoot = [IO.Path]::GetFullPath((Join-Path $root 'target'))
$staging = [IO.Path]::GetFullPath((Join-Path $targetRoot $OutputName))
$zipPath = [IO.Path]::GetFullPath((Join-Path $targetRoot "$OutputName.zip"))
$checksumPath = "$zipPath.sha256"

foreach ($path in @($staging, $zipPath, $checksumPath)) {
    if (-not $path.StartsWith($targetRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "拒绝处理 target 目录之外的路径：$path"
    }
}

Set-Location $root
if (-not $SkipBuild) {
    Write-Host '正在构建 Spring Boot 可执行包……' -ForegroundColor Cyan
    & mvn -B -ntp clean package
    if ($LASTEXITCODE -ne 0) { throw 'Maven 构建失败。' }
}

$jar = Get-ChildItem -LiteralPath (Join-Path $root 'target') -Filter 'aes-agent-*.jar' |
    Where-Object { $_.Name -notLike '*.original' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jar) { throw '未找到 Spring Boot 可执行 JAR。' }

$jlinkCandidates = New-Object System.Collections.Generic.List[string]
if ($env:JAVA_HOME) { $jlinkCandidates.Add((Join-Path $env:JAVA_HOME 'bin\jlink.exe')) }
$mavenVersion = (& mvn -v 2>&1 | Out-String)
$runtimeMatch = [regex]::Match($mavenVersion, 'runtime:\s*([^\r\n]+)', 'IgnoreCase')
if ($runtimeMatch.Success) {
    $jlinkCandidates.Add((Join-Path $runtimeMatch.Groups[1].Value.Trim() 'bin\jlink.exe'))
}
$javaCommand = Get-Command java -ErrorAction SilentlyContinue
if ($javaCommand) {
    $jlinkCandidates.Add((Join-Path (Split-Path $javaCommand.Source -Parent) 'jlink.exe'))
}
$jlink = $jlinkCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $jlink) {
    throw '未找到 jlink。请在构建电脑安装 JDK 17 或更高版本，并设置 JAVA_HOME。'
}
$jdkHome = Split-Path (Split-Path $jlink -Parent) -Parent
$jmods = Join-Path $jdkHome 'jmods'
if (-not (Test-Path -LiteralPath $jmods)) {
    throw "当前 JDK 缺少 jmods 目录，无法生成便携运行时：$jdkHome"
}

$mysqlCandidates = New-Object System.Collections.Generic.List[string]
if ($env:MYSQL_HOME) { $mysqlCandidates.Add([IO.Path]::GetFullPath($env:MYSQL_HOME)) }
$mysqldCommand = Get-Command mysqld.exe -ErrorAction SilentlyContinue
if ($mysqldCommand) {
    $mysqlCandidates.Add((Split-Path (Split-Path $mysqldCommand.Source -Parent) -Parent))
}
$standardMySqlRoot = Join-Path $env:ProgramFiles 'MySQL'
if (Test-Path -LiteralPath $standardMySqlRoot) {
    Get-ChildItem -LiteralPath $standardMySqlRoot -Directory -Filter 'MySQL Server *' |
        Sort-Object Name -Descending | ForEach-Object { $mysqlCandidates.Add($_.FullName) }
}
$mysqlSource = $mysqlCandidates | Where-Object {
    (Test-Path -LiteralPath (Join-Path $_ 'bin\mysqld.exe')) -and
    (Test-Path -LiteralPath (Join-Path $_ 'bin\mysql.exe')) -and
    (Test-Path -LiteralPath (Join-Path $_ 'bin\mysqladmin.exe')) -and
    (Test-Path -LiteralPath (Join-Path $_ 'lib')) -and
    (Test-Path -LiteralPath (Join-Path $_ 'share'))
} | Select-Object -First 1
if (-not $mysqlSource) {
    throw '未找到 MySQL Server 8。请在构建电脑安装 MySQL 8，或设置 MYSQL_HOME。'
}
$mysqlVersion = (& (Join-Path $mysqlSource 'bin\mysqld.exe') --version | Out-String).Trim()
if ($mysqlVersion -notmatch '\bVer\s+8\.') {
    throw "便携包要求 MySQL Server 8，当前检测到：$mysqlVersion"
}

if (Test-Path -LiteralPath $staging) {
    Remove-Item -LiteralPath $staging -Recurse -Force
}
$directories = @(
    (Join-Path $staging 'app'),
    (Join-Path $staging 'config'),
    (Join-Path $staging 'scripts'),
    (Join-Path $staging 'samples'),
    (Join-Path $staging 'data\java_knowledge_base'),
    (Join-Path $staging 'data\database_knowledge_base'),
    (Join-Path $staging 'data\answer_keys'),
    (Join-Path $staging 'data\grading_records'),
    (Join-Path $staging 'data\mysql'),
    (Join-Path $staging 'logs'),
    (Join-Path $staging 'run'),
    (Join-Path $staging 'mysql')
)
New-Item -ItemType Directory -Force -Path $directories | Out-Null

Write-Host '正在生成内置 Java 运行时……' -ForegroundColor Cyan
$jdkVersionText = (& (Join-Path $jdkHome 'bin\java.exe') --version | Out-String)
$jdkVersionMatch = [regex]::Match(
    $jdkVersionText, '(?m)^(?:openjdk|java)\s+(?:version\s+)?"?(?:1\.)?(\d+)')
if (-not $jdkVersionMatch.Success) { throw '无法识别用于打包的 JDK 版本。' }
$jdkMajor = [int]$jdkVersionMatch.Groups[1].Value
$compression = if ($jdkMajor -ge 24) { 'zip-6' } else { '2' }
$jlinkArguments = @(
    '--module-path', $jmods,
    '--add-modules', 'ALL-MODULE-PATH',
    '--bind-services',
    '--strip-debug',
    '--no-header-files',
    '--no-man-pages',
    "--compress=$compression",
    '--output', (Join-Path $staging 'runtime')
)
& $jlink @jlinkArguments
if ($LASTEXITCODE -ne 0) { throw 'jlink 运行时生成失败。' }

Write-Host "正在复制 MySQL 8 及其运行依赖：$mysqlSource" -ForegroundColor Cyan
foreach ($directory in @('bin', 'lib', 'share')) {
    Copy-Item -LiteralPath (Join-Path $mysqlSource $directory) -Destination (Join-Path $staging 'mysql') -Recurse -Force
}
foreach ($file in @('LICENSE', 'README')) {
    $sourceFile = Join-Path $mysqlSource $file
    if (Test-Path -LiteralPath $sourceFile) {
        Copy-Item -LiteralPath $sourceFile -Destination (Join-Path $staging 'mysql') -Force
    }
}

Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $staging 'app\aes-agent.jar')
Copy-Item -Path (Join-Path $root 'data\java_knowledge_base\*') -Destination (Join-Path $staging 'data\java_knowledge_base') -Recurse -Force
Copy-Item -Path (Join-Path $root 'data\database_knowledge_base\*') -Destination (Join-Path $staging 'data\database_knowledge_base') -Recurse -Force
if (Test-Path -LiteralPath (Join-Path $root 'data\answer_keys')) {
    Copy-Item -Path (Join-Path $root 'data\answer_keys\*') -Destination (Join-Path $staging 'data\answer_keys') -Recurse -Force
}
Copy-Item -Path (Join-Path $root 'samples\*') -Destination (Join-Path $staging 'samples') -Recurse -Force
$courseCases = Join-Path $root '专业课程作业批改案例'
if (Test-Path -LiteralPath $courseCases) {
    Copy-Item -LiteralPath $courseCases -Destination (Join-Path $staging 'samples') -Recurse -Force
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'portable\application.properties') -Destination (Join-Path $staging 'config\application.properties')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'portable\start.ps1') -Destination (Join-Path $staging 'scripts\start.ps1')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'portable\stop.ps1') -Destination (Join-Path $staging 'scripts\stop.ps1')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'portable\doctor.ps1') -Destination (Join-Path $staging 'scripts\doctor.ps1')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'portable\start.cmd') -Destination (Join-Path $staging 'start.cmd')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'portable\stop.cmd') -Destination (Join-Path $staging 'stop.cmd')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'portable\doctor.cmd') -Destination (Join-Path $staging 'doctor.cmd')
Copy-Item -LiteralPath (Join-Path $root 'README.md') -Destination (Join-Path $staging 'README.md')

$versionText = "AES Agent $($jar.BaseName)`r`nBuilt: $([DateTimeOffset]::Now.ToString('u'))`r`nJava runtime: $jlink`r`nMySQL: $mysqlVersion`r`n"
[IO.File]::WriteAllText(
    (Join-Path $staging 'VERSION.txt'), $versionText, [Text.UTF8Encoding]::new($false))

if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
if (Test-Path -LiteralPath $checksumPath) { Remove-Item -LiteralPath $checksumPath -Force }
Write-Host '正在压缩便携包……' -ForegroundColor Cyan
Compress-Archive -Path $staging -DestinationPath $zipPath -CompressionLevel Optimal

$sha256 = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText(
    $checksumPath,
    "$sha256  $([IO.Path]::GetFileName($zipPath))`n",
    [Text.UTF8Encoding]::new($false))

$sizeMb = [math]::Round((Get-Item -LiteralPath $zipPath).Length / 1MB, 1)
Write-Host "便携包已生成：$zipPath ($sizeMb MB)" -ForegroundColor Green
Write-Host "SHA-256 校验文件：$checksumPath" -ForegroundColor Green
