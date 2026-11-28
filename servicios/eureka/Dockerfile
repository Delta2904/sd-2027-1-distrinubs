# Etapa 1: compila con Maven dentro de Docker. No hace falta tener Java ni
# Maven instalados.
FROM maven:3.9.9-eclipse-temurin-21 AS construccion
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# Etapa 2: solo el JRE y el jar, con un usuario sin privilegios.
FROM eclipse-temurin:21-jre
RUN useradd --system --create-home directorio
WORKDIR /app
COPY --from=construccion /app/target/*.jar app.jar
USER directorio
EXPOSE 8761
ENTRYPOINT ["java", "-jar", "app.jar"]
