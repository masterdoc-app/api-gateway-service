# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY src ./src
RUN ./gradlew --no-daemon clean installDist -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/build/install/api-gateway-service /app
ENV PORT=8083
EXPOSE 8083 8084
CMD ["/app/bin/api-gateway-service"]
