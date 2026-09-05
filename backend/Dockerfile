FROM maven:3.9.11-eclipse-temurin-21-alpine AS build

WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw --batch-mode -DskipTests dependency:go-offline
COPY src src
RUN ./mvnw --batch-mode -DskipTests package

FROM eclipse-temurin:21-jre-alpine-3.24

RUN apk upgrade --no-cache \
    && apk add --no-cache curl \
    && addgroup -g 10001 app \
    && adduser -D -G app -u 10001 app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/target/lab-booking-api-0.1.0-SNAPSHOT.jar /app/app.jar

USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
  CMD curl --fail --silent http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
