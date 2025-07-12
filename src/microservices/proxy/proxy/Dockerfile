# Этап 1: Сборка приложения (используем JDK 17)
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests

# Этап 2: Финальный образ (JRE 17)
FROM eclipse-temurin:17-jre

# Настройка пользователя и безопасности
RUN useradd -m -u 1001 appuser
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
RUN chown -R appuser:appuser /app
USER appuser

EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8000/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]