@echo off
setlocal

set "PROJECT_DIR=%~dp0"
set "WRAPPER=%PROJECT_DIR%mvnw.cmd"
set "FINAL_JAR=%PROJECT_DIR%target\VaultSurvival.jar"

echo ============================================
echo   Vault Survival - Build
echo ============================================
echo Project: %PROJECT_DIR%
echo.

cd /d "%PROJECT_DIR%"

if not exist "%WRAPPER%" (
    echo ERROR: Maven Wrapper not found:
    echo   %WRAPPER%
    goto fail
)

echo Running: "%WRAPPER%" package
echo.
call "%WRAPPER%" package
if errorlevel 1 (
    echo.
    echo FAILED: Build failed. Check Maven output above.
    goto fail
)

if not exist "%FINAL_JAR%" (
    echo ERROR: Final JAR not found:
    echo   %FINAL_JAR%
    goto fail
)

echo.
echo SUCCESS: VaultSurvival.jar built.
echo Location: %FINAL_JAR%
goto done

:fail
pause
exit /b 1

:done
pause
exit /b 0
