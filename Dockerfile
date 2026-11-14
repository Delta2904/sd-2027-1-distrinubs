# Etapa 1: compila con Maven dentro de Docker. No hace falta tener Java ni
# Maven instalados. Usa un usuario sin privilegios porque las pruebas levantan
# un PostgreSQL embebido, y PostgreSQL no arranca como root.
FROM maven:3.9.9-eclipse-temurin-21 AS construccion
RUN useradd --create-home constructor
ENV MAVEN_CONFIG=/home/constructor/.m2
USER constructor
WORKDIR /home/constructor/app
COPY --chown=constructor pom.xml .
RUN mvn -B -q dependency:go-offline
COPY --chown=constructor src ./src
RUN mvn -B -q package -DskipTests

# Etapa 2: el JRE, el jar y Python 3 para los workers de evaluación. La
# aplicación no corre como root, así el código de los alumnos tampoco; aun así
# no es un sandbox.
FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home juez
WORKDIR /app
COPY --from=construccion /home/constructor/app/target/*.jar app.jar
USER juez
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
