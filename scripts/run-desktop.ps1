$ErrorActionPreference = "Stop"

$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$source = Join-Path $repo "target\bili-record.jar"
if (-not (Test-Path -LiteralPath $source)) {
    throw "Build the application first with: mvn clean package"
}

$runtime = Join-Path $repo "target\runtime"
New-Item -ItemType Directory -Force -Path $runtime | Out-Null
$copy = Join-Path $runtime ("bili-record-{0}.jar" -f (Get-Date -Format "yyyyMMdd-HHmmssfff"))
Copy-Item -LiteralPath $source -Destination $copy

$javaw = (Get-Command javaw -ErrorAction Stop).Source
$process = Start-Process `
    -FilePath $javaw `
    -ArgumentList @("-jar", $copy) `
    -WorkingDirectory $repo `
    -WindowStyle Hidden `
    -PassThru

Write-Output "Started desktop process $($process.Id) from $copy"
