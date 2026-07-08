@echo off
echo ============================================
echo   Vault Survival - Build
echo ============================================
echo.

cd /d "%~dp0"

echo Running Maven package...
call mvn package -DskipTests -q 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo FAILED: Build failed. Check output above for errors.
    exit /b 1
)

echo.
echo SUCCESS: VaultSurvival-1.0.0.jar built.
echo Location: %~dp0target\VaultSurvival-1.0.0.jar
exit /b 0
