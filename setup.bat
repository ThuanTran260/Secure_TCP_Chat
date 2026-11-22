@echo off
REM One-time setup after cloning: generates the TLS certificates and builds the jar.
REM The keystore password is chosen here and is not stored anywhere; remember it for run-server.bat.
cd /d "%~dp0"

if not exist "certificates\server\server.p12" goto :ask_password

echo Da co certificates\server\server.p12. Tao lai se GHI DE chung chi cu va ban phai nhap lai mat khau moi.
set /p CONFIRM="Tao lai chung chi? (y/N): "
if /i not "%CONFIRM%"=="y" goto :build

:ask_password
echo.
echo Dat mat khau keystore (it nhat 6 ky tu, khi nhap se khong hien ky tu). Hay nho mat khau nay.
powershell -NoProfile -ExecutionPolicy Bypass -Command "$s = Read-Host 'Mat khau keystore' -AsSecureString; $env:CHAT_CERT_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)); & ./scripts/gen-certs.ps1"
if errorlevel 1 goto :fail

:build
echo.
echo Dang build jar...
call mvn -q package -DskipTests
if errorlevel 1 goto :fail

echo.
echo Xong. Bay gio bam dup run-server.bat, sau do run-gui.bat.
goto :done

:fail
echo.
echo Setup that bai, xem thong bao loi o tren.

:done
pause
