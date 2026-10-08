param([string]$SourceDir, [string]$BaseDir, [string]$Root)
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($SourceDir)) { throw 'SourceDir is required' }
if ([string]::IsNullOrWhiteSpace($BaseDir)) { throw 'BaseDir is required' }
if ([string]::IsNullOrWhiteSpace($Root)) { throw 'Root is required' }

$encoded = Get-Content (Join-Path $BaseDir 'v11-core.patch.xz.b64') -Raw
$xz = Join-Path $Root 'v11-core.patch.xz'
$patch = Join-Path $Root 'v11-core.patch'
[IO.File]::WriteAllBytes($xz, [Convert]::FromBase64String($encoded.Trim()))
& 7z x $xz "-o$Root" -y | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'v0.11 patch extract failed' }

Push-Location $SourceDir
git apply --verbose --ignore-space-change --ignore-whitespace --whitespace=nowarn $patch
if ($LASTEXITCODE -ne 0) { throw 'v0.11 core patch failed' }
Pop-Location

$clientCheck = [IO.File]::ReadAllText((Join-Path $SourceDir 'FtpClient.cs'))
$fsCheck = [IO.File]::ReadAllText((Join-Path $SourceDir 'FtpFileSystem.cs'))
if (-not $clientCheck.Contains('private const int MetadataSlotWaitTimeoutMs = 250;')) { throw 'v0.11 client patch marker missing' }
if (-not $clientCheck.Contains('TimeSpan.FromSeconds(30)')) { throw 'v0.11 directory-cache marker missing' }
if (-not $fsCheck.Contains('private const long MaxRuntimeLogBytes = 1024L * 1024L;')) { throw 'v0.11 filesystem patch marker missing' }
if (-not $fsCheck.Contains('private readonly object _adsSync = new object();')) { throw 'v0.11 ADS-lock marker missing' }

$programPath = Join-Path $SourceDir 'Program.cs'
$text = [IO.File]::ReadAllText($programPath)
$old = '            int status = _host.Mount(drive, null, true, 0);'
$new = '            // Fine-grained WinFsp dispatcher: slow MP3 property reads must never serialize ReadDirectory.' + [Environment]::NewLine +
       '            int status = _host.MountEx(drive, 0, null, false, 0);'

if (($text.Split($old).Length - 1) -ne 1) { throw 'v0.11 Program.cs mount marker mismatch' }
$text = $text.Replace($old, $new)
[IO.File]::WriteAllText($programPath, $text, (New-Object System.Text.UTF8Encoding($false)))
