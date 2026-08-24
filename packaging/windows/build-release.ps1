param(
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [Parameter(Mandatory = $true)]
    [string]$PythonHome
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
Set-StrictMode -Version Latest

$repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$target = Join-Path $repo "target"
$cache = Join-Path $target "package-cache"
$input = Join-Path $target "package-input"
$images = Join-Path $target "package-image"
$release = Join-Path $target "release"
$appName = "BaiChenRecorder"

foreach ($path in @($input, $images, $release)) {
    if (Test-Path -LiteralPath $path) {
        Remove-Item -LiteralPath $path -Recurse -Force
    }
    New-Item -ItemType Directory -Path $path | Out-Null
}
New-Item -ItemType Directory -Force -Path $cache | Out-Null

function Get-VerifiedDownload {
    param(
        [string]$Url,
        [string]$Destination,
        [string]$Sha256
    )
    if (-not (Test-Path -LiteralPath $Destination)) {
        Invoke-WebRequest -Uri $Url -OutFile $Destination
    }
    $actual = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $Sha256.ToLowerInvariant()) {
        throw "Checksum mismatch for $Destination"
    }
}

$ffmpegZip = Join-Path $cache "ffmpeg-8.1.2-essentials_build.zip"
$ffmpegChecksum = Join-Path $cache "ffmpeg-8.1.2-essentials_build.zip.sha256"
if (-not (Test-Path -LiteralPath $ffmpegChecksum)) {
    Invoke-WebRequest `
        -Uri "https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-8.1.2-essentials_build.zip.sha256" `
        -OutFile $ffmpegChecksum
}
$checksumText = Get-Content -LiteralPath $ffmpegChecksum -Raw
$checksumMatch = [regex]::Match($checksumText, "(?i)[0-9a-f]{64}")
if (-not $checksumMatch.Success) {
    throw "The FFmpeg checksum file did not contain a SHA-256 value"
}
Get-VerifiedDownload `
    -Url "https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-8.1.2-essentials_build.zip" `
    -Destination $ffmpegZip `
    -Sha256 $checksumMatch.Value

$vlcZip = Join-Path $cache "vlc-3.0.21-win64.zip"
Get-VerifiedDownload `
    -Url "https://mirror.csclub.uwaterloo.ca/vlc/vlc/3.0.21/win64/vlc-3.0.21-win64.zip" `
    -Destination $vlcZip `
    -Sha256 "a0b7ec02b50adf6417eed014fb8df50af39690505a4225b85b3dc2ed17d14843"

$expanded = Join-Path $target "package-expanded"
if (Test-Path -LiteralPath $expanded) {
    Remove-Item -LiteralPath $expanded -Recurse -Force
}
New-Item -ItemType Directory -Path $expanded | Out-Null
Expand-Archive -LiteralPath $ffmpegZip -DestinationPath (Join-Path $expanded "ffmpeg")
Expand-Archive -LiteralPath $vlcZip -DestinationPath (Join-Path $expanded "vlc")

$tools = Join-Path $input "tools"
$ffmpegTarget = Join-Path $tools "ffmpeg"
$vlcTarget = Join-Path $tools "vlc"
$pythonTarget = Join-Path $tools "python"
New-Item -ItemType Directory -Force -Path $ffmpegTarget, $vlcTarget, $pythonTarget | Out-Null
Copy-Item -Path (Join-Path $expanded "ffmpeg\ffmpeg-8.1.2-essentials_build\*") -Destination $ffmpegTarget -Recurse
Copy-Item -Path (Join-Path $expanded "vlc\vlc-3.0.21\*") -Destination $vlcTarget -Recurse
Copy-Item -Path (Join-Path (Resolve-Path $PythonHome).Path "*") -Destination $pythonTarget -Recurse

Copy-Item -LiteralPath (Join-Path $target "bili-record.jar") -Destination $input
Copy-Item -LiteralPath (Join-Path $repo "README.md") -Destination $input
Copy-Item -LiteralPath (Join-Path $repo "README_CN.md") -Destination $input
Copy-Item -LiteralPath (Join-Path $repo "THIRD_PARTY_NOTICES.md") -Destination $input

