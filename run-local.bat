@echo off
rem Runs the JNI vs FFM benchmark locally on Windows x64.
rem
rem   run-local.bat                                                     (full run, configurable below)
rem   run-local.bat ".*DowncallJniBench\.(jni|ffm)_BP$" 2 100ms 2 200ms 1  (quick smoke test)
rem
rem Args: [filter] [warmupIterations] [warmupTime] [measurementIterations] [measurementTime] [forks]
rem Requires JDK 25 (java + javac on PATH). Results are written to out\.
setlocal enableextensions
pushd "%~dp0"

set "FILTER=%~1"
if "%FILTER%"=="" set "FILTER=.*DowncallJniBench.*"
set "WI=%~2"
if "%WI%"=="" set "WI=30"
set "WT=%~3"
if "%WT%"=="" set "WT=100ms"
set "MI=%~4"
if "%MI%"=="" set "MI=50"
set "MT=%~5"
if "%MT%"=="" set "MT=100ms"
set "FK=%~6"
if "%FK%"=="" set "FK=1"

set "JVMFLAGS=--enable-native-access=ALL-UNNAMED --add-exports=java.base/jdk.internal.access=ALL-UNNAMED --add-exports=java.base/jdk.internal.foreign=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.util=ALL-UNNAMED"

set "MAINJAR=dist\fjgl-0.3.0.jar"
set "NATIVE=dist\fjgl-0.3.0-natives-windows.jar"

if not exist "%MAINJAR%" ( echo [ERROR] Missing %MAINJAR% & goto :fail )
if not exist "%NATIVE%"  ( echo [ERROR] Missing %NATIVE%  & goto :fail )

echo === Java ===
java -version

echo === Compile tools ===
if not exist "tools\classes" mkdir "tools\classes"
javac -d "tools\classes" "tools\EnvInfo.java" "tools\Flatten.java"
if errorlevel 1 ( echo [ERROR] javac failed & goto :fail )

if not exist "out" mkdir "out"

echo === Environment ===
java -cp "tools\classes" EnvInfo "out\env-windows-x64.json"
if errorlevel 1 ( echo [ERROR] EnvInfo failed & goto :fail )

echo === JMH ===
echo filter=%FILTER%  warmup=%WI% x %WT%  measurement=%MI% x %MT%  forks=%FK%
java %JVMFLAGS% -cp "%MAINJAR%;%NATIVE%;libs\*" org.openjdk.jmh.Main ^
  "%FILTER%" ^
  -wi %WI% ^
  -i %MI% ^
  -w %WT% ^
  -r %MT% ^
  -f %FK% ^
  -t 1 ^
  -bm avgt ^
  -tu ns ^
  -rf json ^
  -rff "out\jmh-windows-x64.json" ^
  -jvmArgsAppend "%JVMFLAGS%"
if errorlevel 1 ( echo [ERROR] JMH failed & goto :fail )

echo === Flatten ===
java -cp "tools\classes" Flatten "out\env-windows-x64.json" "out\jmh-windows-x64.json" "out\results-windows-x64.csv" "out\calls-windows-x64.csv"
if errorlevel 1 ( echo [ERROR] Flatten failed & goto :fail )

echo.
echo Done. Outputs:
dir /b "out"
popd
endlocal
exit /b 0

:fail
echo.
echo FAILED.
popd
endlocal
exit /b 1
