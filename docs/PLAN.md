# Plan

Dos etapas: terminar el monolito y separar todo. Aquí está el orden, quién hace qué y las fechas. Las decisiones están en el [PRD](PRD.md) y las reglas para agentes en [CLAUDE.md](../CLAUDE.md).

**Entrega: sábado 28/11, 07:00.** Código y `docs/reporte-final.pdf` en el repo. Ese día, pitch con demo y defensa. El examen escrito y la entrevista son individuales, el sábado 21/11.

## Ramas

- `main`: README, PRD y reporte final.
- `01-monolito`: etapa 1, cerrada. Queda de respaldo: si el 25/11 no corren los pasos 1, 2 y 4 de la etapa 2, se entrega esta.
- `02-separacion`: etapa 2.

Cada quien trabaja en su rama personal (`02-separacion-<nombre>`) y la integra con un pull request hacia la rama de la etapa. Los puntos de retorno son tags, no ramas.

## Etapa 1: terminar el monolito (cerrada)

Verificado el 14/11: 23 pruebas pasan, el arranque desde cero siembra solo, `scripts/demo.py` pasa y la prueba de carga da 44 s de retraso máximo con 2 workers (el límite es 300 s).

Cierre en git:

- [x] Commit de lo pendiente (incluido el arreglo de codificación de `scripts/demo.py`).
- [x] Pull request a `01-monolito` y tag `01-cierre`.
- [x] Crear `02-separacion` a partir de `01-monolito`.

Pasa a la etapa 2: autenticación, más lenguajes, sandbox, límite de memoria, evaluación final e interfaz de organizador.

## Etapa 2: separar todo

Pasos en orden. Cada uno deja el sistema funcionando y se comprueba con `scripts/demo.py`. Los pasos 1, 2 y 3 se trabajan en paralelo, cada quien en su parte. **El 24/11 lo que no corre de punta a punta se declara fuera** en el README y el reporte.

| Paso | Qué | Quién |
|---|---|---|
| 1 | `envios`, `evaluacion` y `concursos` como servicios en Compose, con RabbitMQ entre envíos y evaluación y varios workers | Enrique, Emiliano, Joshua |
| 2 | Gateway en 2 réplicas, nginx delante y Eureka | Diego |
| 3 | Autenticación: servicio `identidad` (inicio de sesión, token, bloqueo), validación en el gateway y en la subida, y pantalla de inicio de sesión | Enrique y Diego |
| 4 | Demo de fallas: matar un worker a media evaluación, apagar una réplica del gateway y ver salir de Eureka a una instancia caída. **Los pasos 1, 2 y 4 son el mínimo** | Diego, con todos |
| 5 | `marcador` aparte, con Redis y mensajes de resultado | Joshua y Emiliano |
| 6 | Circuit breaker y timeouts en las rutas del gateway; cola de mensajes muertos con tope de reintentos | Diego y Enrique |
| 7 | Observabilidad mínima: id de correlación en los logs, Actuator y una vista del estado de los servicios | Diego |
| 8 | Lenguajes: C++, C, Java y JavaScript, además de Python 3 | Emiliano, con Diego en el front |
| 9 | Si alcanza: sandbox por envío, límite de memoria, evaluación final, interfaz de organizador y saga de cierre | Emiliano y Joshua |

Opcionales, solo con lo anterior corriendo: editor de código al costado del enunciado, estilo LeetCode (junto con el front en React), Kubernetes, MongoDB, cadena de hashes, Prometheus, Grafana y Jaeger.

## Quién hace qué

Propuesta del 14/11 para confirmar en la reunión del 15/11. Cada quien es dueño de su área completa (código, pruebas, Dockerfile y su parte del README), defiende un tema en la defensa y hace sus commits con su cuenta. Si algo no convence, se cambia esta tabla antes de empezar.

