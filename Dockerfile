FROM node:22-alpine AS web
WORKDIR /build/web
COPY web/package*.json ./
RUN npm ci
COPY web/ ./
RUN npm run build
FROM maven:3.9-eclipse-temurin-21 AS java
WORKDIR /build
COPY pom.xml ./
COPY src ./src
COPY --from=web /build/web/dist ./src/main/resources/static
RUN mvn -B package -DskipTests
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S apogee && adduser -S apogee -G apogee && mkdir /app/data && chown apogee:apogee /app/data
COPY --from=java /build/target/apogee-0.1.0.jar /app/apogee.jar
USER apogee
CMD ["java", "-jar", "/app/apogee.jar"]
