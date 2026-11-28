# Servicio `envios`

Microservicio encargado de recibir soluciones, asegurar idempotencia por intento, publicar identificadores de envío en RabbitMQ, consultar el estado/veredicto y hacer cumplir la autenticación y control de propiedad de envíos.

## Qué hace

- **Recepción e idempotencia (`POST /api/envios`):** valida la petición, persiste el envío en PostgreSQL con estado `EN_COLA` antes de responder `"recibido"` (`201 Created`) y publica `{ "envioId": <id> }` en RabbitMQ esperando confirmación del broker (`publisher-confirm-type: correlated` y `publisher-returns`) antes de registrar `publicado_en` y `veces_publicado`. Si un reintento repite `(cuenta_id, id_intento)` (SQLState `23505`), devuelve `200 OK` con el envío existente sin duplicarlo; otras violaciones de integridad responden `400 Bad Request`. El cliente de RabbitMQ usa un timeout de conexión corto (`500ms` por omisión) para no bloquear la respuesta HTTP si el broker está caído.
- **Consulta de estado (`GET /api/envios/{envioId}`):** devuelve `"evaluado"` con el objeto `resultado`, `"evaluando"` si existe un lease vigente en `leases_evaluacion`, o `"en cola"` / `"recibido"` en caso contrario.
- **Autenticación y anti-suplantación con falla cerrada:** valida localmente la firma HMAC-SHA256 y expiración del JWT y confirma con el servicio `identidad` (`/api/auth/validar`) que el token no esté revocado ni la cuenta bloqueada. Solo el `401` y el `403` de `identidad` se devuelven como tales; si `identidad` no responde o contesta cualquier otro código, rechaza con `503 Service Unavailable` (falla cerrada). Exige que el rol pertenezca a `{ALUMNO, ORGANIZADOR, ADMINISTRADOR}`, rechaza `403 Forbidden` si se envía un `alumnoId` ajeno para cualquier rol y restringe `GET /api/envios/{envioId}` al dueño o roles de supervisión (`ORGANIZADOR`, `ADMINISTRADOR`).
- **Mensajería RabbitMQ con cola quorum, DLQ y barrido:** declara el exchange `distrinubs.envios`, la cola quorum durable `envios.evaluacion` con límite de entregas (`x-delivery-limit`) y `x-dead-letter-exchange` hacia `distrinubs.envios.dlx` (`envios.evaluacion.dlq`), y publica mensajes persistentes. Si ya existe una cola `envios.evaluacion` clásica en el broker, debe eliminarse antes de declarar la cola quorum, y el consumidor debe declararla con exactamente los mismos argumentos o no declararla.
- **Barrido periódico (`BarridoRepublicacionEnvios`):** reclama envíos en `EN_COLA` sin resultado utilizando `FOR NO KEY UPDATE OF e SKIP LOCKED` para evitar duplicados entre réplicas. Solo republica envíos nunca publicados tras el margen de gracia (`10 s`) o envíos ya publicados que superaron la espera máxima en cola (`300 s`), respetando el tope de publicaciones (`3`) y excluyendo los que agotaron sus tomas de evaluación (`veces_tomado`). Si el broker no confirma la publicación, corta el lote. Al terminar, los envíos reclamados que no se publicaron (porque se cortó el lote o porque uno lanzó una excepción) recuperan su `publicado_en` anterior, o `NULL` si nunca se habían publicado, para que el siguiente barrido los vuelva a tomar; la falla de un envío no impide publicar los demás.
- **Migraciones con Flyway:** `envios` aplica sus migraciones al arrancar con `out-of-order` y tolerancia a migraciones ajenas, por lo que puede arrancar solo sobre una base vacía. Incluye una migración que amplía el `CHECK` de `lenguaje`: el esquema ya admite `PYTHON3`, `CPP`, `C`, `JAVA` y `JAVASCRIPT`. Que el esquema los admita no significa que el servicio los acepte: la lista aceptada la decide el equipo y por ahora es solo `PYTHON3` (ver `ENVIOS_LENGUAJES`).
- **Registro en directorio y salud:** cliente Eureka configurable (`EUREKA_CLIENT_ENABLED`, `EUREKA_SERVER_URL`) y Spring Boot Actuator (`/actuator/health`).

