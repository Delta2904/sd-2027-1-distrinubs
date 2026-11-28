# Distrinubs · etapa 02-separacion

Juez en línea para práctica y concursos de programación competitiva. Proyecto final de la asignatura Sistemas Distribuidos (FI-UNAM, 2027-1).

Este es el README de la rama `02-separacion`: dice qué hace la etapa 2, qué no hace, qué queda pendiente, cómo se levanta y qué pasa si algo falla. El de `main` presenta el proyecto y el de `01-monolito` describe la etapa 1.

## Qué es

Los alumnos envían una solución a un problema. El sistema la evalúa contra casos de prueba, con límite de tiempo, y publica el veredicto: Accepted, Wrong Answer, Time Limit Exceeded, Runtime Error o error de compilación. Con los envíos construye una tabla de posiciones. La tabla que se ve durante el concurso es provisional; la evaluación final al cierre no entró en esta etapa.

La etapa 2 separa el monolito en servicios: `identidad`, `envios`, `evaluacion` y `concursos`, detrás de un gateway en 2 réplicas, con nginx como entrada única, Eureka como directorio y RabbitMQ entre envíos y evaluación. Todo corre en contenedores con Docker Compose, en una computadora, sobre una base PostgreSQL.

## Quiénes somos

| Integrante | GitHub | Parte principal en la etapa 2 |
|---|---|---|
| Calderón Gutiérrez Victor Emiliano | [@Delta2904](https://github.com/Delta2904) | `evaluacion`, nginx, Eureka, el `docker-compose.yml` y el circuit breaker. Coordina el proyecto |
| Contreras del Ángel Diego Adrián | [@diegocontrras](https://github.com/diegocontrras) | El front con inicio de sesión, la demo de fallas y la vista del estado de los servicios |
| Medrano Solano Enrique | [@Quique1409](https://github.com/Quique1409) | `envios`, la mensajería con RabbitMQ, `identidad` y el filtro del token |
| Rodríguez Zamora Joshua | [@JoshRozam](https://github.com/JoshRozam) | `concursos`, la tabla de posiciones y el `gateway` |

## Cómo se levanta

Requisito: Docker con Docker Compose. No hace falta tener Java ni Maven instalados: cada servicio se compila dentro de Docker.

```bash
git checkout 02-separacion
cp .env.ejemplo .env
docker compose up -d --build
```

En PowerShell, el segundo comando es `Copy-Item .env.ejemplo .env`. El `.env` trae el secreto de los tokens y las contraseñas de la siembra, y no se sube al repositorio. Sin él, Compose se detiene y dice qué variable falta.

Con eso basta: cada servicio aplica sus migraciones al arrancar, `concursos` siembra el concurso de demo e `identidad` siembra las cuentas. Cuando el comando termina, el sistema ya recibe peticiones. Con las imágenes ya construidas, los 12 contenedores quedaron sanos en 32 s; la primera vez, la construcción tarda unos minutos más.

| Contenedor | Qué es | Puerto en esta computadora |
|---|---|---|
| `nginx` | Balanceador y entrada única. Reparte entre las réplicas del gateway por conexiones activas (`least_conn`) y entrega el front | `8080` |
| `gateway` (2 réplicas) | Valida el token y manda cada ruta a su servicio, que encuentra en el directorio | No publica |
| `eureka` | Directorio: los servicios se registran y el gateway los busca por nombre | `127.0.0.1:8761` |
| `identidad` | Inicio de sesión, tokens y cuentas | No publica |
| `concursos` | Concurso activo, problemas y tabla de posiciones | No publica |
| `envios` | Recibe los envíos, los guarda y publica su identificador en RabbitMQ | No publica |
| `evaluacion` (2 contenedores) | Workers que toman los envíos de RabbitMQ y los evalúan | No publica |
| `rabbitmq` | Cola entre `envios` y `evaluacion`, con volumen | `127.0.0.1:15672` (consola) |
| `db` | PostgreSQL 17, con volumen. Todos los servicios la usan, cada uno en sus tablas | `127.0.0.1:5432` |
| `app` | La aplicación de la etapa 1, con su evaluación y su siembra apagadas. Solo sirve el front y la vista del estado de los servicios | No publica |

- **Entrada:** `http://localhost:8080`. Las cuentas de alumno van de `alumno1` a `alumno5`, con la contraseña `SIEMBRA_CONTRASENA_DEMO` del `.env`.
- **Estado:** `docker compose ps` muestra la salud de cada contenedor. La lista de instancias registradas se ve en `http://localhost:8761`, y el front trae la vista del estado de los servicios.
- **Más workers:** `docker compose up -d --scale evaluacion=3` agrega contenedores de `evaluacion`. `EVALUACION_WORKERS` (2 por omisión) son los consumidores dentro de cada contenedor.
- **Si se recrea el gateway:** nginx aprende las direcciones de las réplicas del gateway al arrancar. Después de `docker compose up -d --force-recreate gateway` o de `--scale gateway=N`, reinicia nginx con `docker compose restart nginx`. Detener y volver a arrancar una réplica (`docker stop` y `docker start`) no lo requiere.
- **Puerto ocupado:** `ENTRADA_PUERTO`, `DB_PUERTO_LOCAL`, `EUREKA_PUERTO_LOCAL` y `RABBITMQ_PUERTO_LOCAL` cambian los puertos de esta computadora. Se ponen en el `.env`. Si cambias el de la base o el del directorio, `scripts/demo_fallas.py` necesita `DATABASE_URL` y `--eureka`.
- **Empezar de cero:** `docker compose down -v` borra los volúmenes de la base y de la cola, incluidos envíos y resultados. Sin `-v`, los datos se conservan.
- **Pruebas:** el README de cada servicio, en `servicios/`, dice cómo correr las suyas dentro de Docker. Las de la aplicación de la etapa 1: `docker build --target construccion -t distrinubs-pruebas .` y luego `docker run --rm distrinubs-pruebas mvn -B -q test`.

## Scripts

En Windows el comando es `python` en lugar de `python3`. La contraseña es la de `SIEMBRA_CONTRASENA_DEMO`; los dos scripts de demostración también la toman de esa variable de entorno.

| Script | Comando | Qué hace |
|---|---|---|
| `scripts/demo.py` | `python3 scripts/demo.py --contrasena <contraseña>` | Recorre los puntos 1 a 6 del alcance contra la API: concurso, cuentas, inicio de sesión, envío con reintento, Wrong Answer, Accepted y tabla |
| `scripts/demo_fallas.py` | `python3 scripts/demo_fallas.py --todos --contrasena <contraseña>` | Demostración de manejo de fallas: mata al worker que evalúa un envío y comprueba que otro lo retoma, apaga una réplica del gateway y comprueba que la entrada sigue respondiendo, y mata una instancia y mide cuánto tarda en salir del directorio. Requiere `pip install psycopg2-binary` para leer el lease en la base. Al terminar vuelve a arrancar los contenedores que mató |
| `scripts/carga.py` | No corre contra este Compose | Prueba de carga del marcador de la etapa 1. Todavía no inicia sesión. Ver "Prueba de carga (etapa 1)" |

Sin Python instalado, `demo.py` corre dentro del contenedor `app`, apuntando a la entrada: `docker compose exec -T app python3 - --base http://nginx --contrasena <contraseña> < scripts/demo.py` en bash, o `Get-Content scripts/demo.py | docker compose exec -T app python3 - --base http://nginx --contrasena <contraseña>` en PowerShell.

La siembra no es un script: `concursos` siembra el concurso con `servicios/concursos/src/main/resources/siembra/concursos.sql` e `identidad` siembra las cuentas con las contraseñas del `.env`, los dos al arrancar.

## Qué hace la etapa 2

Se verificó de punta a punta el 28/11/2026, desde cero: `docker compose down -v`, `docker compose up -d --build`, `scripts/demo.py` y `scripts/demo_fallas.py --todos`, sin pasos intermedios. Las salidas completas están en [docs/evidencia/](docs/evidencia/).

1. **Inicio de sesión.** El alumno entra con usuario y contraseña en `identidad` y recibe un token firmado (JWT) con su cuenta, su rol y su vencimiento. Hay tres roles: alumno, organizador y administrador. Un administrador puede crear cuentas, bloquearlas, cambiar roles y revocar tokens por la API.
2. **Token validado dos veces.** El gateway valida el token de cada petición y `envios` lo valida otra vez al recibir. Los dos preguntan a `identidad` si la cuenta está bloqueada o el token revocado; si `identidad` no responde, rechazan con 503 en lugar de aceptar sin validar.
3. **Subir envío.** `envios` responde "recibido" solo después de guardar el envío en PostgreSQL. Un reintento con el mismo `intentoId` devuelve el mismo envío. Cada quien envía y consulta solo lo suyo.
4. **Cola entre envíos y evaluación.** `envios` publica el identificador del envío en RabbitMQ y espera la confirmación del broker. Si cae entre guardar y publicar, un barrido lo publica más tarde.
5. **Evaluación en contenedores aparte.** Dos contenedores de `evaluacion`, con 2 consumidores cada uno, toman los mensajes. Cada envío se toma con un lease, se corre en Python 3 con límite de tiempo y deja un solo resultado: Accepted, Wrong Answer, Time Limit Exceeded, Runtime Error o error de compilación.
6. **Reentrega.** Si un worker muere a media evaluación, otro retoma el envío cuando vence el lease y lo evalúa una sola vez. Un envío se toma 3 veces como máximo; después queda con `ERROR_SERVIDOR`, que no penaliza.
7. **Tabla de posiciones.** Solo con las cuentas de alumno: problemas resueltos y penalización (minuto de la aceptación más 5 minutos por cada fallo previo). La calcula `concursos` al consultarla.
8. **Una sola entrada.** nginx es el único contenedor con el puerto de la aplicación publicado. Reparte entre 2 réplicas del gateway, y el gateway manda cada ruta al servicio que le toca.
9. **Directorio.** `gateway`, `identidad`, `concursos` y `envios` se registran en Eureka. El gateway los encuentra por nombre; no hay direcciones fijas.
10. **Circuit breaker y tiempos de espera.** El gateway espera a un servicio 2 s para conectar y 7 s para responder. Si un servicio deja de contestar, su circuito abre y el gateway responde 503 de inmediato, con un JSON que dice qué servicio falta.
11. **Front.** Inicio de sesión, subir envío, estado y veredicto, tabla de posiciones y vista del estado de los servicios. Si un servicio no responde, muestra un aviso con el código y el id de correlación de la petición.
12. **Id de correlación en los logs.** nginx genera un id por petición, el gateway lo propaga y `identidad`, `envios` y `concursos` lo escriben en cada línea de log. Con `docker compose logs nginx gateway envios | grep <id>` se sigue una petición de la entrada al servicio.
13. **Demo de fallas.** `scripts/demo_fallas.py` provoca tres fallas reales sobre los contenedores y comprueba el resultado de cada una.

## Qué no hace la etapa 2

- **`marcador` aparte y Redis.** No existen. La tabla de posiciones la calcula `concursos` en cada consulta, leyendo PostgreSQL. La ruta `/api/marcador/**` del gateway responde el 503 del circuito. En la vista de estado, `marcador` aparece como `NO_DISPONIBLE` y el estado global como `DEGRADADO` por esa razón.
- **Más lenguajes.** Solo Python 3. El esquema ya admite C++, C, Java y JavaScript, pero `envios` los rechaza y `evaluacion` no los ejecuta. En el front aparecen como "próximamente".
- **Sandbox por envío.** El código del alumno corre como proceso hijo de `evaluacion`, sin root, en un directorio temporal y sin variables de entorno, pero sin contenedor propio.
- **Límite de memoria.** Se guarda en el problema y no se aplica.
- **Renovación de lease.** El lease es fijo. Un envío que tarda más que su lease puede retomarlo otro worker; la unicidad del resultado evita que cuente dos veces.
- **Evaluación final e invalidación al cierre.** En vivo solo corren los casos del marcador provisional.
- **Interfaz de organizador y de administrador.** El concurso lo carga la siembra. Las cuentas se administran por la API de `identidad`, sin pantalla.
- **Id de correlación más allá de la petición HTTP.** El id no viaja en el mensaje de RabbitMQ: `evaluacion` registra el número de envío en cada línea, no el id de la petición. Tampoco viaja en la consulta de validación del token que el gateway y `envios` hacen a `identidad`: esa consulta queda en el log de `identidad` con un id propio.
- **Consumidor de la cola de mensajes muertos.** La cola `envios.evaluacion.dlq` existe y recibe los mensajes que `evaluacion` rechaza o que superan el límite de 3 entregas, pero nadie la lee ni la muestra.
- **Prueba de carga contra los servicios.** `scripts/carga.py` no inicia sesión, así que no corre contra este Compose. El número de carga que hay es el de la etapa 1.
- **Vista de estado protegida.** `/api/observabilidad/estado` es pública y su respuesta incluye las direcciones internas de los servicios y el texto de las excepciones. Sirve para la demo; no se presenta como condición de producción.
- **Circuito compartido.** Cada réplica del gateway lleva su propia cuenta de fallas.

## Qué queda pendiente

- `marcador` como servicio, con mensajes de resultado y Redis delante.
- C++, C, Java y JavaScript: compiladores en la imagen de `evaluacion` y paso de compilación con su propio tope.
- Sandbox por envío, límite de memoria, renovación de lease y evaluación final.
- Llevar el id de correlación al mensaje de RabbitMQ y a la validación del token en `identidad`.
- Consumidor de la cola de mensajes muertos y una vista para organizadores de los envíos con `ERROR_SERVIDOR`.
- `scripts/carga.py` con inicio de sesión, para repetir la prueba de carga contra los servicios.
- Comprobar dos riesgos de `evaluacion`: si el código del alumno puede leer `/proc/<pid>/environ` del proceso Java, y si un `#include` puede filtrar archivos del servidor cuando haya compilación.
- Mover el front y la vista de estado fuera de la aplicación de la etapa 1, que hoy sigue como contenedor `app` solo para eso.
- Interfaz de organizador, dificultad por resueltos, desempate completo y saga de cierre del concurso.

## Cómo entran las peticiones y cómo se comunican las partes

```
navegador ──► nginx :8080 ──► gateway (2 réplicas) ──► identidad · concursos · envios
                 │                    │
                 └──► app (front y vista de estado)     └── busca cada servicio por nombre en Eureka

envios ──► RabbitMQ (envios.evaluacion) ──► evaluacion (2 contenedores)
```

- **nginx** recibe todo. El front (`/`, `/index.html`, `/app.js`, `/estilos.css`) y `/api/observabilidad/estado` van al contenedor `app`, cada uno por una ruta exacta. Lo demás va al gateway, a la réplica con menos conexiones activas. `/actuator` responde 404 desde afuera.
- **gateway** valida el token, limpia las cabeceras `X-Usuario-*` que mande el cliente y agrega las suyas con la cuenta ya verificada. Rutas: `/api/auth/**`, `/api/identidad/**` y `/api/cuentas-demo` a `identidad`; `/api/concursos/**` a `concursos`; `/api/envios/**` a `envios`. Entre las instancias de un servicio reparte Spring Cloud LoadBalancer.
- **De servicio a servicio por HTTP** solo hay una llamada: `envios` y el gateway consultan `GET /api/auth/validar` en `identidad`.
- **Entre `envios` y `evaluacion` no hay HTTP.** `envios` publica `{"envioId": N}` en RabbitMQ y `evaluacion` lo consume. El mensaje solo avisa qué envío revisar; el estado vive en PostgreSQL.
- **`evaluacion` no se registra en Eureka:** no recibe peticiones, toma su trabajo de la cola.

Los diagramas están en [docs/arquitectura.md](docs/arquitectura.md).

## Dónde viven los datos

Una sola base PostgreSQL 17, con el volumen `distrinubs_datos`. Todos los servicios la usan, y cada tabla tiene un solo servicio que escribe en ella.

| Tabla | Servicio dueño | Quién la lee |
|---|---|---|
| `cuentas`, `tokens_revocados` | `identidad` | `envios` y la tabla de posiciones leen `cuentas` |
| `concursos`, `problemas`, `casos_prueba` | `concursos` | `envios`, `evaluacion` y la tabla de posiciones |
| `envios` | `envios` | `evaluacion` y la tabla de posiciones |
| `leases_evaluacion`, `resultados` | `evaluacion` | `envios` y la tabla de posiciones |

- **Migraciones:** cada servicio aplica las suyas con Flyway al arrancar, sobre la misma base, y tolera las de los demás. No hay un contenedor aparte que migre. Una migración ya aplicada no se edita: se agrega otra.
- **Siembra:** `concursos` siembra el concurso de demo e `identidad` las cuentas, al arrancar. Es idempotente.
- **RabbitMQ** guarda la cola en el volumen `distrinubs_cola`. Es una copia de trabajo: si se pierde, el barrido de `envios` vuelve a publicar lo que no tiene resultado.
- **Eureka** guarda la lista de instancias en memoria. Si se reinicia, se reconstruye sola con las renovaciones de los servicios.
- **Restricciones que sostienen las reglas:** un envío por cuenta e intento, un lease por envío y un resultado por envío.

## Qué pasa si algo falla

Medido el 28/11/2026 con este Compose, en una computadora con Docker Desktop (32 CPU y 16 GB asignados).

| Qué se cae | Qué pasa | Medido |
|---|---|---|
| Un worker a media evaluación (`docker kill`) | Vence su lease, otro worker retoma el envío como toma 2 y guarda un solo resultado | El envío terminó en `ACCEPTED` entre 24 y 25 s después de matar al worker, en tres corridas |
| Una réplica del gateway (`docker stop`) | nginx manda todo a la otra réplica | 8 de 8 peticiones con HTTP 200 en 10 de 10 corridas seguidas; en cada corrida, a lo más una petición tardó cerca de 1 s y las demás menos de 40 ms |
| Una instancia registrada, sin avisar (`docker kill`) | Deja de renovar su registro y sale del directorio | Salió de Eureka entre 30 y 34 s después, en tres corridas |
| Un servicio completo (`concursos`) | Su circuito abre y el gateway responde 503 con el nombre del servicio; las demás rutas siguen atendiendo | 2 s por petición hasta que abrió (8 peticiones entre las dos réplicas), después 7 ms; 200 de nuevo a los 11 s de arrancarlo |
| Un mensaje que `evaluacion` no puede leer | Lo rechaza y RabbitMQ lo manda a `envios.evaluacion.dlq` | Comprobado con un mensaje sin `envioId` publicado a mano |
| `identidad` | No se acepta nada sin validar: las rutas con token responden 503 | No se midió el tiempo |
| RabbitMQ, entre guardar y publicar | El envío queda guardado y el barrido de `envios` lo publica después | No se midió el tiempo |
| Eureka | El gateway sigue con la última lista que guardó; no ve altas ni bajas hasta que vuelve | No se probó en esta corrida |
| nginx, PostgreSQL o la computadora | No hay entrada o no hay datos hasta que vuelvan. Lo ya guardado sobrevive | Riesgo declarado |

La demo de fallas corre los tres primeros casos:

```bash
pip install psycopg2-binary   # una vez
python3 scripts/demo_fallas.py --todos --contrasena <contraseña>
```

Al terminar, el script vuelve a arrancar los contenedores que mató, pero no espera a que estén listos. Antes de repetirla, espera a que `docker compose ps` muestre todo `healthy` y unos 20 s más: si empieza antes, el caso 1 falla porque `envios` todavía no responde.

## Riesgos declarados

- **Código sin sandbox.** El código de los alumnos corre como proceso hijo de `evaluacion`, en la computadora del equipo, con red y sin límite de memoria. Se redujo el daño (usuario sin privilegios, directorio temporal, sin variables de entorno, límite de tiempo), pero no es aislamiento.
- **Una sola máquina, punto único de falla.** Si la computadora se apaga, el sistema no responde hasta que vuelva. Lo ya guardado sobrevive: los envíos sin resultado se retoman y los resultados no se duplican.
- **Una sola instancia de PostgreSQL, de nginx, de Eureka y de RabbitMQ.** La redundancia está en el gateway y en los workers.
- **Accepted solo con el subconjunto.** Sin evaluación final, un envío que falla solo un caso privado queda como Accepted.
- **Secretos de demo.** `JWT_SECRETO` y las contraseñas de la siembra salen del `.env`; los valores de `.env.ejemplo` son de ejemplo y hay que cambiarlos.
- **Fallo del kernel de WSL2 visto en una computadora del equipo.** En unos 2 de 95 arranques, un contenedor de Java quedó "Up" con el log vacío y sin pasar su healthcheck. Si pasa antes de la demo, se reinicia Docker Desktop y se levanta de nuevo.

## Pruebas

Cada módulo corre sus pruebas dentro de Docker, con JDK 21. El comando está en el README de cada servicio.

| Módulo | Pruebas | Resultado el 28/11/2026 |
|---|---|---|
| `servicios/gateway` | 19 | Pasan |
| `servicios/identidad` | 20 | Pasan |
| `servicios/envios` | 23 | Pasan |
| `servicios/evaluacion` | 17 | Pasan |
| `servicios/concursos` | 10 | Pasan |
| `servicios/eureka` | 4 | Pasan |
| Aplicación de la etapa 1 (raíz) | 31 | Pasan |

Las de la aplicación de la etapa 1: `docker build --target construccion -t distrinubs-pruebas .` y luego `docker run --rm distrinubs-pruebas mvn -B -q test`.

## Prueba de carga (etapa 1)

No se repitió contra los servicios. El número que hay es el de la etapa 1, con el monolito: 200 alumnos y 800 envíos de golpe, medido el 12/11/2026.

| Escenario | Workers | Retraso máximo del marcador |
|---|---|---|
| Ráfaga | 1 | 104 s |
| Ráfaga | 2 | 44 s |
| Ráfaga | 4 | 21 s |

El límite tolerado es de 300 s. La prueba completa, con sus condiciones y lo que no cubre, está en el README de la rama `01-monolito`.

## Estructura del repositorio

| Carpeta | Qué es | Responsable |
|---|---|---|
| `servicios/gateway` | Spring Cloud Gateway con el filtro del token y el circuit breaker | Joshua (gateway), Enrique (filtro) y Emiliano (circuit breaker) |
| `servicios/identidad` | Inicio de sesión, tokens y cuentas | Enrique |
| `servicios/envios` | Recepción de envíos y publicación en RabbitMQ | Enrique |
| `servicios/evaluacion` | Workers con lease | Emiliano |
| `servicios/concursos` | Concurso, problemas y tabla de posiciones | Joshua |
| `servicios/eureka` | Directorio | Emiliano |
| `nginx/` | Configuración del balanceador de entrada | Emiliano |
| `src/` | Aplicación de la etapa 1. En este Compose solo sirve el front y la vista de estado | Diego (front y observabilidad) |
| `scripts/` | Demostración, demo de fallas y prueba de carga | Diego |
| `docs/` | PRD, plan, arquitectura, evidencia y presentación | Todos |

Cada servicio trae su README (qué hace, qué no hace y qué falta), su Dockerfile y sus pruebas.

## Documentación

- [docs/PRD.md](docs/PRD.md): problema, alcance, decisiones de arquitectura y bitácora de cambios de enfoque.
- [docs/PLAN.md](docs/PLAN.md): las dos etapas, el reparto y las fechas.
- [docs/arquitectura.md](docs/arquitectura.md): diagramas, flujo de un envío, dueños de datos y manejo de fallas.
- [docs/demo.md](docs/demo.md): los comandos de la demo, en orden.
- [docs/evidencia/](docs/evidencia/): salidas de la verificación del 28/11/2026.
- [docs/pitch-distrinubs.pptx](docs/pitch-distrinubs.pptx): la presentación.
- [CLAUDE.md](CLAUDE.md) y [AGENTS.md](AGENTS.md): reglas para los agentes que trabajan en el repositorio.
- Referencia de la asignatura: [mexi-banco](https://github.com/OscarRuiz21/mexi-banco), con una rama por etapa.
