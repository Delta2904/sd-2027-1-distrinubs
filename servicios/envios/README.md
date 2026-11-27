# Servicio `envios`

Microservicio encargado de recibir soluciones, asegurar idempotencia por intento, publicar identificadores de envío en RabbitMQ, consultar el estado/veredicto y hacer cumplir la autenticación y control de propiedad de envíos.

## Qué hace

- **Recepción e idempotencia (`POST /api/envios`):** valida la petición, persiste el envío en PostgreSQL con estado `EN_COLA` antes de responder `"recibido"` (`201 Created`) y publica `{ "envioId": <id> }` en RabbitMQ registrando `publicado_en` y `veces_publicado`. Si un reintento repite `(cuenta_id, id_intento)`, devuelve `200 OK` con el envío existente sin duplicarlo. El cliente de RabbitMQ usa un timeout de conexión corto (`500ms` por omisión) para no bloquear la respuesta HTTP si el broker está caído.
- **Consulta de estado (`GET /api/envios/{envioId}`):** devuelve `"evaluado"` con el objeto `resultado`, `"evaluando"` si existe un lease vigente en `leases_evaluacion`, o `"en cola"` / `"recibido"` en caso contrario.
- **Autenticación y anti-suplantación con falla cerrada:** valida localmente la firma HMAC-SHA256 y expiración del JWT y confirma con el servicio `identidad` (`/api/auth/validar`) que el token no esté revocado ni la cuenta bloqueada. Si `identidad` no responde, rechaza con `503 Service Unavailable` (falla cerrada). Un `ALUMNO` solo puede enviar y consultar sus propios envíos (`403 Forbidden` si intenta usar otro `alumnoId` o consultar un envío ajeno); `ORGANIZADOR` y `ADMINISTRADOR` pueden consultar envíos para supervisión.
- **Mensajería RabbitMQ con cola quorum, DLQ y barrido:** declara el exchange `distrinubs.envios`, la cola quorum durable `envios.evaluacion` con límite de entregas (`x-delivery-limit`) y `x-dead-letter-exchange` hacia `distrinubs.envios.dlx` (`envios.evaluacion.dlq`), y publica mensajes persistentes. Si ya existe una cola `envios.evaluacion` clásica en el broker, debe eliminarse antes de declarar la cola quorum, y el consumidor debe declararla con exactamente los mismos argumentos o no declararla.
- **Barrido periódico (`BarridoRepublicacionEnvios`):** reclama envíos en `EN_COLA` sin resultado utilizando `FOR NO KEY UPDATE OF e SKIP LOCKED` para evitar duplicados entre réplicas. Solo republica envíos nunca publicados tras el margen de gracia (`10 s`) o envíos ya publicados que superaron la espera máxima en cola (`300 s`), respetando el tope de publicaciones (`3`) y excluyendo los que agotaron sus tomas de evaluación (`veces_tomado`). Si el broker falla durante el lote, libera `publicado_en` de los pendientes para reintentar luego.
- **Migraciones con Flyway:** `envios` aplica sus migraciones al arrancar con `out-of-order` y tolerancia a migraciones ajenas, por lo que puede arrancar solo sobre una base vacía.
- **Registro en directorio y salud:** cliente Eureka configurable (`EUREKA_ACTIVO`) y Spring Boot Actuator (`/actuator/health`).

## Qué no hace y qué falta

- **No consume la cola ni ejecuta código:** la evaluación y el manejo completo de la cola de mensajes muertos (DLQ) de punta a punta quedan pendientes de integrarse con `evaluacion`.
- **Coordinación de migraciones en Compose:** falta acordar en `docker-compose.yml` qué servicio o contenedor ejecuta las migraciones para no depender del orden de arranque.
- **Enrutamiento en el gateway:** el gateway no debe enrutar `/api/envios` a este servicio hasta que entre la pantalla de inicio de sesión del front, ya que exige `Authorization: Bearer`.
- **Lista de lenguajes configurable:** por omisión solo acepta `PYTHON3`; se amplía mediante `ENVIOS_LENGUAJES` cuando el consumidor soporte más lenguajes.

## Propiedad de datos

`envios` es el único dueño de escritura de la tabla `envios`. Solo lee `cuentas`, `problemas`, `leases_evaluacion` y `resultados`.

## Variables de entorno

| Variable | Por omisión | Descripción |
|---|---|---|
| `ENVIOS_PUERTO` | `8081` | Puerto HTTP del servicio |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | `localhost`, `5432`, `distrinubs` | Conexión a PostgreSQL |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD` | `localhost`, `5672`, `guest` | Conexión a RabbitMQ |
| `RABBITMQ_CONNECTION_TIMEOUT` | `500ms` | Timeout de conexión hacia RabbitMQ |
| `JWT_SECRETO` | *(obligatoria, sin omisión)* | Secreto compartido para validar firma JWT; el servicio no arranca si falta |
| `IDENTIDAD_URL` | `http://identidad:8083` | URL base del servicio `identidad` |
| `IDENTIDAD_TIMEOUT_MS` | `2000` | Tiempo máximo de espera hacia `identidad` |
| `ENVIOS_LENGUAJES` | `PYTHON3` | Lista separada por comas de lenguajes aceptados |
| `ENVIOS_LIMITE_ENTREGAS` | `3` | Límite de entregas (`x-delivery-limit`) antes de mandar a la DLQ |
| `ENVIOS_BARRIDO_ACTIVO` | `true` | Activa el barrido de republicación |
| `ENVIOS_BARRIDO_INTERVALO_MS` | `15000` | Intervalo entre ejecuciones del barrido |
| `ENVIOS_BARRIDO_GRACIA_SEG` | `10` | Segundos de gracia antes de publicar envíos sin `publicado_en` |
| `ENVIOS_BARRIDO_ESPERA_MAXIMA_SEG` | `300` | Segundos de espera máxima en cola antes de republicar un envío ya publicado |
| `EUREKA_ACTIVO` | `false` | Habilita el registro en Eureka |
| `EUREKA_URL` | `http://eureka:8761/eureka/` | URL del servidor Eureka |

## Cómo construir y correr pruebas

Desde `servicios/envios/` (sin necesidad de Java o Maven instalados localmente):

```bash
docker build --target construccion -t distrinubs-envios-test .
docker run --rm distrinubs-envios-test mvn -B -q test
```
