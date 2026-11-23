# syntax=docker/dockerfile:1

# ---------- Build stage: JDK 25 + Maven Wrapper ----------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Copy phần khai báo build trước để tận dụng cache layer khi chỉ sửa source
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Test chạy riêng qua service `maven` trong docker-compose (cần MySQL), không chạy lúc build image
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -q package -DskipTests \
    && cp target/*.jar app.jar

# ---------- Runtime stage: chỉ JRE 25, chạy bằng user không phải root ----------
FROM eclipse-temurin:25-jre
WORKDIR /app

# Thư mục logs/ (logback-spring.xml) phải ghi được bởi user spring
RUN groupadd --system spring && useradd --system --gid spring --no-create-home spring \
    && mkdir -p /app/logs && chown spring:spring /app/logs
COPY --from=build --chown=spring:spring /workspace/app.jar app.jar
USER spring

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
