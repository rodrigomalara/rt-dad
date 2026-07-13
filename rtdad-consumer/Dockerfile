FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY rtdad-common/pom.xml rtdad-common/pom.xml
COPY rtdad-producer/pom.xml rtdad-producer/pom.xml
COPY rtdad-consumer/pom.xml rtdad-consumer/pom.xml
RUN chmod +x mvnw
COPY rtdad-common/src rtdad-common/src
COPY rtdad-consumer/src rtdad-consumer/src
RUN ./mvnw -q -pl rtdad-consumer -am package -DskipTests

FROM eclipse-temurin:25-jre AS runtime
RUN useradd --create-home --shell /usr/sbin/nologin rtdad
USER rtdad
WORKDIR /app
COPY --from=build /workspace/rtdad-consumer/target/rtdad-consumer-*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
