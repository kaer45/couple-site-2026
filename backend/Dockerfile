# ============================================================
# 后端 Dockerfile（多阶段构建）
# 构建: maven + JDK17  ->  运行: JRE17
# ============================================================
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# 上传目录（docker-compose 会挂载卷到这里）
VOLUME /data/uploads
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
