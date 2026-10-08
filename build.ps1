param([string[]]$Tasks = @('assembleDebug','testDebugUnitTest'), [switch]$Online)
$ErrorActionPreference = 'Stop'
# JDK on Windows reads Gradle worker argument files with the OS code page.
# A temporary drive alias keeps the source in its Chinese-named project folder.
$buildDrive = @('V:','W:','X:','Y:','Z:') | Where-Object { -not (Test-Path "$_\") } | Select-Object -First 1
if (-not $buildDrive) { throw '没有空闲盘符可用于临时构建映射。' }
& subst.exe $buildDrive $PSScriptRoot
if ($LASTEXITCODE -ne 0) { throw '无法创建临时构建映射。' }
try {
    $buildOptions = @('--console=plain', '-Pkotlin.incremental=false')
    if (-not $Online) { $buildOptions += '--offline' }
    & "$buildDrive\gradlew.bat" -p "$buildDrive\" @buildOptions @Tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败：$LASTEXITCODE" }
} finally {
    & subst.exe $buildDrive /D
}
