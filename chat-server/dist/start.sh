#!/bin/bash
#=====================================================================================
# Usage:        ./start.sh [jvmArgs] [--debug]
# Parameters:   jvmArgs
#                   additional arguments to the JVM process starting the server
#               --debug
#                   Start a remote debug session
# Description:  Starts the chat server
#=====================================================================================
jvm_args=()

for arg in "$@"; do
    case "$arg" in
        --debug)
            jvm_args+=("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=localhost:5007")
            ;;
        *)
            jvm_args+=("$arg")
            ;;
    esac
done

exec java \
    -Xms512m \
    -Xmx512m \
    -DconsoleEncoding=CP850 \
    "${jvm_args[@]}" \
    -cp "libs/*" \
    com.aionemu.chatserver.ChatServer