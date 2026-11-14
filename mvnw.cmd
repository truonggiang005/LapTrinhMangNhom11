@echo off
setlocal

set "MAVEN_CMD="
where mvn >nul 2>&1
if %ERRORLEVEL% equ 0 (
    set "MAVEN_CMD=mvn"
) else if exist "D:\App\IntelliJ IDEA 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd" (
    set "MAVEN_CMD=D:\App\IntelliJ IDEA 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd"
) else (
    for /d %%i in ("%PROGRAMFILES%\JetBrains\IntelliJ IDEA*") do (
        if exist "%%i\plugins\maven\lib\maven3\bin\mvn.cmd" (
            set "MAVEN_CMD=%%i\plugins\maven\lib\maven3\bin\mvn.cmd"
        )
    )
)

if "%MAVEN_CMD%"=="" (
    for /d %%i in ("D:\App\IntelliJ IDEA*") do (
        if exist "%%i\plugins\maven\lib\maven3\bin\mvn.cmd" (
            set "MAVEN_CMD=%%i\plugins\maven\lib\maven3\bin\mvn.cmd"
        )
    )
)

if "%MAVEN_CMD%"=="" (
    echo [ERROR] Maven not found! Please ensure Maven is installed.
    exit /b 1
)

call "%MAVEN_CMD%" %*
exit /b %ERRORLEVEL%
