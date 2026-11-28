# Servicio de Concursos y Problemas

Servicio desacoplado del juez en línea Distrinubs para la etapa `02-separacion`.

## Responsabilidades
- Dueño de datos de `concursos`, `problemas` y `casos_prueba`.
- Carga automática de la siembra idempotente al arrancar (`SiembraConcursos`).
- En el paso 1 conserva temporalmente la tabla de posiciones y las cuentas de demo (`SiembraCuentasDemo`).
- Registro dinámico como cliente de descubrimiento en Netflix Eureka (`concursos`).

## Endpoints expuestos
- `GET /api/concursos/activo`: Devuelve el concurso actualmente abierto con sus problemas y límites.
- `GET /api/concursos/activo/tabla`: Calcula y devuelve la tabla de posiciones provisional.
- `GET /api/cuentas-demo`: Devuelve la lista de cuentas de demo disponibles.
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
- **No evalúa envíos ni ejecuta código**: la recepción y calificación de envíos corresponde al servicio `envios`.
- **No gestiona autenticación ni sesiones**: aún no hay inicio de sesión ni tokens; las cuentas de demo se consultan sin contraseña.
- **No hay interfaz de organizador**: los concursos, problemas y casos de prueba se cargan únicamente mediante la siembra automática al arrancar (`SiembraConcursos`).
- **Pendiente para etapas posteriores**:
  - Extraer el cálculo de la tabla de posiciones (`GET /api/concursos/activo/tabla`) hacia su propio servicio (`marcador`).
  - Extraer la gestión de cuentas y la siembra de cuentas de demo (`GET /api/cuentas-demo`, `SiembraCuentasDemo`) hacia el servicio `identidad`.
  - Aplicar realmente los límites de memoria almacenados en la base de datos.

## Cómo se prueba
Ejecuta las pruebas unitarias e integración (usan PostgreSQL embebido con Zonky, no requieren levantar Docker manualmente):
```bash
mvn test
```
