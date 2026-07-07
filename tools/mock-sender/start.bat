@echo off
chcp 936 >nul
title Smart Streetlight - Mock Sender

cd /d "%~dp0"

python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Python not found, please install Python 3.8+
    pause
    exit /b 1
)

echo [1/2] Checking dependencies...
python -c "import flask, paho.mqtt" 2>nul
if %errorlevel% neq 0 (
    echo     - Installing dependencies...
    pip install -r requirements.txt
    if %errorlevel% neq 0 (
        echo [ERROR] pip install failed
        pause
        exit /b 1
    )
    echo     - Dependencies installed
) else (
    echo     - Dependencies ready
)

echo.
echo [2/2] Starting server...
echo.
echo    Web UI: http://localhost:5050
echo.
echo ============================================
echo  Console stays open for live log output
echo  Press Ctrl+C to stop the server
echo ============================================
echo.

start /B cmd /c "timeout /t 2 /nobreak >nul && start http://localhost:5050"

python app.py

echo.
echo ============================================
echo  Server stopped. Close this window.
echo ============================================
pause
