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
| 2 | Gateway en 2 réplicas, nginx delante y Eureka; el `docker-compose.yml` de la etapa 2 | Joshua (gateway) y Emiliano (nginx, Eureka y Compose) |
| 3 | Autenticación: servicio `identidad` (inicio de sesión, token, bloqueo), validación en el gateway y en la subida, y pantalla de inicio de sesión | Enrique (servicio y validación) y Diego (pantalla) |
| 4 | Demo de fallas: matar un worker a media evaluación, apagar una réplica del gateway y ver salir de Eureka a una instancia caída. **Los pasos 1, 2 y 4 son el mínimo** | Diego (el script), con todos |
| 5 | `marcador` aparte, con Redis y mensajes de resultado | Joshua y Emiliano |
| 6 | Circuit breaker y timeouts en las rutas del gateway; cola de mensajes muertos con tope de reintentos | Emiliano (circuit breaker, timeouts y cola de mensajes muertos) y Enrique (productor de la cola) |
| 7 | Observabilidad mínima: id de correlación en los logs, Actuator y una vista del estado de los servicios | Diego, con cada dueño en su servicio |
| 8 | Lenguajes: C++, C, Java y JavaScript, además de Python 3 | Emiliano, con Diego en el front |
| 9 | Si alcanza: sandbox por envío, límite de memoria, evaluación final, interfaz de organizador y saga de cierre | Emiliano y Joshua |

Opcionales, solo con lo anterior corriendo: editor de código al costado del enunciado, estilo LeetCode (junto con el front en React), Kubernetes, MongoDB, cadena de hashes, Prometheus, Grafana y Jaeger.

## Quién hace qué

Propuesta del 14/11 para confirmar en la reunión del 15/11. Cada quien es dueño de su área completa (código, pruebas, Dockerfile y su parte del README), defiende un tema en la defensa y hace sus commits con su cuenta. Si algo no convence, se cambia esta tabla antes de empezar.

| Quién | Área | Tema que defiende |
|---|---|---|
| Emiliano | `evaluacion`, nginx, Eureka, el `docker-compose.yml`, el circuit breaker y la integración | Fallas, reentrega, directorio, balanceador y datos |
| Enrique | `envios`, mensajería, `identidad` y el filtro del token | Operaciones entre servicios y autenticación |
| Joshua | `concursos`, `gateway` y `marcador`, con Redis | Gateway y caché |
| Diego | El front, la demo de fallas y la observabilidad mínima | Qué ve el usuario cuando algo falla |

### Emiliano: `evaluacion` e integración

Primero (paso 1):
- [x] Pasar el worker y el ejecutor de Python a un servicio propio, con su Dockerfile (`servicios/evaluacion`).
- [x] Consumir de RabbitMQ, con confirmación solo al guardar el resultado, y varios workers con `--scale`. Manda el lease: el worker que recibe el mensaje de un worker caído espera a que venza su lease. Probado con RabbitMQ y PostgreSQL reales: al matar un worker a media evaluación, otro la retoma como toma 2 en unos 21 s y la evalúa una sola vez.
- [x] Conservar el lease, el resultado único y el tope de 3 tomas con `ERROR_SERVIDOR`.
- [x] Probarlo de punta a punta con `envios`. Comprobado el 28/11 con el Compose de la etapa 2: `scripts/demo.py` pasa completo y, en el caso 1 de `scripts/demo_fallas.py`, al matar el contenedor del worker otro retoma el envío como toma 2 y lo deja en `ACCEPTED`.
- [x] Armar el `docker-compose.yml` de la etapa 2 (paso 2) y quedarse con él: levanta nginx, el gateway en 2 réplicas, Eureka, `identidad`, `concursos`, `envios`, `evaluacion` en 2 contenedores, RabbitMQ y PostgreSQL. Las variables obligatorias van en un `.env`, con `.env.ejemplo` de plantilla.
- [x] nginx con `least_conn` delante de las réplicas del gateway y Eureka en su contenedor (`servicios/eureka`, paso 2). Comprobado el 28/11 con `scripts/demo_fallas.py`: con una réplica del gateway apagada la entrada atendió 8 de 8 peticiones, y una instancia muerta salió del directorio en 34 s.

Después:
- [x] Circuit breaker y timeouts en las rutas del gateway (paso 6). Un circuito por servicio destino con Resilience4j: ventana de 10 llamadas, abre con 50 % de fallas y dura abierto 10 s; tiempos de espera de 2 s para conectar y 7 s para responder. Medido en el Compose con `concursos` muerto: 2 s por petición hasta que abrió (8 peticiones entre las dos réplicas), 7 ms con el circuito abierto y 200 de nuevo a los 11 s de arrancar `concursos`. Las pruebas del gateway pasan (17). Falta la revisión de Joshua.
- [ ] Publicar el resultado de cada envío para `marcador` (paso 5).
- [ ] Cola de mensajes muertos y tope de reintentos, del lado del consumidor, junto con Enrique (paso 6).
- [ ] Lenguajes (paso 8): compiladores en la imagen, paso de compilación con su propio tope, convenciones (en Java la clase se llama `Main`) y un código de ejemplo por lenguaje.
- [ ] Verificar y documentar dos riesgos: que el código del alumno lea `/proc/<pid>/environ` del proceso Java, y que un `#include` filtre archivos del servidor en el mensaje de compilación.
- [ ] Si alcanza (paso 9): sandbox por envío, límite de memoria y evaluación final.
- [ ] Integración: README de `main`, consolidar el reporte.

