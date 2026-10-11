FROM eclipse-temurin:17-jre

ARG MODULE
WORKDIR /app

# Build the reactor once on the host or in CI, then select the service artifact.
COPY ${MODULE}/target/${MODULE}-0.1.0-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
