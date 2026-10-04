# Runs the JNI vs FFM benchmark locally for the current OS/arch.
#
#   ./run-local.ps1                                              # all 2030 benchmarks
#   ./run-local.ps1 -Filter '.*DowncallJniBench\.(jni|ffm)_BP$'  # a single signature
#   ./run-local.ps1 -Filter '.*ffm_.*'                           # only the FFM side
#
# Requires JDK 25 on PATH. Results are written to out/.
param(
    [string]$Filter = '.*DowncallJniBench.*',
    [string]$Warmup = '30',
    [string]$WarmupTime = '100ms',
    [string]$Iterations = '50',
    [string]$MeasurementTime = '100ms',
    [string]$Forks = '1',
    [string]$Threads = '1'
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# --- platform / native jar ---
$isWin = $IsWindows -or ($env:OS -eq 'Windows_NT')
$isMac = [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform([System.Runtime.InteropServices.OSPlatform]::OSX)
$archRaw = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString().ToLower()   # x64 / arm64
$os = if ($isWin) { 'windows' } elseif ($isMac) { 'macos' } else { 'linux' }
$suffix = if ($archRaw -eq 'arm64') { '-arm64' } else { '' }
$native = "fjgl-0.3.0-natives-$os$suffix.jar"
$sep = if ($isWin) { ';' } else { ':' }
$tag = "$os-$archRaw"

$dist = Join-Path $PSScriptRoot 'dist'
if (-not (Test-Path (Join-Path $dist $native))) {
    throw "Native jar not found: dist/$native (available: $((Get-ChildItem $dist -Filter '*natives-*.jar').Name -join ', '))"
}

$jvmFlags = @(
    '--enable-native-access=ALL-UNNAMED',
    '--add-exports=java.base/jdk.internal.access=ALL-UNNAMED',
    '--add-exports=java.base/jdk.internal.foreign=ALL-UNNAMED',
    '--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED',
    '--add-exports=java.base/jdk.internal.util=ALL-UNNAMED'
)
$jvmFlagsStr = $jvmFlags -join ' '

New-Item -ItemType Directory -Force -Path 'out' | Out-Null
New-Item -ItemType Directory -Force -Path 'tools/classes' | Out-Null

Write-Host ">> compiling tools"
& javac -d tools/classes tools/EnvInfo.java tools/Flatten.java
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

$cp = "dist/fjgl-0.3.0.jar${sep}dist/$native${sep}libs/*"

Write-Host ">> environment"
& java -cp tools/classes EnvInfo "out/env-$tag.json"

Write-Host ">> JMH ($tag)"
& java @jvmFlags -cp $cp org.openjdk.jmh.Main $Filter `
    -wi $Warmup -w $WarmupTime -i $Iterations -r $MeasurementTime -f $Forks -t $Threads -bm avgt -tu ns `
    -rf json -rff "out/jmh-$tag.json" `
    -jvmArgsAppend $jvmFlagsStr
if ($LASTEXITCODE -ne 0) { throw 'JMH failed' }

Write-Host ">> flatten"
& java -cp tools/classes Flatten "out/env-$tag.json" "out/jmh-$tag.json" "out/results-$tag.csv" "out/calls-$tag.csv"

Write-Host "done: out/results-$tag.csv"
