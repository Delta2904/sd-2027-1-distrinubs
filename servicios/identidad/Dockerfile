# Etapa 1: compila con Maven dentro de Docker usando un usuario sin privilegios
# porque las pruebas levantan PostgreSQL embebido, que no arranca como root.
FROM maven:3.9.9-eclipse-temurin-21 AS construccion
RUN useradd --create-home constructor
ENV MAVEN_CONFIG=/home/constructor/.m2
USER constructor
WORKDIR /home/constructor/app
COPY --chown=constructor pom.xml .
RUN mvn -B -q dependency:go-offline
COPY --chown=constructor src ./src
RUN mvn -B -q package -DskipTests

# Etapa 2: imagen de ejecución ligera con JRE 21 y usuario sin privilegios.
FROM eclipse-temurin:21-jre
RUN useradd --system --create-home identidad
WORKDIR /app
COPY --from=construccion /home/constructor/app/target/*.jar app.jar
USER identidad
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
