@echo off
rem MOD のソースから Wiki のページを作り直す
cd /d "%~dp0generator"
"C:\Users\tinti\.gradle\jdks\eclipse_adoptium-17-amd64-windows\jdk-17.0.20.1+1\bin\java.exe" -Dfile.encoding=UTF-8 WikiGen.java "D:\mods\1.20.1 - mha" "..\docs" "https://kujira-90.github.io/my-hero-craft-wiki"
pause
