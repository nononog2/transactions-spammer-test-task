FROM gradle:8.14.3-jdk21 AS builder

WORKDIR /workspace
COPY . .
RUN gradle --no-daemon :platform-core:bootJar

FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=builder /workspace/platform-core/build/libs/*.jar /app/platform-core.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/platform-core.jar"]
