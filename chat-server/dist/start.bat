@ECHO off
:: Run in Windows Terminal if available
IF "%WT_SESSION%" == "" wt %0 2>nul && EXIT
TITLE Aion Emu - Login Server

:START
CLS

JAVA -Xms512m -Xmx512m -DconsoleEncoding=CP850 -cp "libs/*" com.aionemu.chatserver.ChatServer

IF %ERRORLEVEL% EQU 0 GOTO END
IF %ERRORLEVEL% EQU 2 GOTO START

ECHO.
ECHO Login server has terminated abnormally!
ECHO.
PAUSE
EXIT

:END
ECHO.
ECHO Login server has shut down
ECHO.
PAUSE
EXIT