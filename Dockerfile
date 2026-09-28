# Build stage always runs on the native builder platform (amd64 in CI)
# so Gradle never runs under slow QEMU emulation.
# Only the final runtime stage picks the target platform (arm64 for Pi).
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew dependencies --no-daemon -q 2>/dev/null || true

COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
COPY --from=build /workspace/build/libs/xlights-wled-integration.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
