# Distrinubs · etapa 01-monolito

Juez en línea para práctica y concursos de programación competitiva. Proyecto final de la asignatura Sistemas Distribuidos (FI-UNAM, 2027-1).

Este es el README de la rama `01-monolito`: dice qué hace la etapa 1, qué no hace, qué queda pendiente y cómo se levanta. El de `main` presenta el proyecto.

## Qué es

Los alumnos envían una solución a un problema. El sistema la evalúa contra casos de prueba, con límite de tiempo, y publica el veredicto: Accepted, Wrong Answer, Time Limit Exceeded, Runtime Error o error de compilación. Con los envíos construye una tabla de posiciones. Durante el concurso es un marcador provisional, y al cierre habrá uno final.

La etapa 1 es un monolito: una sola aplicación Spring Boot, en un solo proceso, con sus siete módulos y una base PostgreSQL, en contenedores con Docker Compose en una computadora.

## Quiénes somos

| Integrante | GitHub | Parte principal en la etapa 1 |
|---|---|---|
| Calderón Gutiérrez Victor Emiliano | [@Delta2904](https://github.com/Delta2904) | Base de datos, evaluación de código e integración. Coordina el proyecto |
| Contreras del Ángel Diego Adrián | [@diegocontrras](https://github.com/diegocontrras) | Front pequeño, cuentas de prueba y script de demostración |
| Medrano Solano Enrique | [@Quique1409](https://github.com/Quique1409) | Envíos y estado del envío |
| Rodríguez Zamora Joshua | [@JoshRozam](https://github.com/JoshRozam) | Concursos y problemas, y tabla de posiciones |

## Cómo se levanta

En la rama `02-separacion`, el `docker-compose.yml` ya es el de la etapa 2 y levanta los servicios separados. Esta sección y la de scripts describen ese Compose. Las secciones que siguen, desde "Qué hace la etapa 1", todavía describen el monolito y se actualizan en el cierre de la rama.

Requisito: Docker con Docker Compose. No hace falta tener Java ni Maven instalados: cada servicio se compila dentro de Docker.

```bash
git checkout 02-separacion
cp .env.ejemplo .env
docker compose up -d --build
```

En PowerShell, el segundo comando es `Copy-Item .env.ejemplo .env`. El `.env` trae el secreto de los tokens y las contraseñas de la siembra, y no se sube al repositorio. Sin él, Compose se detiene y dice qué variable falta.

Con eso basta: cada servicio aplica sus migraciones al arrancar, `concursos` siembra el concurso de demo e `identidad` siembra las cuentas. Cuando el comando termina, el sistema ya recibe peticiones.

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
| `scripts/carga.py` | `python3 scripts/carga.py --escenario rafaga` | Prueba de carga del marcador de la etapa 1. Todavía no inicia sesión, así que no corre contra este Compose. Ver "Prueba de carga del marcador" |

Sin Python instalado, `demo.py` corre dentro del contenedor `app`, apuntando a la entrada: `docker compose exec -T app python3 - --base http://nginx --contrasena <contraseña> < scripts/demo.py` en bash, o `Get-Content scripts/demo.py | docker compose exec -T app python3 - --base http://nginx --contrasena <contraseña>` en PowerShell.

La siembra no es un script: `concursos` siembra el concurso con `servicios/concursos/src/main/resources/siembra/concursos.sql` e `identidad` siembra las cuentas con las contraseñas del `.env`, los dos al arrancar.

## Qué hace la etapa 1

Se verificó de punta a punta el 12/11/2026: `docker compose down -v`, `docker compose up -d --build` y `scripts/demo.py`, sin pasos intermedios.

1. Concurso "Distrinubs Round 1" abierto, con un problema y tres casos de prueba, cargado por la siembra automática al arrancar.
2. Identidad de demo: el alumno se elige de una lista de cuentas de prueba y su id viaja con el envío.
3. Subir envío: responde "recibido" con su identificador solo después de guardarlo. Un reintento con el mismo `intentoId` devuelve el mismo envío.
4. Evaluación con varios workers (2 por omisión), que son hilos de la misma aplicación sobre la misma cola: cada uno toma un envío con un lease, corre el código en Python 3 con límite de tiempo y guarda el veredicto: Accepted, Wrong Answer, Time Limit Exceeded, Runtime Error o error de compilación. Si la aplicación se cae a media evaluación, el envío se retoma cuando vence su lease y se evalúa una sola vez.
   - **Tope de reintentos:** un envío se toma como máximo 3 veces. Si el servidor no logra evaluarlo, queda con `ERROR_SERVIDOR`, que no cuenta como fallo ni penaliza, y el alumno ve que puede volver a enviarlo.
5. Ver el estado ("en cola", "evaluando", "evaluado") y el veredicto del envío.
6. Tabla provisional con problemas resueltos y penalización: minuto de la aceptación más 5 minutos por cada fallo previo.
7. Front pequeño en HTML y JavaScript: elegir alumno, subir envío, ver estado y tabla.
8. Script de demostración que recorre los puntos 1 a 6.
9. Script de prueba de carga del marcador con 200 alumnos.

## Qué no hace la etapa 1

- Inicio de sesión con contraseña o tokens, su revocación y su validación en la subida.
- Sandbox con un contenedor nuevo por envío.
- Varios lenguajes: solo Python 3.
- Límite de memoria: se guarda en el problema, pero no se aplica.
- Workers en procesos o contenedores separados: en 01 son hilos de la misma aplicación.
- Renovación de lease: el lease es fijo.
- Evaluación final con todos los casos e invalidación al cierre. En vivo solo corren los casos del marcador provisional.
- Verificación de la solución de referencia: los casos se revisaron a mano.
- Dificultad por resueltos y desempate completo.
- Roles, bloqueos, registro de acciones, interfaz de organizador, panel de la cola y lista de envíos para organizadores.

## Pendiente

- El tope de compilación, que sigue sin definirse. En 01 hay una salvaguarda provisional de 10 s que no lo decide.
- La recompilación en un worker limpio. Hoy, un envío cuya revisión de sintaxis se cuelga se reintenta en cualquier worker, hasta el tope de 3 tomas.
- Repetir la prueba de carga con sandbox: el contenedor por envío no existe en 01 y su costo de arranque no está medido.
- Una vista para organizadores de los envíos que quedaron con `ERROR_SERVIDOR`. Hoy solo se ven en el log.
- La evaluación final al cierre, con todos los casos, y la invalidación.
- Flyway 10.10, el que trae Spring Boot 3.3, avisa al arrancar que no probó PostgreSQL 17. Funciona, pero queda revisar la versión.

## Riesgos declarados

- **Sin autenticación.** Cualquiera puede enviar con cualquier identidad de demo.
- **Código sin sandbox.** El código de los alumnos corre como proceso hijo de la aplicación, en la computadora del equipo. Se redujo el daño, pero no es aislamiento:
  - la aplicación corre con un usuario sin privilegios (`juez`);
  - cada envío corre en un directorio temporal;
  - el código no hereda variables de entorno, así que no ve la contraseña de la base;
  - cada caso se mata al pasar su límite de tiempo.

  El código todavía tiene red y puede leer los archivos de la aplicación. No se presenta como condición de producción.
- **Una sola máquina, punto único de falla.** Si la computadora se apaga o se reinicia, el sistema no responde hasta que vuelva a arrancar. Lo ya guardado sobrevive: los envíos con lease vencido se retoman y los resultados no se duplican.
- **Excepción temporal en la siembra de cuentas.** La tabla `cuentas` es de Inicio de sesión, pero en 01 no hay inicio de sesión: las cuentas solo las escribe la siembra de ese paquete al arrancar (`SiembraCuentasDemo`), incluidas las cuentas extra de la prueba de carga.
- **Accepted solo con el subconjunto.** Sin evaluación final, un envío que falla solo un caso privado queda como Accepted en 01.

## Estructura de paquetes

Un paquete por cada uno de los siete módulos, dentro de `mx.distrinubs`. Los módulos se llaman entre sí con código Java en el mismo proceso, sin HTTP. Cada `package-info.java` dice qué tablas escribe y cuáles lee el módulo.

| Paquete | Módulo | Responsable en 01 | Escribe |
|---|---|---|---|
| `iniciosesion` | Inicio de sesión | Diego (cuentas de demo) | `cuentas` (en 01, solo su siembra al arrancar) |
| `envios` | Envíos | Enrique | `envios` |
| `tablaposiciones` | Tabla de posiciones | Joshua | Nada: el marcador se calcula al consultarlo |
| `evaluacion` | Evaluación de código | Emiliano | `leases_evaluacion`, `resultados` |
| `concursos` | Concursos y problemas | Joshua | `concursos`, `problemas`, `casos_prueba` |
| `administracion` | Administración | No entra en 01 | Nada |
| `basedatos` | Base de datos | Emiliano | El esquema, con migraciones en `src/main/resources/db/migration` |

El front está en `src/main/resources/static` y lo sirve Spring Boot.

## Base de datos

PostgreSQL 17 en un contenedor, con el volumen persistente `distrinubs_datos`. El esquema lo crea Flyway al arrancar la aplicación. Hibernate no crea ni modifica tablas.

- **Migraciones:** cada dueño de datos escribe las de sus tablas, con el nombre `V<AAAAMMDD>_<HHMM>__<tabla>_<cambio>.sql`. Una migración ya aplicada no se edita: se agrega otra.
- **Siembra:** cada módulo dueño ejecuta su SQL al arrancar, después de las migraciones y antes de abrir el puerto. Es idempotente.
- **Ids de la siembra:** `cuentas`, `concursos`, `problemas` y `casos_prueba` reciben ids fijos del 1 al 999 y se hace upsert por id. Sus secuencias empiezan en 1000, así lo que inserte la aplicación no choca con la siembra. `envios`, `leases_evaluacion` y `resultados` no se siembran: su id siempre lo pone la base.
- **Restricciones que sostienen las reglas del negocio:**
  - `envios_un_envio_por_intento (cuenta_id, id_intento)`: un reintento no crea un segundo envío.
  - `leases_evaluacion_un_lease_por_envio`: un solo lease por envío.
  - `resultados_un_resultado_por_envio`: un envío no cuenta dos veces.

  El dueño de cada tabla queda como comentario de la tabla. Se ve con `docker compose exec db psql -U distrinubs -c '\dt+'`.

## Etapa 01-monolito: Evaluación de código

Parte de Emiliano (@Delta2904), en el paquete `evaluacion`.

- **Dónde corre:** dentro de la aplicación. No es un contenedor aparte. Hay `evaluacion.workers` hilos (2 por omisión), cada uno con su nombre de worker. Cada uno revisa la cola cada segundo y la vacía antes de volver a esperar. No se coordinan entre sí: solo comparten la cola de PostgreSQL.
- **Cómo toma un envío:**
  - Toma el envío en estado `EN_COLA` más antiguo que no tenga resultado, lease vigente ni las tomas agotadas.
  - Lo marca con un lease en `leases_evaluacion` que dura 10 s + (casos × límite de tiempo) + 10 s, sin renovación.
  - Si el lease anterior ya venció, retoma la misma fila y suma `veces_tomado`.
  - "Evaluando" no se guarda en ningún lado: Envíos lo deriva del lease vigente. Evaluación no llama a Envíos.
- **Varios workers sobre la misma cola:**
  - La toma bloquea un instante la fila del envío candidato con `FOR NO KEY UPDATE ... SKIP LOCKED`, así cada worker elige un envío distinto. Es un lock, no una escritura: `envios` la sigue escribiendo solo Envíos.
  - Aun así dos workers pueden elegir el mismo envío. La unicidad del lease hace que solo uno lo tome; el otro no toma nada.
  - Un worker que pierde esa carrera lo distingue de una cola vacía y lo intenta de nuevo sin dormir. Antes lo leía como cola vacía: en la prueba, con 4 workers y 200 envíos, uno solo hizo todo el trabajo.
- **Cómo juzga:**
  1. Revisa la sintaxis con `py_compile`. Si falla, el veredicto es error de compilación y el mensaje queda en `detalle`.
  2. Corre los casos del marcador provisional (o todos, si el problema no marcó ninguno) en orden, cada uno con el límite de tiempo del problema.
  3. Se detiene en el primer caso con Time Limit Exceeded, Runtime Error (salida con código distinto de 0) o Wrong Answer.
  4. La salida se compara sin los espacios al final de cada línea ni las líneas vacías al final.
  5. Con WA, TLE y RE el alumno solo ve el veredicto, sin mensaje ni número de caso.

  `tiempo_ms` es el mayor tiempo entre los casos e incluye el arranque de Python.
- **Cómo guarda:** escribe la fila de `resultados` solo si todavía tiene el lease que tomó: mismo worker y mismo `veces_tomado`. La unicidad por envío impide un segundo resultado.
- **Si falla el servidor:** si no puede lanzar Python, si la revisión de sintaxis pasa de 10 s o si el problema no tiene casos, no guarda resultado. El envío vuelve a la cola cuando vence el lease y no se penaliza al alumno.
- **Tope de reintentos:** un envío se toma como máximo `evaluacion.max-tomas` veces (3). Cuenta toda toma sin resultado, también una caída de la aplicación a media evaluación. Cuando vence el lease de la última toma, Evaluación guarda el veredicto `ERROR_SERVIDOR`. La tabla no lo cuenta como fallo y el front le dice al alumno que vuelva a enviar.
- **Configuración** (`application.yml`, sección `evaluacion`): `activa`, `python`, `workers`, `max-tomas`, `intervalo-ms`, `salvaguarda-compilacion-ms` y `margen-lease-ms`.
- **Pruebas:** `VariosWorkersTest` (4 workers, 200 envíos: una pasada vacía la cola, cada envío se evalúa una vez y el trabajo se reparte) y `TopeReintentosTest`, las dos contra un PostgreSQL real embebido.

## Prueba de carga del marcador

`scripts/carga.py` simula un concurso de 200 alumnos y mide el retraso del marcador: el tiempo entre que el servidor responde "recibido" a un envío y el primer sondeo de la tabla de posiciones en que la fila del alumno ya lo refleja. El objetivo es que quede bajo 5 minutos (300 s).

**Resultado: cumple.** En el peor caso medido, 800 envíos de golpe con un solo worker, el envío más lento tardó 104 s en aparecer en la tabla. Con los 2 workers por omisión tardó 44 s.

Medido el 12/11/2026 en una computadora con Docker Desktop (32 CPU y 16 GB asignados). Cada alumno manda 3 envíos fallidos y al final uno aceptado: 800 envíos por corrida.

| Escenario | Workers | Duración | Envíos/s | Retraso p50 | Retraso p95 | Retraso máximo | Máximo según el servidor |
|---|---|---|---|---|---|---|---|
| Ráfaga | 1 | 105 s | 7.6 | 53.6 s | 96.7 s | 104.2 s | 103 s |
| Ráfaga | 2 | 45 s | 18.0 | 25.9 s | 42.8 s | 43.8 s | 43 s |
| Ráfaga | 4 | 23 s | 34.9 | 13.3 s | 20.9 s | 21.4 s | 20 s |
| Sostenido | 1 | 301 s | 2.7 | 1.0 s | 2.1 s | 3.4 s | 2 s |
| Sostenido | 2 | 302 s | 2.7 | 0.8 s | 1.7 s | 2.5 s | 1 s |
| Sostenido | 4 | 302 s | 2.7 | 0.8 s | 1.6 s | 2.4 s | 1 s |

- **Ráfaga:** los 200 alumnos mandan sus 4 envíos a la vez. Es el peor caso.
- **Sostenido:** los envíos se reparten en 5 minutos y cada alumno espera su veredicto antes de mandar el siguiente.
- **Mezcla de los fallidos:** 70 % Wrong Answer y 10 % cada uno de Runtime Error, error de compilación y Time Limit Exceeded. Cada Time Limit Exceeded ocupa a un worker 1 s completo.
- **Lecturas simuladas:** cada alumno consulta su último envío cada segundo y la tabla cada 5 s, como el front.
- **En las seis corridas:** los 800 envíos quedaron con un solo resultado, ninguno con `ERROR_SERVIDOR`, sin errores HTTP y sin aviso a organizadores. La tabla respondió en menos de 0.04 s.
- **"Máximo según el servidor"** es el `retrasoSegundos` que reporta la propia tabla: la edad del envío sin evaluar más antiguo.

Lo que esta prueba no cubre:

- **Sin sandbox.** El código corre como proceso hijo. El contenedor por envío, que no existe en 01, agregará su costo de arranque a cada evaluación.
- **Un problema trivial con 2 casos en vivo.** Con más casos o límites de tiempo más largos, cada evaluación tarda más y el retraso crece en proporción.
- **Una computadora con 32 CPU.** En una laptop con menos núcleos, 4 workers pueden rendir menos que aquí.
- **Resolución de 0.5 s:** es cada cuánto se sondea la tabla para medir.
- **Un lease retomado sin explicar.** En la primera ráfaga con 1 worker, un envío se tomó dos veces porque su lease venció. Terminó con un solo resultado y no penalizó de más. Al repetir la corrida no volvió a pasar (retraso máximo de 87 s) y la causa no quedó identificada.

Cómo repetirla, desde cero porque necesita cuentas de carga sin usar:

```bash
# bash
docker compose down -v && SIEMBRA_CUENTAS_CARGA=200 docker compose up -d --build
python3 scripts/carga.py --escenario rafaga
```

```powershell
# PowerShell
docker compose down -v; $env:SIEMBRA_CUENTAS_CARGA=200; docker compose up -d --build
python scripts/carga.py --escenario rafaga
```

Para el otro escenario, `--escenario sostenido`. Para cambiar los workers, `EVALUACION_WORKERS`. Las cuentas de carga se quedan en la base hasta el siguiente `docker compose down -v`; en PowerShell hay que quitar la variable después con `Remove-Item Env:SIEMBRA_CUENTAS_CARGA`.

## Documentación

- [docs/PRD.md](docs/PRD.md): problema, alcance, decisiones de arquitectura y bitácora de cambios de enfoque.
- [docs/PLAN.md](docs/PLAN.md): las dos etapas, el reparto y las fechas.
- [CLAUDE.md](CLAUDE.md) y [AGENTS.md](AGENTS.md): reglas para los agentes que trabajan en el repositorio.
- Referencia de la asignatura: [mexi-banco](https://github.com/OscarRuiz21/mexi-banco), con una rama por etapa.

## Etapa 01-monolito: módulo de Envíos y consulta de estado

Parte de Enrique (@Quique1409).

### Endpoints implementados

1. **`POST /api/envios`**:
   - Recibe: `alumnoId`, `problemaId`, `intentoId`, `lenguaje` y `codigo`.
   - Persiste el envío directamente en PostgreSQL con estado `EN_COLA` y fecha/hora del servidor (`now()`).
   - Responde `201 Created` con `{ "envioId": id, "estado": "recibido" }` solo después de confirmar en disco.
   - **Idempotencia y concurrencia**: ante reintentos o peticiones concurrentes con el mismo `intentoId` para la misma cuenta, captura la violación de unicidad `UNIQUE(cuenta_id, id_intento)`, recupera el envío original y responde `200 OK` con `{ "envioId": existenteId, "estado": "recibido" }` sin duplicar envíos.

2. **`GET /api/envios/{envioId}`**:
   - Si el envío no existe, responde `404 Not Found`.
   - Si la evaluación ya concluyó (hay registro en `resultados`), responde `200 OK` con `estado: "evaluado"` y el objeto `resultado` (`veredicto`, `tiempoMs`, `memoriaKb`, `detalle`).
   - Si el envío está siendo evaluado por un worker (hay registro en `leases_evaluacion` con `vence_en > now()`), deriva y responde `200 OK` con `estado: "evaluando"`.
   - En cualquier otro caso, responde `200 OK` con `estado: "en cola"`.

### Reglas de datos
- Envíos es el único dueño que escribe en `envios`.
- No escribe leases ni resultados (pertenecen a Evaluación).
- Solo lee de `cuentas`, `problemas`, `leases_evaluacion` y `resultados`.

## Etapa 01-monolito: front, cuentas de prueba y demostración

Parte de Diego (@diegocontrras). Con `docker compose up` corriendo, desde la raíz del repositorio:

**1. Cuentas de demo:** `alumno1` a `alumno5`, con ids fijos del 1 al 5. Las siembra la aplicación al arrancar; no hay que correr nada.

**2. Front:** `http://localhost:8080`. HTML y JavaScript sin framework, servido por Spring Boot desde `src/main/resources/static`. Pantallas: elegir alumno de demo, subir envío, ver estado y veredicto, y tabla de posiciones.

**3. Script de demostración** (Python 3, solo biblioteca estándar; recorre los puntos 1 a 6 llamando a la API):

```bash
python3 scripts/demo.py
```

Las soluciones por omisión del script suman dos enteros. Si el problema sembrado es otro, pasa las tuyas con `--correcta` y `--incorrecta`. En Windows el comando es `python scripts/demo.py`.

**Endpoint que expone esta parte:** `GET /api/cuentas-demo`, con `id`, `usuario` y `nombre` de cada cuenta.

### Riesgos declarados

- **Sin autenticación.** La identidad de demo no tiene contraseña ni token: cualquiera puede enviar con cualquier cuenta de demo.
- **Excepción temporal a la regla del dueño de datos.** La tabla `cuentas` es de Inicio de sesión, pero en 01 no hay inicio de sesión y la escribe la siembra de ese paquete al arrancar.
- **Posible envío duplicado.** Si el servidor guarda un envío pero la respuesta se pierde en todos los reintentos automáticos, el clic siguiente del alumno genera un `intentoId` nuevo y crea otro envío. Los reintentos automáticos sí reutilizan el mismo `intentoId`.
- **Contrato con otros módulos por confirmar.** El front y el script asumen el JSON de `POST/GET /api/envios`, `/api/concursos/activo` y `/api/concursos/activo/tabla` según los endpoints descritos en este README, y leen las respuestas de forma tolerante hasta que Enrique y Joshua fijen los nombres de campos.

## Etapa 01-monolito: Concursos, problemas y tabla de posiciones

Parte de Joshua (@JoshRozam). Con `docker compose up` corriendo, desde la raíz del repositorio:

**1. Concurso y problemas:** los siembra la aplicación al arrancar (`SiembraConcursos`, idempotente, con ids fijos). Carga "Distrinubs Round 1" en estado `ABIERTO` con el problema A ("Suma simple", límite 1000 ms, 256 MB y 100 puntos base) y 3 casos de prueba (casos 1 y 2 en el marcador provisional, caso 3 privado). En la etapa 1 el límite de memoria se guarda, pero no se aplica.

**2. Endpoints implementados:**

1. **`GET /api/concursos/activo`**:
   - Devuelve el concurso actualmente abierto con su lista de problemas ordenados por letra: letra, título, enunciado y límites de tiempo y memoria.
   - Si no hay concurso abierto, responde `404 Not Found`.

2. **`GET /api/concursos/activo/tabla`**:
   - Devuelve la tabla de posiciones provisional calculada mediante consultas agregadas sobre envíos y resultados.
   - **Reglas de puntuación y penalización:**
     - Minuto de la primera aceptación (desde el inicio del concurso) más 5 minutos por cada fallo previo a esa aceptación.
     - Los envíos posteriores a la primera aceptación no penalizan.
     - Problemas no resueltos no suman penalización.
     - Un envío con `ERROR_SERVIDOR` no cuenta como fallo: el servidor no pudo evaluarlo.
   - **Orden y desempate:**
     1. Problemas resueltos descendente (`resueltos DESC`).
     2. Menor penalización total ascendente (`penalizacion ASC`).
     3. Desempate por tiempo: tiempo de llegada al servidor del último envío aceptado ascendente (`tiempo_ultimo_aceptado ASC`).
     4. Empates absolutos comparten la misma posición.
   - **Vigilancia de retraso**: si el retraso de envíos sin evaluar supera 5 minutos (300 segundos), se marca `avisoOrganizadores: true` y se registra una advertencia en los logs para los organizadores.
   - Incluye a todas las cuentas registradas en la tabla provisional.

### Reglas de datos
- Concursos y problemas es el único dueño que escribe en `concursos`, `problemas` y `casos_prueba`.
- Tabla de posiciones no tiene tabla propia en la etapa 1: solo lee de `envios`, `resultados`, `problemas`, `cuentas` y `concursos`.

