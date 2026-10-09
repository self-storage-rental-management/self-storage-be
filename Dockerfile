FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src

RUN chmod +x mvnw && ./mvnw -DskipTests package

FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=build /app/target/storagehub-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 10000
# Leave 40% of container memory for metaspace, threads and native buffers.
# Exit promptly on heap exhaustion instead of hanging until the port scan times out.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
