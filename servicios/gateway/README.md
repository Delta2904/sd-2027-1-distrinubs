# Servicio Gateway (Spring Cloud Gateway)

API Gateway de Distrinubs para la etapa `02-separacion`.

## Responsabilidades
- Única puerta de entrada para clientes y front.
- Reenvío reactivo y balanceo dinámico hacia microservicios registrados en Eureka (`lb://<servicio>`).
- Enrutamiento por prefijos:
  - `/api/concursos/**` -> `concursos`
  - `/api/cuentas-demo/**` -> `concursos` (en el paso 1 conserva cuentas de demo)
  - `/api/envios/**` -> `envios`
  - `/api/auth/**` -> `identidad`
  - `/api/identidad/**` -> `identidad`
  - `/api/marcador/**` -> `marcador` (cuando se separe)
- **Filtro global de autenticación (`FiltroAutenticacionGateway`):**
  - **Rutas públicas sin token:** `POST /api/auth/login`, `GET /api/cuentas-demo`, `GET /api/concursos/activo`, `GET /api/concursos/activo/tabla`, `GET /api/observabilidad/**`, `GET /actuator/health/**`, `GET /actuator/info`, peticiones `OPTIONS` y recursos estáticos (`/`, `/index.html`, `/app.js`, `/estilos.css`, `/favicon.ico`).
  - **Rutas protegidas:** exige `Authorization: Bearer <token>` (HS256 con `sub` igual al id de cuenta en texto, `usuario`, `nombre`, `rol`, `jti`, `iat`, `iatMs`, `exp`), verifica localmente firma y vencimiento (`VerificadorJwtGateway`) y confirma revocación/bloqueo contra `identidad` (`GET /api/auth/validar`). Si `identidad` cae o expira el timeout, responde `503 Service Unavailable` (falla cerrada); los errores posteriores de otros servicios aguas abajo no se enmascaran como falla de `identidad`.
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
- `RUTA_CUENTAS_DEMO_URI`: URI de reenvío para cuentas demo (por omisión: `lb://concursos`).
- `RUTA_ENVIOS_URI`: URI de reenvío para envíos (por omisión: `lb://envios`).
- `RUTA_AUTH_URI`: URI de reenvío para autenticación (por omisión: `lb://identidad`).
- `RUTA_IDENTIDAD_URI`: URI de reenvío para administración de cuentas (por omisión: `lb://identidad`).
- `RUTA_MARCADOR_URI`: URI de reenvío para marcador (por omisión: `lb://marcador`).

## Cómo construir y correr pruebas

Desde `servicios/gateway/`:

```bash
docker build --target construccion -t distrinubs-gateway-test .
docker run --rm distrinubs-gateway-test mvn -B -q test
```
