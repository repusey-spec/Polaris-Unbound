param([string]$Root = $env:GITHUB_WORKSPACE)
$ErrorActionPreference = 'Stop'

$base = Join-Path $Root 'yongbongE-build'
$b64 = ''
foreach ($name in @('v04-src.xz.b64.part0a','v04-src.xz.b64.part0b1','v04-src.xz.b64.part0b2','v04-src.xz.b64.part1a','v04-src.xz.b64.part1b','v04-src.xz.b64.part2','v04-src.xz.b64.part3','v04-src.xz.b64.part4')) {
  $b64 += (Get-Content (Join-Path $base $name) -Raw).Trim()
}
$archive = Join-Path $Root 'yongbongE-source-v0.4.tar.xz'
[IO.File]::WriteAllBytes($archive, [Convert]::FromBase64String($b64))
$sourceHash = (Get-FileHash $archive -Algorithm SHA256).Hash
if ($sourceHash -ne '96889E373572185EC6B97285F8614D1C3DDE189B95CCAE5074EEE1031F07A015') {
  throw "v0.4 source hash mismatch: $sourceHash"
}

$srcroot = Join-Path $Root 'src'
New-Item -ItemType Directory -Force $srcroot | Out-Null
tar -xJf $archive -C $srcroot
if ($LASTEXITCODE -ne 0) { throw "source extract failed" }

foreach ($patchName in @('v05-client-rel.patch','v05-fs-rel.patch')) {
  $encoded = Get-Content (Join-Path $base ($patchName + '.xz.b64')) -Raw
  $xz = Join-Path $Root ($patchName + '.xz')
  [IO.File]::WriteAllBytes($xz, [Convert]::FromBase64String($encoded.Trim()))
  & 7z x $xz "-o$Root" -y | Out-Host
  if ($LASTEXITCODE -ne 0) { throw "patch extract failed: $patchName" }
}

$src = Join-Path $srcroot 'TinyFtpDrive'
Push-Location $src
git apply --whitespace=nowarn (Join-Path $Root 'v05-client-rel.patch')
if ($LASTEXITCODE -ne 0) { throw "client patch failed" }
git apply --whitespace=nowarn (Join-Path $Root 'v05-fs-rel.patch')
if ($LASTEXITCODE -ne 0) { throw "filesystem patch failed" }
Pop-Location

function Get-NormalizedSha256([string]$Path) {
  $text = [IO.File]::ReadAllText($Path).Replace("`r`n", "`n")
  $bytes = (New-Object System.Text.UTF8Encoding($false)).GetBytes($text)
  $sha = [Security.Cryptography.SHA256]::Create()
  try { return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace("-", "") }
  finally { $sha.Dispose() }
}
$clientHash = Get-NormalizedSha256 (Join-Path $src 'FtpClient.cs')
$fsHash = Get-NormalizedSha256 (Join-Path $src 'FtpFileSystem.cs')
Write-Host "FtpClient normalized SHA256:" $clientHash
Write-Host "FtpFileSystem normalized SHA256:" $fsHash
if ($clientHash -ne '7D8CFC726EDA062799AF5C6F0A77149376582EB38F1F67F63517E28911811566') { throw "FtpClient normalized hash mismatch" }
if ($fsHash -ne '79157D1D80B241C3271E8C06AA2F36EF404CFA7E58249067F3B69DB852E49DE2') { throw "FtpFileSystem normalized hash mismatch" }

foreach ($patchName in @('v06-client.patch','v06-fs.patch')) {
  $encoded = Get-Content (Join-Path $base ($patchName + '.xz.b64')) -Raw
  $xz = Join-Path $Root ($patchName + '.xz')
  [IO.File]::WriteAllBytes($xz, [Convert]::FromBase64String($encoded.Trim()))
  & 7z x $xz "-o$Root" -y | Out-Host
  if ($LASTEXITCODE -ne 0) { throw "patch extract failed: $patchName" }
}

Push-Location $src
git apply --whitespace=nowarn (Join-Path $Root 'v06-client.patch')
if ($LASTEXITCODE -ne 0) { throw "v0.6 client patch failed" }
git apply --whitespace=nowarn (Join-Path $Root 'v06-fs.patch')
if ($LASTEXITCODE -ne 0) { throw "v0.6 filesystem patch failed" }
Pop-Location

