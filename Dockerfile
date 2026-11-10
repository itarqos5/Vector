# syntax=docker/dockerfile:1

# ---- build ---------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-22 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -ntp -q dependency:go-offline
COPY src ./src
RUN mvn -B -ntp -q package -DskipTests

# ---- runtime -------------------------------------------------------------
FROM eclipse-temurin:22-jre
RUN useradd --system --uid 1000 --create-home vector \
    && mkdir -p /data && chown vector /data
COPY --from=build /src/target/vector-*.jar /opt/vector/vector.jar

USER vector
# config.json, eula.txt, ban-list.txt, whitelist.txt, libraries/ and logs/ all live here.
WORKDIR /data
VOLUME /data

# Reach the dashboard from outside the container; set VECTOR_DASHBOARD_PASSWORD (or a random one is generated).
ENV VECTOR_DASHBOARD_HOST=0.0.0.0
# Players, dashboard, metrics.
EXPOSE 25565 3000 9100

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD java -cp /opt/vector/vector.jar gg.literal.HealthProbe 3000 || exit 1

ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/opt/vector/vector.jar", "--attached-console"]
