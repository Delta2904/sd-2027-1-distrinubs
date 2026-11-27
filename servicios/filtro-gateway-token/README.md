# Filtro de token para Spring Cloud Gateway

Módulo con el `GlobalFilter` de autenticación JWT y validación de revocación/bloqueo contra el servicio `identidad`, listo para integrarse mediante pull request en el servicio `gateway` de Joshua una vez que exista en la rama `02-separacion`.

## Qué hace

- **Rutas públicas sin token:** deja pasar `POST /api/auth/login`, `GET /api/cuentas-demo`, `GET /actuator/health`, `GET /api/concursos/activo`, `GET /api/concursos/activo/tabla`, peticiones `OPTIONS` y archivos estáticos del front (`/`, `/index.html`, `/app.js`, `/estilos.css`), limpiando cualquier cabecera `X-Usuario-*` enviada por el cliente.
- **Validación en rutas protegidas:** para `/api/envios/**`, `/api/identidad/**`, `/api/auth/yo`, `/api/auth/logout` y cualquier otro endpoint privado, exige `Authorization: Bearer <token>`, verifica localmente firma HMAC-SHA256 y expiración (`VerificadorJwtGateway`), y confirma contra `GET /api/auth/validar` en `identidad` que el token no haya sido revocado ni la cuenta bloqueada.
- **Formato esperado del token:** JWT firmado con HS256 cuyos claims incluyen `sub` (identificador numérico de la cuenta en texto), `usuario`, `nombre`, `rol`, `jti`, `iat`, `iatMs` y `exp`.
- **Falla cerrada:** si `identidad` no responde o vence el timeout, rechaza con `503 Service Unavailable`.
- **Propagación segura:** reemplaza cualquier cabecera `X-Usuario-Id`, `X-Usuario-Sub` y `X-Usuario-Rol` del cliente por los valores verificados de la sesión.

## Cómo integrarlo al `gateway`

1. Agregar la dependencia `mx.distrinubs:distrinubs-filtro-gateway-token:0.2.0` o copiar el paquete `mx.distrinubs.gateway.autenticacion` en el servicio `gateway`.
2. Configurar en `application.yml` del gateway (`JWT_SECRETO` es obligatoria, sin valor por omisión; el servicio no arranca si falta):
   ```yaml
   distrinubs:
     autenticacion:
       jwt-secreto: ${JWT_SECRETO}
       identidad-url: ${IDENTIDAD_URL:http://identidad:8083}
       timeout-ms: ${IDENTIDAD_TIMEOUT_MS:2000}
   ```

## Cómo correr las pruebas

Desde `servicios/filtro-gateway-token/`:

```bash
docker build --target construccion -t distrinubs-filtro-test .
docker run --rm distrinubs-filtro-test mvn -B -q test
```
