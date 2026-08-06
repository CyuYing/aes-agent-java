#!/bin/sh
set -eu

# JAVA_OPTS 由部署者控制，需要按空格展开为多个 JVM 参数。
# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} -jar /app/app.jar "$@"
