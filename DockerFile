
# Build Spring Boot với Java 25
FROM maven:3.9.11-eclipse-temurin-25 AS build

WORKDIR /app
COPY . .

RUN mvn -B -Dmaven.test.skip=true package \
    && cp target/*.jar /tmp/app.jar

# Chạy ứng dụng
FROM eclipse-temurin:25-jre

WORKDIR /app
COPY --from=build /tmp/app.jar app.jar

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65.0"

EXPOSE 10000

CMD ["sh", "-c", "exec java -Dserver.address=0.0.0.0 -Dserver.port=${PORT:-10000} -jar app.jar"]
