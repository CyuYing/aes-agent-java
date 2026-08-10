#!/bin/sh
set -eu

# The official MySQL entrypoint has already created aes_agent and its account.
# Keep SQL homework execution isolated in a second database and a least-privilege user.
escaped_password=$(printf '%s' "$MYSQL_SANDBOX_PASSWORD" | sed "s/\\\\/\\\\\\\\/g; s/'/''/g")

MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot <<SQL
CREATE DATABASE IF NOT EXISTS aes_sql_sandbox
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'aes_sandbox'@'%' IDENTIFIED BY '$escaped_password';
ALTER USER 'aes_sandbox'@'%' IDENTIFIED BY '$escaped_password';
GRANT ALL PRIVILEGES ON aes_sql_sandbox.* TO 'aes_sandbox'@'%';
FLUSH PRIVILEGES;
SQL
