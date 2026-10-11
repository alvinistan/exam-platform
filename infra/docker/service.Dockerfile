# syntax=docker/dockerfile:1.7
# One Dockerfile for the gateway and every service; MODULE picks which one (e.g. services/catalog-service).
# Build from the repository root:
#   docker build -f infra/docker/service.Dockerfile --build-arg MODULE=services/catalog-service -t catalog-service .

FROM maven:3.9.11-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /src
COPY backend/ ./
RUN --mount=type=cache,target=/root/.m2 \
    test -n "$MODULE" && mvn -B -ntp -q -pl "$MODULE" -am -DskipTests package \
    && cp "$MODULE/target/$(basename "$MODULE").jar" /app.jar

FROM eclipse-temurin:21-jre-alpine
ARG MODULE
LABEL org.opencontainers.image.source="https://github.com/alvinistan/exam-platform" \
      org.opencontainers.image.title="$MODULE"
RUN addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app app
WORKDIR /app
COPY --from=build --chown=10001:10001 /app.jar /app/app.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    SERVER_PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
    CMD wget -qO- "http://127.0.0.1:${SERVER_PORT}/actuator/health/readiness" | grep -q '"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
