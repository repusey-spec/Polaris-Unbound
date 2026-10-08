param([string]$Root = $env:GITHUB_WORKSPACE)
$ErrorActionPreference = 'Stop'

$base = Join-Path $Root 'yongbongE-build'
$b64 = ''
foreach ($name in @('source.b64.part0','source.b64.part1a','source.b64.part1b','source.b64.part2a','source.b64.part2b','source.b64.part3')) {
  $b64 += (Get-Content (Join-Path $base $name) -Raw).Trim()
}

$zip = Join-Path $Root 'yongbongE-source-v0.2.zip'
[IO.File]::WriteAllBytes($zip, [Convert]::FromBase64String($b64))
$dest = Join-Path $Root 'src'
New-Item -ItemType Directory -Force $dest | Out-Null
& 7z x $zip "-o$dest" -y
if ($LASTEXITCODE -ne 0) { throw "source extract failed" }

$src = Join-Path $dest 'TinyFtpDrive'
python (Join-Path $base 'apply-client-v03.py') (Join-Path $src 'FtpClient.cs')
if ($LASTEXITCODE -ne 0) { throw "client transform failed" }
python (Join-Path $base 'apply-fs-v03.py') (Join-Path $src 'FtpFileSystem.cs')
if ($LASTEXITCODE -ne 0) { throw "filesystem transform failed" }

$utf8 = New-Object System.Text.UTF8Encoding($false)
$proj = Join-Path $src 'TinyFtpDrive.csproj'
$pc = [IO.File]::ReadAllText($proj)
$pc = $pc.Replace('<Version>0.2.0</Version>', '<Version>0.3.0</Version>')
[IO.File]::WriteAllText($proj, $pc, $utf8)
[IO.File]::WriteAllText((Join-Path $src 'VERSION.txt'), "0.3.0" + [Environment]::NewLine, $utf8)

$settings = Join-Path $src 'SettingsForm.cs'
$sc = [IO.File]::ReadAllText($settings)
$sc = $sc.Replace('대용량 파일 부분 읽기', '적응형 읽기 캐시')
[IO.File]::WriteAllText($settings, $sc, $utf8)

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
AppVersion=0.3.0
AppPublisher=yongbongE
DefaultDirName={autopf}\yongbongE
DefaultGroupName=yongbongE
DisableProgramGroupPage=yes
OutputDir=dist
OutputBaseFilename=yongbongE-Setup-v0.3
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

[Code]
function WinFspInstalled(): Boolean;
begin
  Result := FileExists(ExpandConstant('{sys}\drivers\winfsp-x64.sys'));
end;
'@

[IO.File]::WriteAllText((Join-Path $Root 'yongbongE.iss'), $iss, $utf8)
New-Item -ItemType Directory -Force (Join-Path $Root 'dist') | Out-Null
& 'C:\Program Files (x86)\Inno Setup 6\ISCC.exe' (Join-Path $Root 'yongbongE.iss')
if ($LASTEXITCODE -ne 0) { throw "installer build failed" }
