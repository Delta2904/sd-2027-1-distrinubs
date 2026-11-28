# Servicio Gateway (Spring Cloud Gateway)

API Gateway de Distrinubs para la etapa `02-separacion`.

## Responsabilidades
- Única puerta de entrada para clientes y front.
- Reenvío reactivo y balanceo dinámico hacia microservicios registrados en Eureka (`lb://<servicio>`).
- Enrutamiento por prefijos:
  - `/api/concursos/**` -> `concursos`
  - `/api/cuentas-demo/**` -> `identidad` (lista solo las cuentas de alumno que no están bloqueadas)
  - `/api/envios/**` -> `envios`
  - `/api/auth/**` -> `identidad`
  - `/api/identidad/**` -> `identidad`
  - `/api/marcador/**` -> `marcador` (cuando se separe)
- **Filtro global de autenticación (`FiltroAutenticacionGateway`):**
  - **Rutas públicas sin token:** es una lista exacta, sin prefijos: `POST /api/auth/login`, `GET /api/cuentas-demo`, `GET /api/concursos/activo`, `GET /api/concursos/activo/tabla`, `GET /api/observabilidad/estado`, `GET /actuator/health`, `GET /actuator/health/liveness`, `GET /actuator/health/readiness`, `GET /actuator/info`, peticiones `OPTIONS` y recursos estáticos (`/`, `/index.html`, `/app.js`, `/estilos.css`, `/favicon.ico`). Cualquier otra ruta exige token, aunque empiece igual que una pública.
  - **Rutas sin normalizar:** una ruta con segmentos `.` o `..` (también codificados) o con `//` se rechaza con `400 Bad Request` antes de decidir si es pública.
  - **Rutas protegidas:** exige `Authorization: Bearer <token>` (HS256 con `sub` igual al id de cuenta en texto, `usuario`, `nombre`, `rol`, `jti`, `iat`, `iatMs`, `exp`), verifica localmente firma y vencimiento (`VerificadorJwtGateway`) y confirma revocación/bloqueo contra `identidad` (`GET /api/auth/validar`). Solo el `401` y el `403` de `identidad` se devuelven como tales. Si `identidad` cae, expira el timeout o responde cualquier otro código, el gateway responde `503 Service Unavailable` (falla cerrada); los errores posteriores de otros servicios aguas abajo no se enmascaran como falla de `identidad`.
  - **Cabeceras internas propagadas:** limpia cualquier cabecera `X-Usuario-*` recibida del cliente y propaga `X-Usuario-Id` (id numérico de cuenta), `X-Usuario-Login` (nombre de usuario) y `X-Usuario-Rol`.
- Preparado para correr en múltiples réplicas (`--scale gateway=2`) detrás del balanceador nginx.

## Variables de entorno
- `PORT`: Puerto HTTP del gateway (por omisión: `8080`).
- `EUREKA_SERVER_URL`: URL del servidor Eureka (por omisión: `http://localhost:8761/eureka/`).
- `EUREKA_CLIENT_ENABLED`: Habilita registro en Eureka (por omisión: `true`).
- `JWT_SECRETO`: *(obligatoria, sin omisión)* Secreto HMAC-SHA256 compartido para validar tokens JWT; el servicio no arranca si falta.
- `IDENTIDAD_URL`: URL base del servicio `identidad` (por omisión: `http://identidad:8083`).
- `IDENTIDAD_TIMEOUT_MS`: Tiempo máximo de espera hacia `identidad` en milisegundos (por omisión: `2000`).
- `RUTA_CONCURSOS_URI`: URI de reenvío para concursos (por omisión: `lb://concursos`).
- `RUTA_CUENTAS_DEMO_URI`: URI de reenvío para cuentas demo (por omisión: `lb://identidad`).
- `RUTA_ENVIOS_URI`: URI de reenvío para envíos (por omisión: `lb://envios`).
- `RUTA_AUTH_URI`: URI de reenvío para autenticación (por omisión: `lb://identidad`).
- `RUTA_IDENTIDAD_URI`: URI de reenvío para administración de cuentas (por omisión: `lb://identidad`).
- `RUTA_MARCADOR_URI`: URI de reenvío para marcador (por omisión: `lb://marcador`).

## Qué no hace este servicio y qué falta
- **No contiene lógica de negocio ni acceso a base de datos**: únicamente valida el token, enruta y balancea peticiones HTTP hacia los microservicios registrados en Eureka.
- **No emite tokens ni guarda sesiones**: el gateway valida el token de cada petición con su filtro, pero el inicio de sesión, la emisión del token, el bloqueo y la revocación son del servicio `identidad`. Tampoco decide permisos por rol: propaga el rol verificado y cada servicio decide.
- **Ruta pendiente de respaldo en esta etapa**: `/api/marcador/**` ya está declarada en la configuración, pero su servicio destino (`marcador`) todavía no existe.

## Cómo se prueba
Las pruebas cubren el contexto, las rutas configuradas en `RouteLocator`, el filtro de autenticación y el cliente hacia `identidad`. Desde `servicios/gateway/`:

```bash
mvn test
```

Sin Java ni Maven instalados, con Docker:

```bash
docker build --target construccion -t distrinubs-gateway-test .
docker run --rm distrinubs-gateway-test mvn -B -q test
```
