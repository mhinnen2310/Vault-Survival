@echo off
setlocal

set "PROJECT_DIR=%~dp0"
set "WRAPPER=%PROJECT_DIR%mvnw.cmd"
set "FINAL_JAR=%PROJECT_DIR%target\VaultSurvival.jar"
set "TESTSERVER_PLUGINS=C:\Users\Mitchel\Desktop\MCServer\Vault-src\Vault Survival QWEN3 TESTSERVER\plugins"
set "DEPLOYED_JAR=%TESTSERVER_PLUGINS%\VaultSurvival.jar"

echo ============================================
echo   Vault Survival - Dev Build ^& Deploy
echo ============================================
echo Project: %PROJECT_DIR%
echo Test plugins: %TESTSERVER_PLUGINS%
echo.

cd /d "%PROJECT_DIR%"

if not exist "%WRAPPER%" (
    echo ERROR: Maven Wrapper not found:
    echo   %WRAPPER%
    echo.
    echo This project must build with mvnw.cmd, not global mvn.
    goto fail
)

if not exist "%TESTSERVER_PLUGINS%" (
    echo ERROR: Test server plugins folder not found:
    echo   %TESTSERVER_PLUGINS%
    goto fail
)

echo ============================================
echo   STEP 1: Clean ^& Build
echo ============================================
echo Running: "%WRAPPER%" clean package
echo.
call "%WRAPPER%" clean package
if errorlevel 1 (
    echo.
    echo ============================================
    echo   BUILD FAILED
    echo ============================================
    echo Maven output above contains the compile/build error.
    goto fail
)

echo.
echo ============================================
echo   STEP 2: Verify Final JAR
echo ============================================
if not exist "%FINAL_JAR%" (
    echo ERROR: Build finished but final JAR was not found:
    echo   %FINAL_JAR%
    goto fail
)
echo Found:
echo   %FINAL_JAR%
for %%A in ("%FINAL_JAR%") do echo Size: %%~zA bytes

echo.
echo ============================================
echo   STEP 3: Deploy To Test Server
echo ============================================
copy /Y "%FINAL_JAR%" "%DEPLOYED_JAR%"
if errorlevel 1 (
    echo ERROR: Could not copy JAR to:
    echo   %DEPLOYED_JAR%
    goto fail
)

echo.
echo ============================================
echo   BUILD ^& DEPLOY COMPLETE
echo ============================================
echo Deployed:
echo   %DEPLOYED_JAR%
for %%A in ("%DEPLOYED_JAR%") do echo Deployed size: %%~zA bytes
echo.
echo Restart/reload the test server to load the new JAR.
echo.
goto done

:fail
echo.
pause
exit /b 1

:done
pause
exit /b 0
