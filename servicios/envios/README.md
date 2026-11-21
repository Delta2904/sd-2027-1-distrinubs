# Servicio `envios`

Microservicio encargado de recibir soluciones, asegurar idempotencia por intento, publicar identificadores de envío en RabbitMQ, consultar el estado/veredicto y hacer cumplir la autenticación y control de propiedad de envíos.

## Qué hace

- **Recepción e idempotencia (`POST /api/envios`):** valida la petición, persiste el envío en PostgreSQL con estado `EN_COLA` antes de responder `"recibido"` (`201 Created`) y publica `{ "envioId": <id> }` en RabbitMQ. Si un reintento repite `(cuenta_id, id_intento)`, devuelve `200 OK` con el envío existente sin duplicarlo.
- **Consulta de estado (`GET /api/envios/{envioId}`):** devuelve `"evaluado"` con el objeto `resultado`, `"evaluando"` si existe un lease vigente en `leases_evaluacion`, o `"en cola"` / `"recibido"` en caso contrario.
- **Autenticación y anti-suplantación con falla cerrada:** valida localmente la firma HMAC-SHA256 y expiración del JWT y confirma con el servicio `identidad` (`/api/auth/validar`) que el token no esté revocado ni la cuenta bloqueada. Si `identidad` no responde, rechaza con `503 Service Unavailable` (falla cerrada). Un `ALUMNO` solo puede enviar y consultar sus propios envíos (`403 Forbidden` si intenta usar otro `alumnoId` o consultar un envío ajeno); `ORGANIZADOR` y `ADMINISTRADOR` pueden consultar envíos para supervisión.
- **Mensajería RabbitMQ con DLQ y barrido:** declara el exchange `distrinubs.envios`, la cola durable `envios.evaluacion` configurada con `x-dead-letter-exchange` hacia `distrinubs.envios.dlx` (`envios.evaluacion.dlq`), envía mensajes persistentes con encabezado `x-max-reintentos` y ejecuta un barrido periódico (`BarridoRepublicacionEnvios`) para republicar envíos en `EN_COLA` que excedan el margen de gracia sin resultado ni lease vigente (por ejemplo, ante una caída entre guardar en PostgreSQL y publicar en RabbitMQ).
- **Registro en directorio y salud:** cliente Eureka configurable (`EUREKA_ACTIVO`) y Spring Boot Actuator (`/actuator/health`).

## Qué no hace y qué falta

- **No consume la cola ni ejecuta código:** la evaluación y la actualización del esquema de evaluación pertenecen al servicio `evaluacion`.
- **No levanta su propio `docker-compose.yml`:** la integración al Compose general queda pendiente de coordinación con el resto del equipo.
- **Lista de lenguajes configurable:** por omisión solo acepta `PYTHON3`; se amplía mediante `ENVIOS_LENGUAJES` cuando el consumidor soporte más lenguajes.

## Propiedad de datos

`envios` es el único dueño de escritura de la tabla `envios`. Solo lee `cuentas`, `problemas`, `leases_evaluacion` y `resultados`.

## Variables de entorno

| Variable | Por omisión | Descripción |
|---|---|---|
| `ENVIOS_PUERTO` | `8081` | Puerto HTTP del servicio |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | `localhost`, `5432`, `distrinubs` | Conexión a PostgreSQL |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD` | `localhost`, `5672`, `guest` | Conexión a RabbitMQ |
| `JWT_SECRETO` | `distrinubs-secreto-compartido-etapa-02-separacion-2027` | Secreto compartido para validar firma JWT |
| `IDENTIDAD_URL` | `http://identidad:8083` | URL base del servicio `identidad` |
| `IDENTIDAD_TIMEOUT_MS` | `2000` | Tiempo máximo de espera hacia `identidad` |
| `ENVIOS_LENGUAJES` | `PYTHON3` | Lista separada por comas de lenguajes aceptados |
| `ENVIOS_BARRIDO_ACTIVO` | `true` | Activa el barrido de republicación |
| `ENVIOS_BARRIDO_INTERVALO_MS` | `15000` | Intervalo entre ejecuciones del barrido |
| `ENVIOS_BARRIDO_GRACIA_SEG` | `10` | Segundos de gracia antes de republicar |
| `EUREKA_ACTIVO` | `false` | Habilita el registro en Eureka |
| `EUREKA_URL` | `http://eureka:8761/eureka/` | URL del servidor Eureka |

## Cómo construir y correr pruebas

Desde `servicios/envios/` (sin necesidad de Java o Maven instalados localmente):

```bash
docker build --target construccion -t distrinubs-envios-test .
docker run --rm distrinubs-envios-test mvn -B -q test
```
