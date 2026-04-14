# ── Stage 1: Build ──────────────────────────────────────────────────────────
# Build on Java 21 LTS — Lombok's annotation processor uses private javac internals
# (TypeTag, etc.) that changed in Java 25 and are not yet supported by any stable
# Lombok release. Java 21 bytecode is fully forward-compatible with the Java 25 JRE.
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /workspace

# Copy parent POM first (resolves the multi-module parent)
COPY pom.xml ./pom.xml

# Copy consumer module POM and source
COPY consumer-service/pom.xml consumer-service/pom.xml
COPY consumer-service/src    consumer-service/src

# Maven scans ALL modules listed in the parent POM during project loading.
# Provide the sibling module's POM (no source needed) so the scan doesn't fail.
COPY csv-producer-service/pom.xml csv-producer-service/pom.xml

# Build only the consumer module; -am also builds any parent POMs it depends on
RUN mvn -pl consumer-service -am package -DskipTests --no-transfer-progress

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

# Copy the fat jar produced by spring-boot-maven-plugin
COPY --from=builder /workspace/consumer-service/target/*.jar app.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]
