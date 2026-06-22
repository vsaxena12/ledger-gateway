FROM eclipse-temurin:26-jdk AS build
WORKDIR /build
COPY pom.xml pom.xml
COPY gateway gateway
COPY account-service account-service
RUN mvn -B -pl gateway -am -DskipTests package

FROM eclipse-temurin:26-jre
WORKDIR /app
COPY --from=build /build/gateway/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar","--spring.profiles.active=docker"]