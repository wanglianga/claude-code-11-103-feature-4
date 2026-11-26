# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
# 使用阿里云 Maven 镜像加速（题目环境约定源）
COPY docker-settings.xml /tmp/settings.xml
COPY pom.xml .
RUN mvn -B -s /tmp/settings.xml dependency:go-offline -DskipTests || true
COPY src ./src
RUN mvn -B -s /tmp/settings.xml clean package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/target/medwaste-backend.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
