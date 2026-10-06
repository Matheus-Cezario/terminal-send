# Server image. Build the jar first: ./gradlew :server:bootJar
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app

COPY --chown=app:app server/build/libs/server.jar app.jar

ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s \
    CMD wget -qO- http://localhost:8080/actuator/health >/dev/null || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
