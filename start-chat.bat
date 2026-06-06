@echo off
title Aion Chat Server

cd /d "%~dp0chat-server"

java ^
-Xms256M ^
-Xmx512M ^
-Dlogback.configurationFile=config/slf4j-logback.xml ^
-jar target\chat-server-1.0.0-SNAPSHOT.jar

pause