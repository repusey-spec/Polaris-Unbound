param([string]$Root = $env:GITHUB_WORKSPACE)
$ErrorActionPreference = 'Stop'

$base = Join-Path $Root 'yongbongE-build'
$b64 = ''
0..4 | ForEach-Object {
  $b64 += (Get-Content (Join-Path $base ("v04-src.xz.b64.part" + $_)) -Raw).Trim()
}
$archive = Join-Path $Root 'yongbongE-source-v0.4.tar.xz'
[IO.File]::WriteAllBytes($archive, [Convert]::FromBase64String($b64))
Write-Host "SOURCE SHA256:" (Get-FileHash $archive -Algorithm SHA256).Hash

$srcroot = Join-Path $Root 'src'
New-Item -ItemType Directory -Force $srcroot | Out-Null
tar -xJf $archive -C $srcroot
if ($LASTEXITCODE -ne 0) { throw "source extract failed" }

$proj = Join-Path $srcroot 'TinyFtpDrive\TinyFtpDrive.csproj'
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
AppVersion=0.4.0
AppPublisher=yongbongE
DefaultDirName={autopf}\yongbongE
DefaultGroupName=yongbongE
DisableProgramGroupPage=yes
OutputDir=dist
OutputBaseFilename=yongbongE-Setup-v0.4
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
[IO.File]::WriteAllText((Join-Path $Root 'yongbongE.iss'), $iss, (New-Object System.Text.UTF8Encoding($false)))
New-Item -ItemType Directory -Force (Join-Path $Root 'dist') | Out-Null
& 'C:\Program Files (x86)\Inno Setup 6\ISCC.exe' (Join-Path $Root 'yongbongE.iss')
if ($LASTEXITCODE -ne 0) { throw "installer build failed" }
