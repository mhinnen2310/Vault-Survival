@echo off
echo ============================================
echo   Vault Survival - Dev Build ^& Deploy
echo ============================================
echo.

cd /d "%~dp0"

set JAR=target\VaultSurvival-1.0.0.jar
set PLUGINS=..\Vault Survival QWEN3 TESTSERVER\plugins

echo ============================================
echo   STEP 1: Clean ^& Build
echo ============================================
call mvn clean package -DskipTests -q 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ============================================
    echo   BUILD FAILED
    echo ============================================
    exit /b 1
)

echo ============================================
echo   STEP 2: Verify JAR
echo ============================================
if not exist "%JAR%" (
    echo ERROR: JAR not found after build!
    echo Expected: %JAR%
    exit /b 1
)
echo JAR found: VaultSurvival-1.0.0.jar
for %%A in ("%JAR%") do echo Size: %%~zA bytes

echo ============================================
echo   STEP 3: Deploy to Test Server
echo ============================================
copy /y "%JAR%" "%PLUGINS%\VaultSurvival-1.0.0.jar" >nul
if %ERRORLEVEL% EQU 0 (
    echo SUCCESS: JAR deployed to test server.
    echo   %PLUGINS%\VaultSurvival-1.0.0.jar
) else (
    echo ERROR: Could not copy JAR. Check path:
    echo   %PLUGINS%
    exit /b 1
)

echo.
echo ============================================
echo   BUILD ^& DEPLOY COMPLETE
echo ============================================
echo   JAR: target\VaultSurvival-1.0.0.jar
echo   DEPLOYED: %PLUGINS%\VaultSurvival-1.0.0.jar
echo.
echo   Start the test server to verify.
echo ============================================

exit /b 0
