# Etapa 1: compila con Maven dentro de Docker. No hace falta tener Java ni
# Maven instalados (PRD, bitácora 4).
FROM maven:3.9.9-eclipse-temurin-21 AS construccion
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# Etapa 2: el JRE, el jar y Python 3 para el worker de evaluación (PRD,
# bitácora 7). La aplicación no corre como root, así el código de los alumnos
# tampoco; aun así no es un sandbox (PRD, sección 0).
FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home juez
WORKDIR /app
COPY --from=construccion /app/target/*.jar app.jar
USER juez
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
