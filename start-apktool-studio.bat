@echo off
setlocal
rem Use UTF-8 console output for Gradle and JDK messages.
chcp 65001 >nul
rem Resolve the project root from the batch file location.
set "PROJECT_DIR=%~dp0"
set "WEB_DIR=%PROJECT_DIR%web"
set "CLI_JAR=%PROJECT_DIR%brut.apktool\apktool-cli\build\libs\apktool-cli.jar"
rem Check required runtime dependencies before starting services.
where java >nul 2>nul
if errorlevel 1 (echo [ERROR] Java is not available.& pause& exit /b 1)
where python >nul 2>nul
if errorlevel 1 (echo [ERROR] Python is not available.& pause& exit /b 1)
if not exist "%CLI_JAR%" (echo [ERROR] Apktool CLI JAR is missing.& pause& exit /b 1)
rem Each child console also uses UTF-8.
start "Apktool Studio Java API" /D "%PROJECT_DIR%" cmd /k "chcp 65001 >nul & gradlew.bat :apktool-web:run"
rem The static web server also uses UTF-8.
start "Apktool Studio Web UI" /D "%WEB_DIR%" cmd /k "chcp 65001 >nul & python -m http.server 5173 --bind 127.0.0.1"
timeout /t 2 /nobreak >nul
echo Apktool Studio Web UI: http://127.0.0.1:5173/
echo Apktool Studio API:    http://127.0.0.1:8787/api/health
start "" "http://127.0.0.1:5173/"
endlocal
