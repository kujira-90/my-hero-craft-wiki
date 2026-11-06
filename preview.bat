@echo off
rem Wiki をPCで見る(3Dモデルも表示される)。閉じる時はこの黒い画面を閉じる
start "" http://127.0.0.1:8765/
"C:\Users\tinti\.gradle\jdks\eclipse_adoptium-17-amd64-windows\jdk-17.0.20.1+1\bin\java.exe" "%~dp0generator\Serve.java" "%~dp0docs"
