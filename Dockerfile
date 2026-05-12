# ==============================================
# Multi-Chain Block Indexer - Multi-stage build
# ==============================================

# ------------------------------------------
# Stage 1: Build frontend
# ------------------------------------------
FROM node:26-alpine AS frontend-build

WORKDIR /app/frontend

COPY frontend/package.json frontend/package-lock.json* ./
RUN npm ci --ignore-scripts

COPY frontend/ ./

# Build outputs to ../src/main/resources/static (relative to frontend dir)
# We override outDir to keep it inside the frontend stage
RUN npx vite build --outDir /app/static

# ------------------------------------------
# Stage 2: Build backend
# ------------------------------------------
FROM maven:3-eclipse-temurin-25-alpine AS backend-build

WORKDIR /app

# Cache Maven dependencies
COPY pom.xml ./
RUN mvn dependency:go-offline -B

# Copy frontend build into Spring Boot static resources
COPY --from=frontend-build /app/static src/main/resources/static/

# Copy source code
COPY src/ src/

# Build JAR (skip tests - they run separately in CI)
RUN mvn package -DskipTests -B

# ------------------------------------------
# Stage 3: Runtime
# ------------------------------------------
FROM eclipse-temurin:25-jre-alpine AS runtime

RUN apk add --no-cache curl netcat-openbsd

# Create non-root user
RUN addgroup -S indexer && adduser -S indexer -G indexer

WORKDIR /app

# Copy JAR from build stage
COPY --from=backend-build /app/target/*.jar app.jar

# Copy entrypoint script
COPY docker/entrypoint.sh ./entrypoint.sh
RUN chmod +x entrypoint.sh

# Create output directory for Parquet files
RUN mkdir -p /app/output && chown indexer:indexer /app/output

# Switch to non-root user
USER indexer

EXPOSE 8080

# JVM options tuned for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseG1GC \
  -XX:+ExitOnOutOfMemoryError \
  -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -sf http://localhost:8080/api/indexer/health || exit 1

ENTRYPOINT ["./entrypoint.sh"]
