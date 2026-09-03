FROM maven:3.9.16-eclipse-temurin-25 AS build

WORKDIR /app

# Copy pom.xml and download dependencies (cached layer)
COPY pom.xml ./
RUN mvn dependency:go-offline -B

# Copy vendored jars (system-scoped dependencies)
COPY lib ./lib

# Copy source code
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests

FROM eclipse-temurin:25-jre-jammy

# Release version stamped by the CI build (see .github/workflows/release-deploy.yml);
# surfaced by the public /api/v1/status endpoint.
ARG APP_VERSION=dev
ENV APP_VERSION=${APP_VERSION}

WORKDIR /app

# Install wget (health checks) and LibreOffice headless (TOC refresh via UNO)
RUN apt-get update && \
    apt-get install -y --no-install-recommends wget libreoffice-writer && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Copy the built jar from build stage.
#
# Specifically the -boot jar. target/ holds two: the plain library jar that the enterprise
# overlay compiles against, and this repackaged runnable one. A bare *.jar glob picks the
# library jar and the container dies with "no main manifest attribute, in app.jar".
COPY --from=build /app/target/*-boot.jar app.jar

# Entrypoint script starts LibreOffice headless server, then the Spring app
COPY entrypoint.sh entrypoint.sh
RUN chmod +x entrypoint.sh

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=5 \
  CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["./entrypoint.sh"]
