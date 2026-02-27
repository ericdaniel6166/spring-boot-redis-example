# Stage 1: Build
FROM maven:3-amazoncorretto-21-alpine AS build
WORKDIR /app

# Copy pom and download dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build (including the flag for the build-time checks)
COPY src ./src
RUN mvn clean package -DskipTests -Dliquibase.secureParsing=false

# Stage 2: Run
FROM amazoncorretto:21-alpine
WORKDIR /app

# Security: Run as non-root
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

# The "sh -c" wrapper is required to expand the $JAVA_OPTS variable
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]