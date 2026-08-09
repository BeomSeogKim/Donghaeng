# The deployed artifact. Built and actually booted by CI (.github/workflows/ci.yml,
# job `docker`), because a Gradle build proves the code compiles and proves nothing
# about whether the packaged jar starts.
#
# Two things are deliberately absent and must stay absent:
#   - no credential, baked or defaulted. DATABASE_URL / DB_USERNAME / DB_PASSWORD
#     arrive from the runtime environment
#     (notes/2026-08-08-decision-scaffold-secrets-and-surface.md).
#   - no profile. SPRING_PROFILES_ACTIVE is supplied at run time; starting without
#     one is a hard failure by design (com.donghaeng.config.RequiredProfileMarker).

# --- build ------------------------------------------------------------------
# The wrapper, not a gradle:* image: gradle-wrapper.properties pins the
# distribution and its SHA-256, so building through it is the same Gradle the
# repo is tested with.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

COPY gradlew ./
COPY gradle ./gradle
RUN ./gradlew --version --no-daemon

COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
RUN ./gradlew bootJar --no-daemon

# --- runtime ----------------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --no-create-home --uid 10001 donghaeng
COPY --from=build /build/build/libs/*.jar app.jar
USER donghaeng

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
