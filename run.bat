@echo off
if not exist out mkdir out
javac -d out src\courtzone\*.java
if errorlevel 1 exit /b 1
java -cp out courtzone.App
