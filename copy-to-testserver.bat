@echo off
setlocal

set "PROJECT_DIR=%~dp0"
set "FINAL_JAR=%PROJECT_DIR%target\VaultSurvival.jar"
set "TESTSERVER_PLUGINS=C:\Users\Mitchel\Desktop\MCServer\Vault-src\Vault Survival QWEN3 TESTSERVER\plugins"
set "DEPLOYED_JAR=%TESTSERVER_PLUGINS%\VaultSurvival.jar"

echo ============================================
echo   Vault Survival - Copy to Test Server
echo ============================================
echo.

if not exist "%FINAL_JAR%" (
    echo ERROR: JAR not found:
    echo   %FINAL_JAR%
    echo Run clean-build.bat or dev-build-deploy.bat first.
    goto fail
)

if not exist "%TESTSERVER_PLUGINS%" (
    echo ERROR: Test server plugins folder not found:
    echo   %TESTSERVER_PLUGINS%
    goto fail
)

echo Copying:
echo   %FINAL_JAR%
echo To:
echo   %DEPLOYED_JAR%
echo.
copy /Y "%FINAL_JAR%" "%DEPLOYED_JAR%"
if errorlevel 1 (
    echo FAILED: Could not copy JAR.
    goto fail
)

echo.
echo SUCCESS: JAR deployed.
for %%A in ("%DEPLOYED_JAR%") do echo Deployed size: %%~zA bytes
goto done

:fail
pause
exit /b 1

:done
pause
exit /b 0
