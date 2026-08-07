# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY backend/pom.xml .
RUN mvn dependency:go-offline -B

COPY backend/src ./src
RUN mvn package -DskipTests -B

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-jammy

# Install Node.js 20 (required to run Playwright tests via npx)
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl \
    && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y nodejs \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# Install Playwright browsers (chromium) so generated tests can run
RUN npm install -g playwright@1.48.0 \
    && npx playwright install chromium \
    && npx playwright install-deps chromium \
    && rm -rf /root/.npm

WORKDIR /app

COPY --from=build /build/target/*.jar app.jar

ENV QALAB_AUTO_START_FRONTEND=false \
    QALAB_AUTO_OPEN_BROWSER=false \
    QALAB_WORKSPACES_DIR=/app/workspaces \
    QALAB_SCREENSHOTS_DIR=/app/screenshots

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