$clientHash = Get-NormalizedSha256 (Join-Path $src 'FtpClient.cs')
$fsHash = Get-NormalizedSha256 (Join-Path $src 'FtpFileSystem.cs')
Write-Host "v0.6 FtpClient normalized SHA256:" $clientHash
Write-Host "v0.6 FtpFileSystem normalized SHA256:" $fsHash
if ($clientHash -ne 'D318104E9A3BE74E6599A421010033805399380FB41B073903FDE3A942062EA6') { throw "v0.6 FtpClient hash mismatch" }
if ($fsHash -ne '7F8BE8885E9C0626A645C4AA3EA7BD8753F0985DB4B2BB28461E0373B1D00C73') { throw "v0.6 FtpFileSystem hash mismatch" }

$utf8 = New-Object System.Text.UTF8Encoding($false)
$proj = Join-Path $src 'TinyFtpDrive.csproj'
$pc = [IO.File]::ReadAllText($proj)
$pc = $pc.Replace('<Version>0.4.0</Version>', '<Version>0.6.0</Version>')
[IO.File]::WriteAllText($proj, $pc, $utf8)
[IO.File]::WriteAllText((Join-Path $src 'VERSION.txt'), "0.6.0" + [Environment]::NewLine, $utf8)

dotnet restore $proj
dotnet build $proj -c Release --no-restore
if ($LASTEXITCODE -ne 0) { throw "dotnet build failed" }

$winfsp = Join-Path $Root 'winfsp-2.2.26215.msi'
Invoke-WebRequest -Uri 'https://github.com/winfsp/winfsp/releases/download/v2.2B4/winfsp-2.2.26215.msi' -OutFile $winfsp
$hash = (Get-FileHash $winfsp -Algorithm SHA256).Hash
if ($hash -ne '2ECB5C89405488A95BBD8A01875E02C48534FD37BBDFD84488F7590464D65944') { throw "WinFsp hash mismatch" }

choco install innosetup --no-progress -y

$iss = @'
[Setup]
AppId=TinyFtpDrive.0.1
AppName=yongbongE
AppVersion=0.6.0
AppPublisher=yongbongE
DefaultDirName={autopf}\yongbongE
DefaultGroupName=yongbongE
DisableProgramGroupPage=yes
OutputDir=dist
OutputBaseFilename=yongbongE-Setup-v0.6
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayName=yongbongE
CloseApplications=yes
RestartApplications=no

[Files]
Source: "src\TinyFtpDrive\bin\Release\net48\yongbongE.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "src\TinyFtpDrive\bin\Release\net48\yongbongE.exe.config"; DestDir: "{app}"; Flags: ignoreversion
Source: "src\TinyFtpDrive\bin\Release\net48\winfsp-msil.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "winfsp-2.2.26215.msi"; DestDir: "{tmp}"; Flags: deleteafterinstall

[Icons]
Name: "{group}\yongbongE"; Filename: "{app}\yongbongE.exe"

[Run]
Filename: "msiexec.exe"; Parameters: "/i ""{tmp}\winfsp-2.2.26215.msi"" /qn /norestart"; StatusMsg: "WinFsp 파일 시스템 드라이버 설치 중..."; Flags: waituntilterminated; Check: not WinFspInstalled
Filename: "{app}\yongbongE.exe"; Description: "yongbongE 실행"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{cmd}"; Parameters: "/C taskkill /IM yongbongE.exe /F >NUL 2>&1 & exit /B 0"; Flags: runhidden; RunOnceId: "StopyongbongE"

[Code]
function WinFspInstalled(): Boolean;
begin
  Result := FileExists(ExpandConstant('{sys}\drivers\winfsp-x64.sys'));
end;

function InitializeSetup(): Boolean;
var
  ResultCode: Integer;
begin
  Exec(ExpandConstant('{cmd}'),
    '/C taskkill /IM yongbongE.exe /F >NUL 2>&1 & taskkill /IM TinyFtpDrive.exe /F >NUL 2>&1 & exit /B 0',
    '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Result := True;
end;
'@
[IO.File]::WriteAllText((Join-Path $Root 'yongbongE.iss'), $iss, $utf8)
New-Item -ItemType Directory -Force (Join-Path $Root 'dist') | Out-Null
& 'C:\Program Files (x86)\Inno Setup 6\ISCC.exe' (Join-Path $Root 'yongbongE.iss')
if ($LASTEXITCODE -ne 0) { throw "installer build failed" }
