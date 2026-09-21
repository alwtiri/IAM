# syntax=docker/dockerfile:1.7
# iam-core image (ADR-0009). Build context: repository root.
#   docker build -f core/Dockerfile -t iam-core:dev .

FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY shared-kernel ./shared-kernel
COPY provider-spi ./provider-spi
COPY provider-spi-testkit ./provider-spi-testkit
COPY core ./core
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon :core:bootJar -x test \
 && java -Djarmode=tools -jar core/build/libs/iam-core.jar extract --layers --launcher --destination /workspace/extracted

FROM eclipse-temurin:21-jre-noble AS runtime
LABEL org.opencontainers.image.title="iam-core" \
      org.opencontainers.image.description="Enterprise IAM / PAM governance Core" \
      org.opencontainers.image.vendor="Enterprise IAM"
RUN groupadd --system --gid 10001 iam && useradd --system --uid 10001 --gid iam --no-create-home --shell /usr/sbin/nologin iam
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/extracted/dependencies/ ./
COPY --from=build --chown=10001:10001 /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /workspace/extracted/application/ ./
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
  CMD ["/bin/bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health/liveness HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q '\"UP\"' <&3"]
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