### Enrique: `envios`, mensajería e `identidad`

Primero (paso 1):
- [x] `envios` como servicio (`servicios/envios/`), con las mismas rutas `POST /api/envios` y `GET /api/envios/{id}`.
- [x] Publicar en RabbitMQ después de guardar y cubrir caídas entre guardar y publicar mediante barrido periódico (`BarridoRepublicacionEnvios`) que solo republica envíos nunca publicados tras el margen de gracia o ya publicados que superen la espera máxima en cola, con tope de publicaciones, exclusión de tomas agotadas y `SKIP LOCKED` para no duplicar entre réplicas.
- [x] Mantener la idempotencia por `intentoId`.

Autenticación (paso 3):
- [x] Servicio `identidad` (`servicios/identidad/`): inicio de sesión con contraseña guardada con hash BCrypt, token JWT firmado con vencimiento, bloqueo y revocación de cuentas/tokens (`tokens_revocados`), y los roles alumno, organizador y administrador.
- [x] En `envios`: rechazar la subida sin token válido (falla cerrada ante caída de `identidad`), rechazar `alumnoId` ajeno para cualquier rol y dejar que cada usuario consulte según su dueño o supervisión. Con eso termina la suplantación por `alumnoId`.
- [x] Filtro que valida el token, ya integrado en `servicios/gateway/`: las rutas públicas son una lista exacta, rechaza las rutas sin normalizar, aísla los errores de `identidad` respecto a los backends aguas abajo y propaga `X-Usuario-Id`, `X-Usuario-Login` y `X-Usuario-Rol`. El gateway ya manda `/api/cuentas-demo` a `identidad`, que lista solo las cuentas de alumno no bloqueadas. Está cubierto con las pruebas de cada servicio.
- [x] Probar la autenticación de punta a punta con el Compose (front, gateway, `identidad` y `envios`). Comprobado el 28/11: el front inicia sesión, manda el token, sube un envío por el gateway y muestra el veredicto; sin token, `envios` y las rutas de administración responden 401.
- [ ] Acordar con Diego los claims del token y el orden de integración con su pantalla de inicio de sesión y `scripts/demo.py` (ya acepta `--contrasena`).

Después:
- [ ] Cola de mensajes muertos (`envios.evaluacion.dlq`) con tope de entregas (cola quorum, `x-delivery-limit` 3): lado del productor listo en `servicios/envios/`; falta el consumidor de Emiliano y probarlo de punta a punta (paso 6).
- [ ] Acordar con Emiliano quién aplica las migraciones en Compose. *(En el Compose de la etapa 2 cada servicio aplica las suyas al arrancar y no hay un contenedor aparte que migre. Arranca bien desde una base vacía; falta que el equipo lo confirme como decisión).*
- [ ] Redis para sesiones y revocación, si hace falta.

### Joshua: `concursos`, `gateway` y `marcador`

Primero (paso 1):
- [x] `concursos` como servicio, con su siembra y las rutas actuales. En este paso conserva la tabla de posiciones y las cuentas de demo.

Entrada (paso 2, puede empezar ya contra el monolito):
- [x] Probar el primer día qué versión de Spring Cloud funciona con Spring Boot 3.3.13.
- [x] Gateway en 2 réplicas, con rutas por prefijo y sin lógica de negocio.
- [x] Registrar el gateway en Eureka y dejar listas las rutas para que nginx y el circuit breaker de Emiliano se conecten.

Después:
- [ ] `marcador` aparte (paso 5): consume los resultados, guarda su propio modelo y calcula la tabla.
- [ ] Redis delante del marcador: TTL menor que el atraso tolerado de 5 minutos, invalidación al llegar un resultado y protección contra una expiración masiva.
- [ ] Repetir la prueba de carga (`scripts/carga.py`) contra los servicios y comparar con la del monolito.
- [ ] Si alcanza (paso 9): dificultad por resueltos, desempate completo, interfaz de organizador y saga de cierre del concurso.

### Diego: el front, la demo de fallas y la observabilidad

Primero:
- [ ] Adaptar el front para que llame a todo por el gateway (las rutas públicas no cambian) y mostrar un error claro cuando un servicio no responde. *(Parcial: el front muestra un aviso claro en 502/503/504, timeout y falla de red, con el id de correlación de esa petición; comprobado el 28/11 con el Compose de la etapa 2 que, entrando por nginx y el gateway, el front carga, inicia sesión, sube un envío y muestra el veredicto y la tabla. Falta comprobar el aviso de error con un servicio caído).*
- [x] Pantalla de inicio de sesión (paso 3), con el token en cada petición. Comprobado el 28/11 con el Compose de la etapa 2: la pantalla inicia sesión contra `identidad` y manda `Authorization: Bearer` en cada petición.

