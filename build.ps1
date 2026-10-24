param([string]$HostHome = $env:SPW_HOME, [string]$JdkHome = $env:JAVA_HOME, [switch]$TestOnly)
$ErrorActionPreference = 'Stop'
if (-not $JdkHome) {
    $candidates = @(Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -ErrorAction SilentlyContinue | Where-Object Name -like '*21*' | Sort-Object Name -Descending)
    if ($candidates.Count) { $JdkHome = $candidates[0].FullName }
}
if (-not $JdkHome -or -not (Test-Path "$JdkHome\bin\javac.exe")) { throw 'Set JAVA_HOME or -JdkHome to JDK 21 or later.' }
$buildArgs = @("$PSScriptRoot\tools\build.py", '--jdk', $JdkHome)
if ($HostHome) { $buildArgs += @('--host', $HostHome) }
if ($TestOnly) { $buildArgs += '--test-only' }
python @buildArgs
if ($LASTEXITCODE -ne 0) { throw "Build failed: $LASTEXITCODE" }
