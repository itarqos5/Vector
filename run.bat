@echo off
setlocal
cd /d "%~dp0"
java --enable-native-access=ALL-UNNAMED -jar target\vector-1.0.0-SNAPSHOT.jar
pause
