#!/bin/sh
# Builds Vector and runs the end-to-end suite against it.
set -e
cd "$(dirname "$0")/../.."
mvn -q -B package -DskipTests
java -Dsun.net.http.allowRestrictedHeaders=true tests/e2e/Harness.java \
  "${TMPDIR:-/tmp}/vector-e2e" target/vector-*-SNAPSHOT.jar
