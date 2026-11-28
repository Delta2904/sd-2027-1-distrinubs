# Servicio `evaluacion`

Dueño: Emiliano (@Delta2904). Etapa `02-separacion`.

## Qué hace

Evalúa los envíos de los alumnos. Consume de RabbitMQ la cola `envios.evaluacion`, que publica `envios` con el mensaje `{"envioId": N}`. Por cada mensaje toma el envío con un lease, corre los casos del marcador provisional con Python 3 y guarda el veredicto en `resultados`.

- **Escribe:** `leases_evaluacion` y `resultados`. **Lee:** `envios`, `problemas` y `casos_prueba`.
- **Varios workers:** `EVALUACION_WORKERS` consumidores por contenedor (por omisión 2), cada uno con un mensaje a la vez. Se agregan contenedores con `docker compose up --scale evaluacion=N`. No se registra en Eureka: toma el trabajo de RabbitMQ, no recibe peticiones.
- **Manda el lease, no el mensaje.** El estado vive en PostgreSQL; el mensaje solo avisa qué envío revisar.

### Qué hace con cada mensaje

| Situación | Qué hace | Mensaje |
|---|---|---|
| Lo evaluó y guardó el veredicto | — | Lo confirma |
| El envío ya tenía resultado (mensaje duplicado) | No lo evalúa | Lo confirma |
| Otro worker tiene su lease vigente (por ejemplo, murió a media evaluación) | Espera a que venza el lease y lo retoma | Lo retiene mientras espera |
| Falla del servidor (no lanza Python, problema sin casos, revisión de sintaxis colgada) | Libera su lease al momento | Lo devuelve a la cola para otro worker |
| Agotó sus 3 tomas | Guarda `ERROR_SERVIDOR`, que no penaliza | Lo confirma |
| El envío no existe o no está en cola | Lo intenta 3 veces y lo descarta | Lo confirma |
| El mensaje no trae `envioId` | — | Lo rechaza: va a `envios.evaluacion.dlq` |
| La base no responde | Reintenta con espera creciente | Lo retiene |

Solo confirma después de guardar el resultado. Si el worker muere antes, RabbitMQ entrega el mensaje a otro al cerrarse la conexión, y ese otro espera el lease del caído: el envío no se pierde ni se evalúa dos veces a la vez.

El tope de 3 tomas (`evaluacion.max-tomas`) cierra el envío con `ERROR_SERVIDOR` antes de que el límite de entregas de la cola (`x-delivery-limit` 3) lo mande a la cola de mensajes muertos. Un barrido cada 30 s cierra los envíos que agotaron sus tomas y se quedaron sin mensaje.

## Qué no hace

- **Sin sandbox:** el código del alumno corre como proceso hijo del servicio, sin root, en un directorio temporal y sin variables de entorno, pero sin contenedor propio ni límite de memoria. Es un riesgo declarado.
- Solo Python 3. Sin renovación de lease: un envío que tarda más que su lease puede retomarlo otro worker; la unicidad del resultado evita que cuente dos veces.
- No consume la cola de mensajes muertos ni publica resultados para `marcador`.
- No escribe el id de correlación de la petición en sus logs.

## Qué falta

- Consumidor de `envios.evaluacion.dlq`, para que los organizadores vean los envíos que llegaron ahí.
- Publicar el resultado de cada envío para `marcador`.
- Más lenguajes, sandbox por envío, límite de memoria y evaluación final.

## Configuración

| Variable | Por omisión | Qué es |
|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | `localhost`, `5432`, `distrinubs` | PostgreSQL |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD` | `localhost`, `5672`, `guest` | RabbitMQ |
| `EVALUACION_WORKERS` | `2` | Consumidores por contenedor |
| `ENVIOS_LIMITE_ENTREGAS` | `3` | Límite de entregas de la cola; debe ser igual al de `envios` |
| `EVALUACION_PUERTO` | `8085` | Puerto de Actuator (`/actuator/health`) |

El servicio aplica las migraciones con Flyway sobre la base compartida y tolera las de otros servicios que no trae.

## Pruebas

Sin Java instalado, desde `servicios/evaluacion`:

```bash
docker build --target construccion -t distrinubs-evaluacion-pruebas .
docker run --rm distrinubs-evaluacion-pruebas mvn -B -q test
```

Corren contra un PostgreSQL 17 embebido y sin broker (el canal de RabbitMQ es un doble). Cubren el lease ajeno de un worker caído, los mensajes duplicados con 4 consumidores, la falla del servidor con el tope de 3 tomas, el barrido y qué hace el consumidor con cada mensaje.
