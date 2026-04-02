FROM gradle:8.14.3-jdk21 AS builder

WORKDIR /workspace
COPY . .
RUN gradle --no-daemon :transaction-generator:installDist

FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=builder /workspace/transaction-generator/build/install/transaction-generator /app/transaction-generator

ENTRYPOINT ["/app/transaction-generator/bin/transaction-generator"]
