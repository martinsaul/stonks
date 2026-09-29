# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
RUN ./gradlew --version > /dev/null
COPY engine engine
COPY cli cli
COPY app app
RUN ./gradlew :app:installDist --no-daemon -q

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 stonks
WORKDIR /opt/stonks
COPY --from=build /src/app/build/install/app ./
USER stonks
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=180s \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /healthz HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q '200' <&3"]
ENTRYPOINT ["./bin/app"]
