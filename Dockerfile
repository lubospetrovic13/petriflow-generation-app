FROM maven:3.8-openjdk-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/target/petriflow-unified-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Dhttps.protocols=TLSv1.2,TLSv1.3", "-Djdk.tls.client.protocols=TLSv1.2,TLSv1.3", "-jar", "app.jar"]