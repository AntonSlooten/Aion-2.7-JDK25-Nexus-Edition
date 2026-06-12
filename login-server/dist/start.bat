@ECHO off
:: Memaksa Windows Terminal untuk buka di folder ini (-d .)
IF "%WT_SESSION%" == "" wt -d . %0 2>nul && EXIT
TITLE Aion Veylora - Login Server

:START
:: Memaksa CMD untuk pindah ke lokasi asli file .bat ini berada
cd /d "%~dp0"
CLS

JAVA -Xms512m -Xmx512m ^
 --add-opens java.base/java.lang=ALL-UNNAMED ^
 --add-opens java.base/java.lang.reflect=ALL-UNNAMED ^
 --add-opens java.base/java.util=ALL-UNNAMED ^
 --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED ^
 --add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED ^
 --add-exports jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED ^
 --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED ^
 -XX:+UseNUMA -XX:+UseCompactObjectHeaders -DconsoleEncoding=CP850 -cp "libs/*" com.aionemu.loginserver.LoginServer

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