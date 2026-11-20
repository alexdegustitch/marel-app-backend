# ---------- Build stage ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# Dependency layer first, so a source-only change does not re-download Maven Central.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -q dependency:go-offline

COPY src src
# Tests run in CI before the image is built; the image build itself only packages.
RUN ./mvnw -B -DskipTests package && cp target/*.jar /build/app.jar

# ---------- Runtime stage ----------
FROM eclipse-temurin:21-jre

# curl is only for the container/health checks in deploy.sh and docker-compose.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --uid 10001 --create-home app \
    && mkdir -p /data/uploads \
    && chown -R app:app /data

WORKDIR /app
COPY --from=build /build/app.jar app.jar

USER app
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