## Qué no hace y qué falta

- **No consume la cola ni ejecuta código:** la evaluación y el manejo completo de la cola de mensajes muertos (DLQ) de punta a punta quedan pendientes de integrarse con `evaluacion`.
- **Coordinación de migraciones en Compose:** falta acordar en `docker-compose.yml` qué servicio o contenedor ejecuta las migraciones para no depender del orden de arranque.
- **Enrutamiento en el gateway:** el gateway no debe enrutar `/api/envios` a este servicio hasta que entre la pantalla de inicio de sesión del front, ya que exige `Authorization: Bearer`.
- **Lista de lenguajes:** el servicio solo acepta `PYTHON3`. Qué lenguajes se aceptan es una decisión que el equipo todavía no toma; mientras tanto, cualquier otro lenguaje se rechaza con `400 Bad Request`, aunque el esquema ya lo admita. `ENVIOS_LENGUAJES` no debe ampliarse hasta que el equipo decida la lista y `evaluacion` sepa ejecutar esos lenguajes.

## Propiedad de datos

`envios` es el único dueño de escritura de la tabla `envios`. Solo lee `cuentas`, `problemas`, `leases_evaluacion` y `resultados`.

## Variables de entorno

| Variable | Por omisión | Descripción |
|---|---|---|
| `PORT` | `8081` | Puerto HTTP del servicio |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | `localhost`, `5432`, `distrinubs` | Conexión a PostgreSQL |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD` | `localhost`, `5672`, `guest` | Conexión a RabbitMQ |
| `RABBITMQ_CONNECTION_TIMEOUT` | `500ms` | Timeout de conexión hacia RabbitMQ |
| `JWT_SECRETO` | *(obligatoria, sin omisión)* | Secreto compartido para validar firma JWT; el servicio no arranca si falta |
| `IDENTIDAD_URL` | `http://identidad:8083` | URL base del servicio `identidad` |
| `IDENTIDAD_TIMEOUT_MS` | `2000` | Tiempo máximo de espera hacia `identidad` |
| `ENVIOS_LENGUAJES` | `PYTHON3` | Lista separada por comas de lenguajes aceptados. Por ahora solo `PYTHON3`; el esquema admite además `CPP`, `C`, `JAVA` y `JAVASCRIPT`, pero la lista aceptada la decide el equipo |
| `ENVIOS_LIMITE_ENTREGAS` | `3` | Límite de entregas (`x-delivery-limit`) antes de mandar a la DLQ |
| `ENVIOS_CONFIRM_TIMEOUT_MS` | `1000` | Tiempo máximo de espera por el ack del broker al publicar |
| `ENVIOS_BARRIDO_ACTIVO` | `true` | Activa el barrido de republicación |
| `ENVIOS_BARRIDO_INTERVALO_MS` | `15000` | Intervalo entre ejecuciones del barrido |
| `ENVIOS_BARRIDO_GRACIA_SEG` | `10` | Segundos de gracia antes de publicar envíos sin `publicado_en` |
| `ENVIOS_BARRIDO_ESPERA_MAXIMA_SEG` | `300` | Segundos de espera máxima en cola antes de republicar un envío ya publicado |
| `EUREKA_CLIENT_ENABLED` | `true` | Habilita el registro en Eureka |
| `EUREKA_SERVER_URL` | `http://localhost:8761/eureka/` | URL del servidor Eureka |

## Cómo construir y correr pruebas

Desde `servicios/envios/` (sin necesidad de Java o Maven instalados localmente):

```bash
docker build --target construccion -t distrinubs-envios-test .
docker run --rm distrinubs-envios-test mvn -B -q test
```
