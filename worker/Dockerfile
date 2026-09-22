# syntax=docker/dockerfile:1.7
# iam-worker image (ADR-0009, ADR-0010). Build context: repository root.
#   docker build -f worker/Dockerfile -t iam-worker:dev .
# The worker has no database access; it talks to RabbitMQ, the Core's internal mTLS API, and managed targets.

FROM eclipse-temurin:21-jdk-noble@sha256:4d271cd5e0624598cf563342f47281b09cb364bc13acbbd7251f49f83470018d AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY shared-kernel ./shared-kernel
COPY provider-spi ./provider-spi
COPY provider-spi-testkit ./provider-spi-testkit
COPY worker ./worker
COPY providers ./providers
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon :worker:bootJar -x test \
 && java -Djarmode=tools -jar worker/build/libs/iam-worker.jar extract --layers --launcher --destination /workspace/extracted

FROM eclipse-temurin:21-jre-noble@sha256:7739f0ffce786528961eea6bf46d9610ee968ac6127c9b2e93494757bdecce9f AS runtime
LABEL org.opencontainers.image.title="iam-worker" \
      org.opencontainers.image.description="Enterprise IAM / PAM provider worker" \
      org.opencontainers.image.vendor="Enterprise IAM"
RUN groupadd --system --gid 10001 iam && useradd --system --uid 10001 --gid iam --no-create-home --shell /usr/sbin/nologin iam
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/extracted/dependencies/ ./
COPY --from=build --chown=10001:10001 /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /workspace/extracted/application/ ./
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"
EXPOSE 8081
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
  CMD ["/bin/bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8081 && printf 'GET /actuator/health/liveness HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q '\"UP\"' <&3"]
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
