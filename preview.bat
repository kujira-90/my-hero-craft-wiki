@echo off
cd /d "%~dp0"
"C:\Users\tinti\.gradle\jdks\eclipse_adoptium-17-amd64-windows\jdk-17.0.20.1+1\bin\java.exe" generator\Serve.java docs
pause