Después:
- [x] Script de la demo de fallas (paso 4): los tres casos del paso, con lo que se ve en cada uno. El caso 1 solo pasa si el lease muestra la reentrega y los casos sin sus piezas se reportan como "no disponible". Comprobado el 28/11 con el Compose de la etapa 2: los tres casos pasan.
- [ ] Observabilidad mínima (paso 7): Actuator, id de correlación en los logs y una vista del estado de los servicios. *(Parcial: `FiltroCorrelacion`, Actuator y `/api/observabilidad/estado` listos en el monolito; la vista responde en 6 s aunque la base de datos o un servicio no contesten, y Actuator ya no muestra detalles sin autenticación. Los servicios sin URL de health configurada reportan `NO_DISPONIBLE` y el estado global queda `DEGRADADO`. El Compose de la etapa 2 ya configura esas URL y la vista muestra cada servicio; `marcador` sale `NO_DISPONIBLE` porque todavía no existe. Falta el id de correlación en los logs de cada servicio separado).*
- [ ] Selector de lenguaje en el front (paso 8). *(Parcial: selector y plantillas en el front listos; solo Python 3 está habilitado y los demás aparecen como "próximamente", porque `envios` y `evaluacion` todavía solo aceptan `PYTHON3`).*
- [ ] Opcional: editor de código al costado del enunciado, junto con el front en React. *(Parcial: editor al costado del enunciado agregado en el front HTML/JS; falta decidir si se migra a React).*

## Contratos que se fijan el primer día

Son los puntos donde el trabajo de uno depende del de otro. Se acuerdan el 15/11, antes de escribir código.

| Contrato | Entre quién | Propuesta |
|---|---|---|
| Mensaje del envío a evaluar | Enrique y Emiliano | Una cola de RabbitMQ; el cuerpo lleva el `envioId`. El estado sigue en PostgreSQL; cola quorum con `x-delivery-limit` 3 y DLX hacia `envios.evaluacion.dlq` (propuesta, por confirmar con Emiliano) |
| Mensaje del resultado | Emiliano y Joshua | `envioId`, `cuentaId`, `problemaId`, `veredicto` y `llegadaEn` |
| Token | Enrique, Joshua y Diego | Propuesta de Enrique, por confirmar con Joshua y Diego: JWT HS256 en `Authorization: Bearer`, emitido por `POST /api/auth/login`, con `sub` (id de la cuenta, como texto), `usuario`, `nombre`, `rol`, `jti`, `iat`, `iatMs` y `exp`. Se valida en el gateway y en `envios`, consultando `GET /api/auth/validar` en `identidad`. Secreto `JWT_SECRETO`, sin valor por omisión |
| Rutas públicas | Joshua y todos | No cambian: `/api/envios/**`, `/api/concursos/**` y `/api/cuentas-demo`. Así el front y el demo siguen igual |
| Datos entre servicios | Los cuatro | Propuesta de Enrique (bitácora 25), por confirmar con los cuatro: una instancia y esquema `public`, un solo escritor por tabla; cada servicio aplica las mismas migraciones con Flyway. Falta decidir en Compose quién migra |
| Quién valida la lista de lenguajes | Emiliano y Enrique | Antes del paso 8 |

## Cómo trabajamos

- Pull requests chicos y frecuentes hacia `02-separacion`. Cada PR lo revisa otra persona, en este orden: Enrique revisa a Emiliano, Joshua a Enrique, Diego a Joshua y Emiliano a Diego. El filtro del token que Enrique agrega al gateway lo revisa Joshua, que es su dueño. Así cada quien conoce la pieza de otro.
- Cada servicio trae su README (qué hace, qué no hace y qué falta), su Dockerfile y sus pruebas.
- Un cambio de enfoque entra primero a la bitácora del PRD.
- Si alguien se atora más de un día, lo dice ese día, no el 24/11.

## Reporte y pitch

| Parte del reporte | Quién |
|---|---|
| Portada y problema | Emiliano |
| Diagrama funcional | Diego |
| Diagrama de arquitectura | Diego, con Joshua y Emiliano revisando el gateway, Eureka y nginx |
| Decisiones (al menos tres, con lo que se dejó atrás) | Cada quien escribe una de su área |
| Manejo de fallas | Emiliano (lease y reentrega) y Diego (demo) |
| Cómo levantarlo y probarlo | Diego |
| Qué falta y qué harían distinto | Enrique |
| Quién hizo qué | Cada quien su parte; Emiliano consolida |

En el pitch cada quien presenta su área en 2 o 3 minutos: Emiliano, el problema, la evaluación, el directorio y el balanceador; Enrique, envíos, mensajería y autenticación; Joshua, el gateway y la caché del marcador; Diego, el front y la demo de fallas.

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
