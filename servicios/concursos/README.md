# Servicio de Concursos y Problemas

Servicio desacoplado del juez en línea Distrinubs para la etapa `02-separacion`.

## Responsabilidades
- Dueño de datos de `concursos`, `problemas` y `casos_prueba`.
- Carga automática de la siembra idempotente al arrancar (`SiembraConcursos`).
- Conserva la tabla de posiciones mientras no exista el servicio `marcador`. La tabla lista solo las cuentas con rol de alumno: las de organizador y de administración no compiten.
- No escribe en `cuentas`: las siembra y las lista `identidad`, su dueña. Este servicio solo las lee para armar la tabla.
- Registro dinámico como cliente de descubrimiento en Netflix Eureka (`concursos`).

## Endpoints expuestos
- `GET /api/concursos/activo`: Devuelve el concurso actualmente abierto con sus problemas y límites.
- `GET /api/concursos/activo/tabla`: Calcula y devuelve la tabla de posiciones provisional.
- `GET /actuator/health`: Estado de salud del servicio.

## Variables de entorno
- `DB_HOST`: Host de PostgreSQL (por omisión: `localhost`).
- `DB_PORT`: Puerto de PostgreSQL (por omisión: `5432`).
- `DB_NAME`: Base de datos (por omisión: `distrinubs`).
- `DB_USER`: Usuario de base de datos (por omisión: `distrinubs`).
- `DB_PASSWORD`: Contraseña (por omisión: `distrinubs`).
- `PORT`: Puerto HTTP (por omisión: `8082`).
- `EUREKA_SERVER_URL`: URL del servidor Eureka (por omisión: `http://localhost:8761/eureka/`).
- `EUREKA_CLIENT_ENABLED`: Habilita registro en Eureka (por omisión: `true`).

## Qué no hace este servicio y qué falta
- **No evalúa envíos ni ejecuta código**: la recepción de envíos corresponde al servicio `envios` y su calificación al servicio `evaluacion`.
- **No gestiona autenticación, sesiones ni cuentas**: eso corresponde al servicio `identidad`, que también atiende `GET /api/cuentas-demo`.
- **La tabla de posiciones depende del esquema de `identidad`**: filtra por la columna `rol` de `cuentas`, que agrega una migración de `identidad`. Con el Compose completo esa migración siempre está aplicada; si `concursos` corre solo contra una base vacía, la tabla responde con error.
- **No hay interfaz de organizador**: los concursos, problemas y casos de prueba se cargan únicamente mediante la siembra automática al arrancar (`SiembraConcursos`).
- **Pendiente para etapas posteriores**:
  - Extraer el cálculo de la tabla de posiciones (`GET /api/concursos/activo/tabla`) hacia su propio servicio (`marcador`).
  - El límite de memoria se guarda aquí, pero lo aplica `evaluacion` (pendiente en ese servicio).

## Cómo se prueba
Desde `servicios/concursos/`, sin Java ni Maven instalados:
```bash
docker build --target construccion -t distrinubs-concursos-test .
docker run --rm distrinubs-concursos-test mvn -B -q test
```
Las pruebas de la siembra y de la tabla solo con alumnos levantan un PostgreSQL embebido; las demás usan dobles de la base. Con JDK 21 y Maven instalados también corren con `mvn test`.
