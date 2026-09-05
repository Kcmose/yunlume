FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN groupadd --gid 10001 nav \
    && useradd --uid 10001 --gid nav --no-create-home --home-dir /app \
        --shell /usr/sbin/nologin nav
RUN mkdir -p /app/uploads /app/logs /app/config \
    && chmod 0700 /app/config \
    && chown -R nav:nav /app
COPY --chown=nav:nav app.jar /app/app.jar
USER nav
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
