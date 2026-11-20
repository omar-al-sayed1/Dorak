# syntax=docker/dockerfile:1
# The production image (M7). Built by GitHub Actions on every push to main and pushed to
# ghcr.io/omar-al-sayed1/dorak; the server only pulls it (deploy/). Builds locally too:
#   docker build -t dorak .

# --- Build: Maven builds the React app (it downloads its own Node) and the Spring Boot jar. ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw
# Dependencies first, in their own layer: rebuilt only when pom.xml changes.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY frontend frontend
COPY src src
# Tests run in CI before the image is built (./mvnw verify), not here.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -DskipTests package \
    && cp target/clinic-reminders-*.jar /app.jar

# --- Run: a JRE only, as an unprivileged user. ---
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --home-dir /app dorak
WORKDIR /app
COPY --from=build /app.jar app.jar
USER dorak
EXPOSE 8080
# Heap from the container's memory limit (deploy/compose.yaml); exit rather than limp on after an OOM,
# so Docker restarts it.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1
# Exec form: arguments after the image name reach the app, e.g. --create-operator=omar.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