$icon = Join-Path $target "BaiChenRecorder.ico"
Add-Type -AssemblyName System.Drawing
$bitmap = [System.Drawing.Bitmap]::new(256, 256)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$graphics.Clear([System.Drawing.Color]::FromArgb(251, 114, 153))
$font = [System.Drawing.Font]::new("Arial", 150, [System.Drawing.FontStyle]::Bold)
$format = [System.Drawing.StringFormat]::new()
$format.Alignment = [System.Drawing.StringAlignment]::Center
$format.LineAlignment = [System.Drawing.StringAlignment]::Center
$graphics.DrawString("B", $font, [System.Drawing.Brushes]::White, [System.Drawing.RectangleF]::new(0, 0, 256, 250), $format)
$png = [System.IO.MemoryStream]::new()
$bitmap.Save($png, [System.Drawing.Imaging.ImageFormat]::Png)
$pngBytes = $png.ToArray()
$file = [System.IO.File]::Create($icon)
$writer = [System.IO.BinaryWriter]::new($file)
$writer.Write([uint16]0); $writer.Write([uint16]1); $writer.Write([uint16]1)
$writer.Write([byte]0); $writer.Write([byte]0); $writer.Write([byte]0); $writer.Write([byte]0)
$writer.Write([uint16]1); $writer.Write([uint16]32)
$writer.Write([uint32]$pngBytes.Length); $writer.Write([uint32]22); $writer.Write($pngBytes)
$writer.Dispose(); $graphics.Dispose(); $bitmap.Dispose(); $font.Dispose(); $format.Dispose(); $png.Dispose()

$common = @(
    "--name", $appName,
    "--app-version", $Version,
    "--vendor", "FYFYBai",
    "--description", "Bilibili live recorder and review tool",
    "--icon", $icon,
    "--input", $input,
    "--main-jar", "bili-record.jar",
    "--main-class", "io.github.fyfybai.bilirecord.Main",
    "--java-options", "-Dfile.encoding=UTF-8"
)

& jpackage --type app-image --dest $images @common
if ($LASTEXITCODE -ne 0) { throw "jpackage app-image failed" }

$app = Join-Path $images $appName
& (Join-Path $app "app\tools\ffmpeg\bin\ffmpeg.exe") -version | Select-Object -First 1
if ($LASTEXITCODE -ne 0) { throw "Bundled FFmpeg smoke test failed" }
& (Join-Path $app "app\tools\ffmpeg\bin\ffprobe.exe") -version | Select-Object -First 1
if ($LASTEXITCODE -ne 0) { throw "Bundled FFprobe smoke test failed" }
if (-not (Test-Path -LiteralPath (Join-Path $app "app\tools\vlc\libvlc.dll")) -or
        -not (Test-Path -LiteralPath (Join-Path $app "app\tools\vlc\plugins"))) {
    throw "Bundled VLC runtime is incomplete"
}
& (Join-Path $app "app\tools\python\python.exe") -c "import faster_whisper; print('faster-whisper ready')"
if ($LASTEXITCODE -ne 0) { throw "Bundled transcription runtime smoke test failed" }

$installerScript = Join-Path $target "BaiChenRecorder.iss"
$installerSource = $app
$installerOutput = $release
$installerIcon = $icon
@"
[Setup]
AppId=FYFYBai.BaiChenRecorder
AppName=白沉的录播小工具
AppVersion=$Version
AppPublisher=FYFYBai
DefaultDirName={localappdata}\Programs\BaiChenRecorder
DefaultGroupName=白沉的录播小工具
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=$installerOutput
OutputBaseFilename=BaiChenRecorder-$Version-windows-x64
SetupIconFile=$installerIcon
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\BaiChenRecorder.exe

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; Flags: checkedonce

[Files]
Source: "$installerSource\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\白沉的录播小工具"; Filename: "{app}\BaiChenRecorder.exe"
Name: "{autodesktop}\白沉的录播小工具"; Filename: "{app}\BaiChenRecorder.exe"; Tasks: desktopicon
"@ | Set-Content -LiteralPath $installerScript -Encoding utf8BOM

$iscc = "C:\Program Files (x86)\Inno Setup 6\ISCC.exe"
if (-not (Test-Path -LiteralPath $iscc)) {
    throw "Inno Setup compiler was not found"
}
& $iscc $installerScript
if ($LASTEXITCODE -ne 0) { throw "Inno Setup EXE build failed" }

$installer = Get-ChildItem -LiteralPath $release -Filter "*.exe" | Select-Object -First 1
$hash = Get-FileHash -LiteralPath $installer.FullName -Algorithm SHA256
"$($hash.Hash.ToLowerInvariant())  $($installer.Name)" | Set-Content `
    -LiteralPath (Join-Path $release "$($installer.Name).sha256") -Encoding ascii
