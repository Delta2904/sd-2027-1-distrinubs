# Servicio `identidad`

Microservicio encargado de la autenticación, emisión y validación de tokens JWT firmados (HMAC-SHA256), gestión de roles y bloqueo o revocación de cuentas y sesiones en Distrinubs.

## Qué hace

- **Inicio de sesión (`POST /api/auth/login`):** verifica credenciales contra contraseñas almacenadas con hash BCrypt y emite un token JWT firmado con `sub` (identificador numérico de la cuenta en texto), `usuario`, `nombre`, `rol`, `jti`, `iat`, `iatMs` y `exp`. Las credenciales inválidas responden `401 Unauthorized` sin revelar si la cuenta existe; las cuentas bloqueadas responden `403 Forbidden`.
- **Validación de sesión y revocación (`GET /api/auth/yo`, `GET /api/auth/validar`):** verifica la firma y expiración del token y consulta PostgreSQL para comprobar que la cuenta siga activa (si está bloqueada responde `403 Forbidden`), que el token sea posterior a `revocada_desde` y que su `jti` no aparezca en `tokens_revocados` (`401 Unauthorized` si está revocado o inválido).
- **Cierre de sesión (`POST /api/auth/logout`):** registra el `jti` del token en `tokens_revocados` para invalidarlo antes de su expiración natural.
- **Administración de cuentas (`/api/identidad/cuentas/**`):** endpoints protegidos para rol `ADMINISTRADOR` que permiten listar cuentas, crear cuentas nuevas (normalizando el nombre de usuario a minúsculas, con unicidad sin distinguir mayúsculas/minúsculas), bloquear/desbloquear cuentas (el bloqueo revoca inmediatamente tokens previos), revocar todos los tokens activos de una cuenta y cambiar roles (`ALUMNO`, `ORGANIZADOR`, `ADMINISTRADOR`).
- **Cuentas de demo (`GET /api/cuentas-demo`):** mantiene compatibilidad con el selector de cuentas de alumno no bloqueadas.
- **Siembra idempotente sin pisar cambios:** al arrancar aplica migraciones Flyway (tolerando migraciones ajenas) y siembra `alumno1`..`alumno5`, `organizador1` y `admin` (y cuentas `carga001`..`cargaN` si se configura `SIEMBRA_CUENTAS_CARGA > 0`). Si la cuenta ya existe, no sobrescribe su rol ni una contraseña previamente definida.
- **Registro en directorio y salud:** cliente Eureka configurable (`EUREKA_ACTIVO`) y Spring Boot Actuator (`/actuator/health`).

## Qué no hace y qué falta

- **No levanta su propio `docker-compose.yml`:** la integración en el Compose general de la etapa queda pendiente de coordinación con el resto de los servicios, incluido acordar qué servicio o contenedor aplica las migraciones.
- **Sin caché en Redis:** cada validación contra `/api/auth/validar` consulta PostgreSQL; agregar Redis para sesiones y revocación queda pendiente solo si la carga lo requiere.
- **Sin auto-registro público de alumnos:** las cuentas nuevas las crea un administrador por `POST /api/identidad/cuentas` o la siembra al iniciar.

## Propiedad de datos

`identidad` es el único dueño de escritura de las tablas `cuentas` y `tokens_revocados`.

## Variables de entorno

| Variable | Por omisión | Descripción |
|---|---|---|
| `IDENTIDAD_PUERTO` | `8083` | Puerto HTTP del servicio |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | `localhost`, `5432`, `distrinubs` | Conexión a PostgreSQL |
| `JWT_SECRETO` | *(obligatoria, sin omisión)* | Secreto compartido HMAC-SHA256; el servicio no arranca si falta |
| `JWT_DURACION_SEGUNDOS` | `86400` | Vigencia de los tokens en segundos |
| `SIEMBRA_ACTIVA` | `true` | Ejecuta la siembra inicial al arrancar |
| `SIEMBRA_CUENTAS_CARGA` | `0` | Cantidad de cuentas extra `cargaNNN` |
| `SIEMBRA_CONTRASENA_DEMO` | *(obligatoria, sin omisión)* | Contraseña inicial para cuentas sembradas; la siembra falla al arrancar si falta |
| `EUREKA_ACTIVO` | `false` | Habilita el registro en Eureka |
| `EUREKA_URL` | `http://eureka:8761/eureka/` | URL del servidor Eureka |

## Cómo construir y correr pruebas

Desde `servicios/identidad/` (sin necesidad de Java o Maven instalados localmente):

```bash
docker build --target construccion -t distrinubs-identidad-test .
docker run --rm distrinubs-identidad-test mvn -B -q test
```
