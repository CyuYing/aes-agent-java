# syntax=docker/dockerfile:1.7

FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /workspace

# 先缓存 Maven 依赖，业务代码变化时无需重新下载全部依赖。
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests clean package && \
    JAR_FILE="$(find target -maxdepth 1 -name 'aes-agent-*.jar' ! -name '*.original' -print -quit)" && \
    test -n "$JAR_FILE" && \
    cp "$JAR_FILE" target/app.jar

FROM eclipse-temurin:17-jre-jammy

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl tini && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --system aes && useradd --system --gid aes --home-dir /app aes

WORKDIR /app
COPY --from=builder /workspace/target/app.jar /app/app.jar
COPY data /app/data
COPY deploy/docker-entrypoint.sh /app/docker-entrypoint.sh

RUN sed -i 's/\r$//' /app/docker-entrypoint.sh && \
    chmod 0755 /app/docker-entrypoint.sh && \
    mkdir -p /app/tmp && \
    chown -R aes:aes /app

USER aes

ENV SERVER_PORT=8080 \
    CHROMA_ENABLED=true \
    CHROMA_BASE_URL=http://chroma:8000 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8 -Djava.io.tmpdir=/app/tmp"

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
    CMD curl --fail --silent --show-error http://127.0.0.1:8080/api/health || exit 1

ENTRYPOINT ["/usr/bin/tini", "--", "/app/docker-entrypoint.sh"]
