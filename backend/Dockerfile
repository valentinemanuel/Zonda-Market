FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY --chmod=0755 mvnw ./
COPY pom.xml ./
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw --batch-mode --no-transfer-progress verify

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system zonda \
    && useradd --system --gid zonda zonda
WORKDIR /app
COPY --from=build /workspace/target/zonda-market.jar app.jar
USER zonda
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
