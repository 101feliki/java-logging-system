# Builds one runnable module. Default: the central server.
#   docker build -t insurelog-server .
#   docker build --build-arg MODULE=demo-claims-service -t claims-service .
ARG MODULE=insurelog-server

FROM maven:3.9-eclipse-temurin-17 AS build
ARG MODULE
WORKDIR /src
COPY pom.xml .
COPY insurelog-core/pom.xml insurelog-core/
COPY insurelog-spring-boot-starter/pom.xml insurelog-spring-boot-starter/
COPY insurelog-server/pom.xml insurelog-server/
COPY demo-claims-service/pom.xml demo-claims-service/
RUN mvn -q -B dependency:go-offline -pl ${MODULE} -am || true
COPY . .
RUN mvn -q -B package -DskipTests -pl ${MODULE} -am && cp ${MODULE}/target/${MODULE}-*.jar /app.jar

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 app && mkdir -p /app/logs && chown app /app/logs
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
USER app
ENV LOG_DIR=/app/logs
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
