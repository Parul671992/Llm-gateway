# ---------- Stage 1: build the jar ----------
    FROM maven:3.9-eclipse-temurin-21 AS build
    WORKDIR /app
    
    # Copy only pom.xml first, and download dependencies (cached separately, see below)
    COPY pom.xml .
    RUN mvn -q dependency:go-offline
    
    # Now copy the source code and build
    COPY src ./src
    RUN mvn -q package -DskipTests
    
    # ---------- Stage 2: the small runtime image ----------
    FROM eclipse-temurin:21-jre
    WORKDIR /app
    
    # Never run as root inside a container
    RUN useradd --system --uid 1001 appuser
    
    COPY --from=build /app/target/*.jar app.jar
    
    USER appuser
    EXPOSE 8080
    ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]