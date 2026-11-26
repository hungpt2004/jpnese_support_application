FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY . .
RUN bash ./gradlew --no-daemon bootJar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/app.jar /app/app.jar
EXPOSE 10000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
