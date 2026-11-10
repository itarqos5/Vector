#!/bin/sh
# Starts Vector from the repository root after "mvn clean package" (Linux/macOS counterpart of run.bat).
cd "$(dirname "$0")" || exit 1
exec java --enable-native-access=ALL-UNNAMED -XX:+UseG1GC -XX:MaxGCPauseMillis=30 \
  -jar target/vector-1.0.0-SNAPSHOT.jar --attached-console "$@"
