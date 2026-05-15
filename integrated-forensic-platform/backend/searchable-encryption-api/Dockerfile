FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY ["integrated-forensic-platform/backend/searchable-encryption-api/pom.xml", "integrated-forensic-platform/backend/searchable-encryption-api/pom.xml"]
COPY ["integrated-forensic-platform/backend/searchable-encryption-api/src", "integrated-forensic-platform/backend/searchable-encryption-api/src"]
COPY ["demo/searchable encryption/src", "demo/searchable encryption/src"]

WORKDIR /workspace/integrated-forensic-platform/backend/searchable-encryption-api
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/integrated-forensic-platform/backend/searchable-encryption-api/target/searchable-encryption-api-0.1.0.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]

