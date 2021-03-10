FROM maven:3.6-openjdk-15 AS builder
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline
COPY . .
RUN mvn compile package

FROM openjdk:15-slim
# To store database files outside of the container
VOLUME /app
COPY --from=builder /build/target/ari.jar /
WORKDIR /app
EXPOSE 14265
ENTRYPOINT ["java", "-jar", "/ari.jar"]
CMD ["--debug", "-r", "14265", "-p"]
