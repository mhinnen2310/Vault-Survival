@echo off
setlocal

set "PROJECT_DIR=%~dp0"
set "WRAPPER=%PROJECT_DIR%mvnw.cmd"
set "FINAL_JAR=%PROJECT_DIR%target\VaultSurvival.jar"

echo ============================================
echo   Vault Survival - Clean Build
echo ============================================
echo Project: %PROJECT_DIR%
echo.

cd /d "%PROJECT_DIR%"

if not exist "%WRAPPER%" (
    echo ERROR: Maven Wrapper not found:
    echo   %WRAPPER%
    echo.
    echo This project must build with mvnw.cmd, not global mvn.
    goto fail
)

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

if not exist "%FINAL_JAR%" (
    echo.
    echo ERROR: Build finished but final JAR was not found:
    echo   %FINAL_JAR%
    goto fail
)

echo.
echo ============================================
echo   BUILD SUCCESS
echo ============================================
echo Final JAR:
echo   %FINAL_JAR%
for %%A in ("%FINAL_JAR%") do echo Size: %%~zA bytes
echo.
goto done

:fail
echo.
pause
exit /b 1

:done
pause
exit /b 0