| Quién | Área | Tema que defiende |
|---|---|---|
| Emiliano | `evaluacion` y la integración | Fallas y reentrega |
| Enrique | `envios`, mensajería e `identidad` | Operaciones entre servicios |
| Joshua | `concursos` y `marcador`, con Redis | Datos y consistencia |
| Diego | `gateway`, nginx, Eureka, el front y la demo de fallas | Gateway, directorio y balanceador |

### Emiliano: `evaluacion` e integración

Primero (paso 1):
- [ ] Pasar el worker y el ejecutor de Python a un servicio propio, con su Dockerfile.
- [ ] Consumir de RabbitMQ, con confirmación solo al guardar el resultado, y varios workers con `--scale`.
- [ ] Conservar el lease, el resultado único y el tope de 3 tomas con `ERROR_SERVIDOR`.
- [ ] Armar con Diego el `docker-compose.yml` de la etapa 2.

Después:
- [ ] Publicar el resultado de cada envío para `marcador` (paso 5).
- [ ] Lenguajes (paso 8): compiladores en la imagen, paso de compilación con su propio tope, convenciones (en Java la clase se llama `Main`) y un código de ejemplo por lenguaje.
- [ ] Verificar y documentar dos riesgos: que el código del alumno lea `/proc/<pid>/environ` del proceso Java, y que un `#include` filtre archivos del servidor en el mensaje de compilación.
- [ ] Si alcanza (paso 9): sandbox por envío, límite de memoria y evaluación final.
- [ ] Integración: README de `main`, consolidar el reporte.

### Enrique: `envios`, mensajería e `identidad`

Primero (paso 1):
- [ ] `envios` como servicio, con las mismas rutas `POST /api/envios` y `GET /api/envios/{id}`.
- [ ] Publicar en RabbitMQ después de guardar. Falta decidir qué pasa si el servicio cae entre guardar y publicar (barrido u outbox, pregunta abierta del PRD).
- [ ] Mantener la idempotencia por `intentoId`.

Autenticación (paso 3):
- [ ] Servicio `identidad`: inicio de sesión con contraseña guardada con hash, token firmado con vencimiento, bloqueo y revocación de cuentas, y los roles alumno, organizador y administrador.
- [ ] En `envios`: rechazar la subida sin token válido y dejar que cada alumno envíe y consulte solo lo suyo. Con eso termina la suplantación por `alumnoId`.

Después:
- [ ] Cola de mensajes muertos y tope de reintentos junto con Diego (paso 6).
- [ ] Redis para sesiones y revocación, si hace falta.

### Joshua: `concursos` y `marcador`

Primero (paso 1):
- [ ] `concursos` como servicio, con su siembra y las rutas actuales. En este paso conserva la tabla de posiciones y las cuentas de demo.

Después:
- [ ] `marcador` aparte (paso 5): consume los resultados, guarda su propio modelo y calcula la tabla.
- [ ] Redis delante del marcador: TTL menor que el atraso tolerado de 5 minutos, invalidación al llegar un resultado y protección contra una expiración masiva.
- [ ] Repetir la prueba de carga (`scripts/carga.py`) contra los servicios y comparar con la del monolito.
- [ ] Si alcanza (paso 9): dificultad por resueltos, desempate completo, interfaz de organizador y saga de cierre del concurso.

### Diego: `gateway`, infraestructura y front

Primero (paso 2, puede empezar ya contra el monolito):
- [ ] Probar el primer día qué versión de Spring Cloud funciona con Spring Boot 3.3.13.
- [ ] Gateway en 2 réplicas, con rutas por prefijo y sin lógica de negocio.
- [ ] nginx con `least_conn` delante y Eureka. Cada dueño registra su servicio.
- [ ] Quedarse con el `docker-compose.yml`: los demás agregan su servicio con pull requests chicos.

Después:
- [ ] Filtro del token en el gateway y pantalla de inicio de sesión (paso 3).
- [ ] Script de la demo de fallas (paso 4).
- [ ] Circuit breaker y timeouts en las rutas (paso 6); id de correlación y vista de estado (paso 7).
- [ ] Selector de lenguaje en el front (paso 8).

## Contratos que se fijan el primer día

