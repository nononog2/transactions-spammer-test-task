FROM gradle:8.14.3-jdk21 AS builder

WORKDIR /workspace
COPY . .
RUN gradle --no-daemon :platform-core:bootJar

FROM eclipse-temurin:21-jre

# curl is needed for the docker-compose healthcheck probe.
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=builder /workspace/platform-core/build/libs/*.jar /app/platform-core.jar

EXPOSE 8080

ENTRYPOINT ["java", \
  "-server", \
  "-XX:+UseG1GC", \
  "-XX:MaxGCPauseMillis=50", \
  "-Xms512m", \
  "-Xmx2g", \
  "-jar", "/app/platform-core.jar"]
