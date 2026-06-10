#!/bin/bash
#=====================================================================================
# Usage:        ./start.sh [jvmArgs]
# Parameters:   jvmArgs
#                   additional arguments to the JVM process starting the server
# Description:  Starts the server and restarts it depending on returned exit code.
#=====================================================================================

java -Xms512m -Xmx512m -DconsoleEncoding=CP850 -cp "libs/*" com.aionemu.loginserver.LoginServer