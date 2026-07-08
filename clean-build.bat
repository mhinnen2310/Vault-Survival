@echo off
echo ============================================
echo   Vault Survival - Clean Build
echo ============================================
echo.

cd /d "%~dp0"

echo [1/2] Cleaning...
call mvn clean -q 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo FAILED: Clean step failed.
    exit /b 1
)

echo [2/2] Packaging fresh JAR...
call mvn package -DskipTests -q 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo FAILED: Build failed.
    exit /b 1
)

echo.
echo SUCCESS: VaultSurvival-1.0.0.jar built clean.
echo Location: %~dp0target\VaultSurvival-1.0.0.jar
exit /b 0
