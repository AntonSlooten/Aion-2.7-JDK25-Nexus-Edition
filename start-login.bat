@echo off
title Aion Login Server

cd /d "%~dp0login-server"

java ^
-Xms256M ^
-Xmx512M ^
-jar target\login-server-1.0.0-SNAPSHOT.jar

pause