FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN groupadd --gid 10001 nav \
    && useradd --uid 10001 --gid nav --no-create-home --home-dir /app \
        --shell /usr/sbin/nologin nav
RUN mkdir -p /app/uploads /app/logs /app/config \
    && chmod 0700 /app/config \
    && chown -R nav:nav /app
COPY --from=build /workspace/target/nav-backend-0.1.0.jar app.jar
USER nav
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
