@echo off
REM Starts the chat server. Prompts for the keystore password unless CHAT_CERT_PASSWORD is already set.
cd /d "%~dp0"

if not exist "target\secure-tcp-chat.jar" (
    echo Chua co target\secure-tcp-chat.jar, dang build...
    call mvn -q package -DskipTests
    if errorlevel 1 goto :fail
)

if defined CHAT_CERT_PASSWORD goto :run_env

REM Read the password as a SecureString so it is not echoed, then hand it to java via the environment.
powershell -NoProfile -Command "$s = Read-Host 'Nhap mat khau keystore' -AsSecureString; $env:CHAT_CERT_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)); java --enable-native-access=ALL-UNNAMED -cp target/secure-tcp-chat.jar com.example.securechat.server.ServerMain"
goto :done

:run_env
java --enable-native-access=ALL-UNNAMED -cp target\secure-tcp-chat.jar com.example.securechat.server.ServerMain
goto :done

:fail
echo Build that bai.

:done
echo.
echo Server da dung.
pause
