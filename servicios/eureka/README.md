# Servicio `eureka` (directorio)

Dueño: Emiliano (@Delta2904). Etapa `02-separacion`.

## Qué hace

Es el directorio de servicios: un servidor Eureka en su propio contenedor. Cada servicio se registra aquí al arrancar, con su nombre y su dirección, y renueva ese registro cada pocos segundos. El gateway pide la lista y así encuentra a cada servicio por su nombre, sin direcciones fijas.

- **Quién se registra:** `gateway`, `identidad`, `concursos` y `envios`. `evaluacion` no: no recibe peticiones, toma su trabajo de RabbitMQ.
- **Instancia caída:** una instancia que muere sin avisar deja de renovar y el directorio la saca cuando vence su registro. La auto-preservación va apagada: todo corre en una computadora, así que una instancia que no renueva está caída y no detrás de una falla de red.
- **Tiempos:** el directorio revisa cada 5 s qué registros vencieron. Cada servicio renueva cada 5 s y su registro dura 15 s; esos dos valores los fija el `docker-compose.yml`. En la práctica una instancia caída sale de la lista en unos 30 a 35 s, porque Eureka cuenta la duración a partir de la última renovación más la propia duración.
- **Lista al momento:** quien consulta `/eureka/apps` ve las altas y las bajas de inmediato, sin la copia de solo lectura que Eureka refresca cada 30 s.
- **Salud:** `/actuator/health`, que usan el healthcheck de Compose y la vista del estado de los servicios.

## Qué no hace y qué falta

- **No guarda nada en disco.** La lista vive en memoria. Si el contenedor se reinicia, se reconstruye sola en segundos con las renovaciones de los servicios.
- **Es un solo servidor.** Si cae, el gateway sigue trabajando con la última lista que guardó, pero no ve instancias nuevas ni bajas hasta que el directorio vuelve.
- **No balancea.** Entre las réplicas del gateway reparte nginx; del gateway a los servicios reparte Spring Cloud LoadBalancer con la lista que le da el directorio.
- **Sin autenticación.** Cualquiera que llegue al puerto puede registrar una instancia. En Compose el puerto solo se publica en `127.0.0.1`, para ver la lista y para la demo de fallas.

## Variables de entorno

| Variable | Por omisión | Descripción |
|---|---|---|
| `PORT` | `8761` | Puerto HTTP del directorio |
| `EUREKA_HOSTNAME` | `localhost` | Nombre con el que el servidor se conoce a sí mismo. En Compose es `eureka` |
| `EUREKA_AUTO_PRESERVACION` | `false` | Auto-preservación: conserva a las instancias que dejan de renovar |
| `EUREKA_INTERVALO_EXPULSION_MS` | `5000` | Cada cuánto se revisa qué registros vencieron |

## Cómo se prueba

Desde `servicios/eureka/`, sin Java ni Maven instalados:

```bash
docker build --target construccion -t distrinubs-eureka-test .
docker run --rm distrinubs-eureka-test mvn -B -q test
```

Las pruebas levantan el servidor en un puerto libre y comprueban que responde la lista en JSON, que una instancia recién registrada aparece de inmediato, que la salud responde `UP` y que la auto-preservación está apagada.

Con el Compose levantado, la lista de instancias se ve en `http://localhost:8761` y `scripts/demo_fallas.py --caso 3` mata una instancia y mide cuánto tarda en salir de la lista.
