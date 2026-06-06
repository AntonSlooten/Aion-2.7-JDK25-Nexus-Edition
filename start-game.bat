@echo off
title Aion Game Server

cd /d "%~dp0game-server"

java ^
-Xms2G ^
-Xmx4G ^
--add-opens java.base/java.lang=ALL-UNNAMED ^
-javaagent:target\game-server-1.0.0-SNAPSHOT.jar ^
-jar target\game-server-1.0.0-SNAPSHOT.jar

pause