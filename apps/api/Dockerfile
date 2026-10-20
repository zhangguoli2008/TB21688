FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml pom.xml
COPY contracts contracts
COPY apps/api apps/api
RUN mvn --batch-mode -pl apps/api -am -DskipTests package

FROM eclipse-temurin:21.0.12.1_1-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /workspace/apps/api/target/api-0.1.0-SNAPSHOT.jar /app/api.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/api.jar"]
