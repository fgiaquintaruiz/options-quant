# Dockerfile for Java Trading Engine
# Build: docker build -f Dockerfile.java -t options-quant-engine .

FROM eclipse-temurin:25-jdk-alpine AS builder

WORKDIR /build

# Copy build files
COPY build.gradle.kts settings.gradle.kts ./
COPY libs/ ./libs/

# Copy source code
COPY src/ ./src/
COPY protos/ ./protos/

# Build the application
RUN ./gradlew bootJar --no-daemon


FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

# Copy built artifact
COPY --from=builder /build/build/libs/*.jar app.jar

# Create data directory
RUN mkdir -p /app/data

# Expose ports
EXPOSE 8080 50051

# Set environment
ENV JAVA_OPTS="-Xmx2g -Xms512m -XX:+UseG1GC"

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]