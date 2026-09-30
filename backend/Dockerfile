# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Resolve dependencies in their own layer so source changes don't re-download them.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q dependency:go-offline

COPY src src
# Tests need Redis, so they run outside the image build (./mvnw test).
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q package -DskipTests \
    && cp target/tinylink-*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --no-create-home tinylink
USER tinylink

# One layer per Spring Boot layer: dependencies rarely change, application code often does.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
