#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$ROOT"

if ! command -v docker >/dev/null 2>&1; then
  echo "未检测到 Docker，请先安装并启动 Docker。" >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "Docker 尚未启动。" >&2
  exit 1
fi

if [ ! -f .env ]; then
  cp .env.example .env
  echo "已创建本机配置 .env。"
fi

set_env_value() {
  name=$1
  value=$2
  awk -v name="$name" -v value="$value" '
    BEGIN { found=0 }
    index($0, name "=") == 1 { print name "=" value; found=1; next }
    { print }
    END { if (!found) print name "=" value }
  ' .env > .env.tmp
  mv .env.tmp .env
}

new_hex_secret() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 32
  else
    od -An -N32 -tx1 /dev/urandom | tr -d ' \n'
  fi
}

for name in MYSQL_ROOT_PASSWORD MYSQL_PASSWORD MYSQL_SANDBOX_PASSWORD; do
  if ! grep -Eq "^${name}=.+$" .env; then
    set_env_value "$name" "$(new_hex_secret)"
  fi
done

if ! grep -Eq '^(GRADING_API_KEY|DEEPSEEK_API_KEY)=.+$' .env && [ -t 0 ]; then
  printf '批改模型 API Key（支持百炼或 DeepSeek，直接回车可稍后配置）: '
  stty -echo
  IFS= read -r API_KEY || true
  stty echo
  printf '\n'
  if [ -n "${API_KEY:-}" ]; then
    case "$API_KEY" in
      sk-ws-*)
        set_env_value GRADING_API_KEY "$API_KEY"
        set_env_value GRADING_BASE_URL 'https://dashscope.aliyuncs.com/compatible-mode/v1'
        set_env_value GRADING_MODEL 'qwen3.7-plus'
        set_env_value VISION_ENABLED 'true'
        set_env_value VISION_API_KEY "$API_KEY"
        set_env_value VISION_BASE_URL 'https://dashscope.aliyuncs.com/compatible-mode/v1'
        set_env_value VISION_MODEL 'qwen3.7-plus'
        ;;
      *) set_env_value DEEPSEEK_API_KEY "$API_KEY" ;;
    esac
  fi
fi

if grep -Eqi '^AES_SECURITY_ENABLED=true$' .env && ! grep -Eq '^AES_SECURITY_PASSWORD=.+$' .env; then
  if [ ! -t 0 ]; then
    echo "已启用访问鉴权，但 AES_SECURITY_PASSWORD 为空。" >&2
    exit 1
  fi
  printf '教师登录密码（不能为空）: '
  stty -echo
  IFS= read -r SECURITY_PASSWORD || true
  stty echo
  printf '\n'
  if [ -z "${SECURITY_PASSWORD:-}" ]; then
    echo "教师登录密码不能为空。" >&2
    exit 1
  fi
  awk -v password="$SECURITY_PASSWORD" '
    BEGIN { found=0 }
    /^AES_SECURITY_PASSWORD=/ { print "AES_SECURITY_PASSWORD=" password; found=1; next }
    { print }
    END { if (!found) print "AES_SECURITY_PASSWORD=" password }
  ' .env > .env.tmp
  mv .env.tmp .env
fi

echo "正在构建并启动应用、MySQL 与 Chroma……"
docker compose -f compose.yaml up -d --build

APP_PORT=$(awk -F= '/^APP_PORT=/{print $2}' .env | tail -n 1)
APP_PORT=${APP_PORT:-8080}
URL="http://127.0.0.1:${APP_PORT}/api/health"

i=0
until [ "$i" -ge 120 ]; do
  if command -v curl >/dev/null 2>&1 && curl -fsS "$URL" >/dev/null 2>&1; then
    echo "部署完成：http://localhost:${APP_PORT}"
    exit 0
  fi
  i=$((i + 1))
  sleep 1
done

docker compose -f compose.yaml logs app --tail 100
echo "服务未能在预期时间内通过健康检查。" >&2
exit 1
