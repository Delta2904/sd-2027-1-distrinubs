# Etapa de compilación y pruebas dentro de Docker (no requiere Java ni Maven instalados).
FROM maven:3.9.9-eclipse-temurin-21 AS construccion
RUN useradd --create-home constructor
ENV MAVEN_CONFIG=/home/constructor/.m2
USER constructor
WORKDIR /home/constructor/app
COPY --chown=constructor pom.xml .
RUN mvn -B -q dependency:go-offline
COPY --chown=constructor src ./src
RUN mvn -B -q test-compile
