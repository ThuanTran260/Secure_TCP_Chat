@echo off
REM Opens one chat GUI window. Double-click again for each extra account.
cd /d "%~dp0"

if not exist "target\secure-tcp-chat.jar" (
    echo Chua co target\secure-tcp-chat.jar, dang build...
    call mvn -q package -DskipTests
    if errorlevel 1 (
        echo Build that bai.
        pause
        exit /b 1
    )
)

java --enable-native-access=ALL-UNNAMED -jar target\secure-tcp-chat.jar
if errorlevel 1 pause
