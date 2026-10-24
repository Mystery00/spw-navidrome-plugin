param([string]$Spmod, [string]$DataRoot = "$env:APPDATA\Salt Player for Windows")
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$meta = Get-Content -LiteralPath (Join-Path $repo 'project.json') -Raw | ConvertFrom-Json
if (-not $Spmod) { $Spmod = Join-Path $repo "build\dist\plugin-$($meta.pluginId)-$($meta.version).spmod" }
$running = @(Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -like '*Salt Player*' -or $_.Path -like '*Salt Player for Windows*' })
if ($running.Count) { throw 'SPW is running. Close it normally before installation; no process will be terminated.' }
$pluginRoot = Join-Path $DataRoot 'workshop\plugins'
$existing = @(Get-ChildItem -LiteralPath $pluginRoot -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -like '*vip.mystery0.spw.navidrome*' -or $_.Name -like '*io.github.mystery00.spw.navidrome*' })
if ($existing.Count) { throw 'An existing version was found. Archive that plugin directory outside workshop/plugins before upgrading.' }
$target = Join-Path $pluginRoot "plugin-$($meta.pluginId)-$($meta.version)"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Spmod))
try {
    $manifest = $zip.GetEntry('META-INF/MANIFEST.MF')
    if (-not $manifest) { throw 'Missing plugin manifest.' }
    $reader = [IO.StreamReader]::new($manifest.Open())
    try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
    if ($text -notmatch 'Plugin-Id: vip.mystery0.spw.navidrome\r?\n') { throw 'Unexpected plugin ID.' }
    $boundary = [IO.Path]::GetFullPath($target).TrimEnd('\') + '\'
    foreach ($entry in $zip.Entries) {
        $resolved = [IO.Path]::GetFullPath((Join-Path $target $entry.FullName))
        if (-not $resolved.StartsWith($boundary, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe archive path.' }
    }
} finally { $zip.Dispose() }
[IO.Directory]::CreateDirectory($target) | Out-Null
[IO.Compression.ZipFile]::ExtractToDirectory((Resolve-Path -LiteralPath $Spmod), $target)
Write-Host "Installed: $target"
Write-Host 'Open SPW Workshop, enable SPW Navidrome, and open its configuration page.'