Son los puntos donde el trabajo de uno depende del de otro. Se acuerdan el 15/11, antes de escribir código.

| Contrato | Entre quién | Propuesta |
|---|---|---|
| Mensaje del envío a evaluar | Enrique y Emiliano | Una cola de RabbitMQ; el cuerpo lleva el `envioId`. El estado sigue en PostgreSQL |
| Mensaje del resultado | Emiliano y Joshua | `envioId`, `cuentaId`, `problemaId`, `veredicto` y `llegadaEn` |
| Token | Enrique y Diego | Token firmado con `sub` (la cuenta), `rol` y `exp`, en `Authorization: Bearer`; `POST /api/auth/login`. Dónde se valida está abierto en el PRD |
| Rutas públicas | Diego y todos | No cambian: `/api/envios/**`, `/api/concursos/**` y `/api/cuentas-demo`. Así el front y el demo siguen igual |
| Datos entre servicios | Los cuatro | Pregunta abierta del PRD (una base con un esquema por servicio, o una base por servicio) |
| Quién valida la lista de lenguajes | Emiliano y Enrique | Antes del paso 8 |

## Cómo trabajamos

- Pull requests chicos y frecuentes hacia `02-separacion`. Cada PR lo revisa otra persona, en este orden: Enrique revisa a Emiliano, Joshua a Enrique, Diego a Joshua y Emiliano a Diego. Así cada quien conoce la pieza de otro.
- Cada servicio trae su README (qué hace, qué no hace y qué falta), su Dockerfile y sus pruebas.
- Un cambio de enfoque entra primero a la bitácora del PRD.
- Si alguien se atora más de un día, lo dice ese día, no el 24/11.

## Reporte y pitch

| Parte del reporte | Quién |
|---|---|
| Portada y problema | Emiliano |
| Diagrama funcional | Joshua |
| Diagrama de arquitectura | Diego |
| Decisiones (al menos tres, con lo que se dejó atrás) | Cada quien escribe una de su área |
| Manejo de fallas | Diego (demo) y Emiliano (lease y reentrega) |
| Cómo levantarlo y probarlo | Diego |
| Qué falta y qué harían distinto | Enrique |
| Quién hizo qué | Cada quien su parte; Emiliano consolida |

En el pitch cada quien presenta su área en 2 o 3 minutos: Emiliano, el problema y la evaluación; Enrique, envíos, mensajería y autenticación; Joshua, el marcador y la caché; Diego, el gateway, el directorio, el balanceador y la demo de fallas.

## Calendario

| Fecha | Qué |
|---|---|
| Sáb 14 y dom 15 | Cerrar la etapa 1 en git y crear `02-separacion`. Reunión de una hora: confirmar el reparto y los contratos |
| Lun 16 a vie 20 | Pasos 1, 2 y 3 en paralelo; el paso 4 el viernes |
| Sáb 21 | Examen escrito y entrevista; sin trabajo del proyecto |
| Dom 22 a mar 24 | Pasos 5 a 8. El martes 24, corte |
| Mié 25 | Se congela el código con un tag y se prueba `docker compose up` desde cero |
| Mié 25 a vie 27 | Reporte PDF, diagramas y ensayo del pitch y de la demo de fallas |
| Sáb 28, 07:00 | Entrega; después, la presentación |

## Qué pide la rúbrica

- **Prototipo, 30 %:** se levanta con el README sin pasos escondidos, y se ve cómo entran las peticiones, cómo se comunican las partes, dónde viven los datos y qué pasa si algo falla.
- **Reporte, 30 %:** portada, problema, diagrama funcional, diagrama de arquitectura, al menos tres decisiones con lo que se dejó atrás, manejo de fallas, cómo levantarlo, qué falta y quién hizo qué.
- **Pitch, 20 %:** problema, solución y demo; todos presentan una parte.
- **Defensa y participación, 20 %:** preguntas a cualquiera sobre gateway, directorio, balanceador, operaciones entre servicios y fallas; y el historial de commits de cada quien.
