# Билдер с явным указанием платформы (ARM или x86)
FROM --platform=linux/amd64 maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn package -DskipTests

# Финальный образ (используем JRE без Alpine, т.к. с Alpine могут быть проблемы на ARM)
FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar

# Устанавливаем таймзону (опционально)
RUN apt-get update && apt-get install -y tzdata && rm -rf /var/lib/apt/lists/*

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]