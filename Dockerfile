# syntax=docker/dockerfile:1
FROM node:24-alpine AS web
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY proto/ /src/proto/
COPY web/ ./
RUN npm run build

FROM gradle:9-jdk25 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts ./
RUN gradle dependencies -q --no-daemon > /dev/null
COPY proto ./proto
COPY src ./src
COPY --from=web /src/web/dist ./web/dist
RUN gradle bootJar -x test -q --no-daemon

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S mayday && adduser -S -G mayday -u 10001 mayday && mkdir -p /data && chown 10001:mayday /data
COPY --from=build /src/build/libs/mayday.jar /opt/mayday/mayday.jar
USER 10001
ENV PORT=8080 MAYDAY_GRPC_PORT=9090 MAYDAY_DATA_DIR=/data
EXPOSE 8080 9090
VOLUME /data
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s CMD wget -qO- http://127.0.0.1:8080/readyz || exit 1
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:MaxRAMPercentage=75", "-jar", "/opt/mayday/mayday.jar"]
