@echo off
echo ============================================
echo   Vault Survival - Deploy to Test Server
echo ============================================
echo.

cd /d "%~dp0"

set JAR=target\VaultSurvival.jar
set PLUGINS=..\Vault Survival QWEN3 TESTSERVER\plugins

if not exist "%JAR%" (
    echo ERROR: JAR not found at %JAR%
    echo Run build.bat first.
    exit /b 1
)

if not exist "%PLUGINS%" (
    echo ERROR: Test server plugins folder not found at %PLUGINS%
    exit /b 1
)

echo Copying VaultSurvival.jar to test server...
copy /y "%JAR%" "%PLUGINS%\VaultSurvival.jar" >nul

if %ERRORLEVEL% EQU 0 (
    echo SUCCESS: JAR deployed to:
    echo   %PLUGINS%\VaultSurvival.jar
) else (
    echo FAILED: Could not copy JAR.
    exit /b 1
)

exit /b 0
