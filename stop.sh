#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$ROOT"
docker compose -f compose.yaml down
echo "服务已停止，MySQL 业务数据与知识库索引均已保留。"
