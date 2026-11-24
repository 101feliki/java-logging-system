# Build stage
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# Run stage
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 insurelog && mkdir -p /app/logs && chown insurelog /app/logs
COPY --from=build /src/target/insurance-logging-system-1.0.0.jar /app/insurance-logging-system.jar
USER insurelog
ENV PORT=8080 LOG_DIR=/app/logs
EXPOSE 8080
VOLUME ["/app/logs"]
ENTRYPOINT ["java", "-jar", "/app/insurance-logging-system.jar", "serve"]
