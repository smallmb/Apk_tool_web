@echo off
setlocal EnableExtensions
chcp 65001 >nul

set "ACTION=%~1"
if "%ACTION%"=="" set "ACTION=start"
set "PROJECT_DIR=%~dp0..\..\"
for %%I in ("%PROJECT_DIR%") do set "PROJECT_DIR=%%~fI\"
set "WEB_DIR=%PROJECT_DIR%web"
set "LOG_DIR=%PROJECT_DIR%logs"

if not exist "%LOG_DIR%" mkdir "%LOG_DIR%" >nul 2>nul

if /I "%ACTION%"=="start" goto start_all
if /I "%ACTION%"=="api" goto start_api
if /I "%ACTION%"=="web" goto start_web
if /I "%ACTION%"=="stop" goto stop_all
echo Usage: %~nx0 [start^|api^|web^|stop]
exit /b 2

:start_all
call :check_java || exit /b 1
call :check_python || exit /b 1
call :check_port 8787 "Java API" || exit /b 1
call :check_port 5173 "Web UI" || exit /b 1
call :write_log "Starting Java API and Web UI"
start "Apktool Studio Java API" /D "%PROJECT_DIR%" cmd /d /k call "%PROJECT_DIR%scripts\windows\apktool-studio.bat" api
start "Apktool Studio Web UI" /D "%PROJECT_DIR%" cmd /d /k call "%PROJECT_DIR%scripts\windows\apktool-studio.bat" web
timeout /t 2 /nobreak >nul
echo Web UI:  http://127.0.0.1:5173/
echo Java API: http://127.0.0.1:8787/api/health
start "" "http://127.0.0.1:5173/"
exit /b 0

:start_api
call :check_java || exit /b 1
call :check_port 8787 "Java API" || exit /b 1
call :write_log "Starting Java API"
call "%PROJECT_DIR%gradlew.bat" :apktool-web:run
set "EXIT_CODE=%ERRORLEVEL%"
call :write_log "Java API exited with code %EXIT_CODE%"
exit /b %EXIT_CODE%

:start_web
call :check_python || exit /b 1
call :check_port 5173 "Web UI" || exit /b 1
call :write_log "Starting Web UI"
pushd "%WEB_DIR%"
python -m http.server 5173 --bind 127.0.0.1
set "EXIT_CODE=%ERRORLEVEL%"
popd
call :write_log "Web UI exited with code %EXIT_CODE%"
exit /b %EXIT_CODE%

:stop_all
call :write_log "Stopping services on ports 8787 and 5173"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ports=8787,5173; $pids=Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $ports -contains $_.LocalPort } | Select-Object -ExpandProperty OwningProcess -Unique; if ($pids) { $pids | ForEach-Object { Stop-Process -Id $_ -Force }; Write-Output ('Stopped process IDs: ' + ($pids -join ', ')) } else { Write-Output 'No Apktool Studio service is listening.' }"
exit /b %ERRORLEVEL%

:check_java
where java >nul 2>nul || (echo [ERROR] Java was not found in PATH.& exit /b 1)
where javac >nul 2>nul || (echo [ERROR] A JDK with javac is required to build the Java API.& exit /b 1)
exit /b 0

:check_python
where python >nul 2>nul || (echo [ERROR] Python was not found in PATH.& exit /b 1)
exit /b 0

:check_port
set "PORT=%~1"
set "SERVICE_NAME=%~2"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$c=Get-NetTCPConnection -LocalPort %PORT% -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1; if ($c) { $p=Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue; Write-Output ('[ERROR] %SERVICE_NAME% port %PORT% is already in use by PID ' + $c.OwningProcess + ' (' + $p.ProcessName + '). Run stop-apktool-studio.bat first.'); exit 1 }; exit 0"
exit /b %ERRORLEVEL%

:write_log
>> "%LOG_DIR%\launcher.log" echo [%date% %time%] %~1
exit /b 0
