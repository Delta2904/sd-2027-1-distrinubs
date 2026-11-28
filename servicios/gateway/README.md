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
  - `/api/marcador/**` -> `marcador` (cuando se separe)
- Sin estado ni lógica de negocio: preparado para correr en múltiples réplicas (`--scale gateway=2`) detrás del balanceador nginx de Emiliano.

## Variables de entorno
- `PORT`: Puerto HTTP del gateway (por omisión: `8080`).
- `EUREKA_SERVER_URL`: URL del servidor Eureka (por omisión: `http://localhost:8761/eureka/`).
- `EUREKA_CLIENT_ENABLED`: Habilita registro en Eureka (por omisión: `true`).
- `RUTA_CONCURSOS_URI`: URI de reenvío para concursos (por omisión: `lb://concursos`).
- `RUTA_CUENTAS_DEMO_URI`: URI de reenvío para cuentas demo (por omisión: `lb://concursos`).
- `RUTA_ENVIOS_URI`: URI de reenvío para envíos (por omisión: `lb://envios`).
- `RUTA_AUTH_URI`: URI de reenvío para autenticación (por omisión: `lb://identidad`).
- `RUTA_MARCADOR_URI`: URI de reenvío para marcador (por omisión: `lb://marcador`).

## Qué no hace este servicio y qué falta
- **No contiene lógica de negocio ni acceso a base de datos**: únicamente enruta y balancea peticiones HTTP hacia los microservicios registrados en Eureka.
- **No valida autenticación ni autorización**: la verificación de credenciales o tokens se delegará al servicio `identidad` (o a filtros de seguridad en etapas posteriores).
- **Rutas pendientes de respaldo en esta etapa**: `/api/envios/**`, `/api/auth/**` y `/api/marcador/**` ya están declaradas en la configuración, pero sus servicios destino (`envios`, `identidad`, `marcador`) se irán separando e integrando conforme avance la etapa 2.

## Cómo se prueba
Ejecuta las pruebas del contexto y verificación de rutas configuradas en `RouteLocator`:
```bash
mvn test
```
