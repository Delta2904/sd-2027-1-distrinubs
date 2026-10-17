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

Requisito: Docker con Docker Compose. No hace falta tener Java, Maven ni Python instalados: la aplicación se compila dentro de Docker y su imagen trae Python 3.

```bash
git checkout 01-monolito
docker compose up -d --build

# Siembra (idempotente: se puede correr varias veces sin duplicar)
docker compose exec -T db psql -U distrinubs -d distrinubs -v ON_ERROR_STOP=1 < scripts/siembra-cuentas.sql
docker compose exec -T db psql -U distrinubs -d distrinubs -v ON_ERROR_STOP=1 < scripts/siembra-concursos.sql
```

- **Entrada:** `http://localhost:8080`. Salud de la aplicación: `http://localhost:8080/actuator/health`.
- **Puerto ocupado:** si otro proyecto usa el 8080, levanta con otro puerto: `APP_PUERTO=8081 docker compose up -d` en bash, o `$env:APP_PUERTO=8081; docker compose up -d` en PowerShell. La base se publica en `127.0.0.1:5432` y se cambia igual, con `DB_PUERTO_LOCAL`.
- **Empezar de cero:** `docker compose down -v` borra el volumen de la base, incluidos envíos y resultados. Sin `-v`, los datos se conservan.
- **Pruebas unitarias**, sin Java instalado: `docker build --target construccion -t distrinubs-pruebas .` y luego `docker run --rm distrinubs-pruebas mvn -B -q test`.

## Scripts

| Script | Comando | Qué hace |
|---|---|---|
| `scripts/siembra-cuentas.sql` | `docker compose exec -T db psql -U distrinubs -d distrinubs -v ON_ERROR_STOP=1 < scripts/siembra-cuentas.sql` | Carga 5 cuentas de demo (`alumno1` a `alumno5`) con ids fijos del 1 al 5 |
| `scripts/siembra-concursos.sql` | `docker compose exec -T db psql -U distrinubs -d distrinubs -v ON_ERROR_STOP=1 < scripts/siembra-concursos.sql` | Carga "Distrinubs Round 1" abierto, con el problema A ("Suma simple") y 3 casos de prueba |
| `scripts/demo.py` | `python3 scripts/demo.py`, o sin Python instalado: `docker compose exec -T app python3 - < scripts/demo.py` | Recorre los puntos 1 a 6 del alcance contra la API: concurso, cuentas, envío con reintento, Wrong Answer, Accepted y tabla. Necesita las dos siembras |

## Qué hace la etapa 1

Se verificó de punta a punta el 17/10/2026 con `scripts/demo.py`. Los veredictos que el script no cubre se probaron a mano.

1. Concurso "Distrinubs Round 1" abierto, con un problema y tres casos de prueba, cargado por la siembra.
2. Identidad de demo: el alumno se elige de una lista de cuentas de prueba y su id viaja con el envío.
3. Subir envío: responde "recibido" con su identificador solo después de guardarlo. Un reintento con el mismo `intentoId` devuelve el mismo envío.
4. Evaluación con un solo worker: toma el envío con un lease, corre el código en Python 3 con límite de tiempo y guarda el veredicto: Accepted, Wrong Answer, Time Limit Exceeded, Runtime Error o error de compilación. Si la aplicación se cae a media evaluación, el envío se retoma cuando vence su lease y se evalúa una sola vez.
5. Ver el estado ("en cola", "evaluando", "evaluado") y el veredicto del envío.
6. Tabla provisional con problemas resueltos y penalización: minuto de la aceptación más 5 minutos por cada fallo previo.
7. Front pequeño en HTML y JavaScript: elegir alumno, subir envío, ver estado y tabla.
8. Script de demostración que recorre los puntos 1 a 6.

## Qué no hace la etapa 1

- Inicio de sesión con contraseña o tokens, su revocación y su validación en la subida.
- Sandbox con un contenedor nuevo por envío.
- Varios lenguajes: solo Python 3.
- Límite de memoria: se guarda en el problema, pero no se aplica.
- Varios workers y renovación de lease.
- Evaluación final con todos los casos e invalidación al cierre. En vivo solo corren los casos del marcador provisional.
- Verificación de la solución de referencia: los casos se revisaron a mano.
- Dificultad por resueltos y desempate completo.
- Roles, bloqueos, registro de acciones, interfaz de organizador, panel de la cola y lista de envíos para organizadores.

## Pendiente

- El tope de compilación (PRD, sección 10). En 01 hay una salvaguarda provisional de 10 s que no lo decide.
- La recompilación en un worker limpio. Hoy, un envío cuya revisión de sintaxis se cuelga se reintenta cada vez que vence su lease, sin límite de intentos.
- La prueba de carga del marcador en vivo, que debe confirmar el límite de 5 minutos.
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
- **Excepción temporal en la siembra de cuentas.** La tabla `cuentas` es de Inicio de sesión, pero en 01 ese módulo no existe y la escribe `scripts/siembra-cuentas.sql` (PRD, sección 0, punto 2).
- **Accepted solo con el subconjunto.** Sin evaluación final, un envío que falla solo un caso privado queda como Accepted en 01 (PRD, bitácora 8).

## Estructura de paquetes

Un paquete por módulo de la sección 6 del PRD, dentro de `mx.distrinubs`. Los módulos se llaman entre sí con código Java en el mismo proceso, sin HTTP. Cada `package-info.java` dice qué tablas escribe y cuáles lee el módulo.

| Paquete | Módulo | Responsable en 01 | Escribe |
|---|---|---|---|
| `iniciosesion` | Inicio de sesión | Diego (cuentas de demo) | `cuentas` (en 01, solo la siembra) |
| `envios` | Envíos | Enrique | `envios` |
| `tablaposiciones` | Tabla de posiciones | Joshua | Nada: el marcador se calcula al consultarlo |
| `evaluacion` | Evaluación de código | Emiliano | `leases_evaluacion`, `resultados` |
| `concursos` | Concursos y problemas | Joshua | `concursos`, `problemas`, `casos_prueba` |
| `administracion` | Administración | No entra en 01 | Nada |
| `basedatos` | Base de datos | Emiliano | El esquema, con migraciones en `src/main/resources/db/migration` |

El front está en `src/main/resources/static` y lo sirve Spring Boot.

## Base de datos

PostgreSQL 17 en un contenedor, con el volumen persistente `distrinubs_datos`. El esquema lo crea Flyway al arrancar la aplicación (PRD, bitácora 1). Hibernate no crea ni modifica tablas.

- **Migraciones:** cada dueño de datos escribe las de sus tablas, con el nombre `V<AAAAMMDD>_<HHMM>__<tabla>_<cambio>.sql`. Una migración ya aplicada no se edita: se agrega otra (PRD, bitácora 2).
- **Ids de la siembra:** `cuentas`, `concursos`, `problemas` y `casos_prueba` reciben ids fijos del 1 al 999 y se hace upsert por id. Sus secuencias empiezan en 1000, así lo que inserte la aplicación no choca con la siembra. `envios`, `leases_evaluacion` y `resultados` no se siembran: su id siempre lo pone la base.
- **Restricciones que sostienen las reglas del PRD:**
  - `envios_un_envio_por_intento (cuenta_id, id_intento)`: un reintento no crea un segundo envío.
  - `leases_evaluacion_un_lease_por_envio`: un solo lease por envío.
  - `resultados_un_resultado_por_envio`: un envío no cuenta dos veces.

  El dueño de cada tabla queda como comentario de la tabla. Se ve con `docker compose exec db psql -U distrinubs -c '\dt+'`.

## Etapa 01-monolito: Evaluación de código

Parte de Emiliano (@Delta2904), en el paquete `evaluacion`.

- **Dónde corre:** dentro de la aplicación, como un componente programado. No es un contenedor aparte (PRD, bitácora 7). Revisa la cola cada segundo y la vacía antes de volver a esperar. Hay un solo hilo, así que hay un solo worker.
- **Cómo toma un envío:**
  - Toma el envío en estado `EN_COLA` más antiguo que no tenga resultado ni lease vigente.
  - Lo marca con un lease en `leases_evaluacion` que dura 10 s + (casos × límite de tiempo) + 10 s, sin renovación.
  - Si el lease anterior ya venció, retoma la misma fila y suma `veces_tomado`.
  - "Evaluando" no se guarda en ningún lado: Envíos lo deriva del lease vigente. Evaluación no llama a Envíos.
- **Cómo juzga:**
  1. Revisa la sintaxis con `py_compile`. Si falla, el veredicto es error de compilación y el mensaje queda en `detalle`.
  2. Corre los casos del marcador provisional (o todos, si el problema no marcó ninguno) en orden, cada uno con el límite de tiempo del problema.
  3. Se detiene en el primer caso con Time Limit Exceeded, Runtime Error (salida con código distinto de 0) o Wrong Answer.
  4. La salida se compara sin los espacios al final de cada línea ni las líneas vacías al final.
  5. Con WA, TLE y RE el alumno solo ve el veredicto, sin mensaje ni número de caso (PRD, bitácora 9).

  `tiempo_ms` es el mayor tiempo entre los casos e incluye el arranque de Python.
- **Cómo guarda:** escribe la fila de `resultados` solo si todavía tiene el lease que tomó: mismo worker y mismo `veces_tomado`. La unicidad por envío impide un segundo resultado.
- **Si falla el servidor:** si no puede lanzar Python, si la revisión de sintaxis pasa de 10 s o si el problema no tiene casos, no guarda resultado. El envío vuelve a la cola cuando vence el lease y no se penaliza al alumno.
- **Configuración** (`application.yml`, sección `evaluacion`): `activa`, `python`, `intervalo-ms`, `salvaguarda-compilacion-ms` y `margen-lease-ms`.

## Documentación

- [docs/PRD-Distrinubs.md](docs/PRD-Distrinubs.md): problema, alcance, decisiones de arquitectura, estado de cada parte y bitácora de cambios de enfoque (sección 11).
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

### Reglas de datos (Sección 6 del PRD)
- Envíos es el único dueño que escribe en `envios`.
- No escribe leases ni resultados (pertenecen a Evaluación).
- Solo lee de `cuentas`, `problemas`, `leases_evaluacion` y `resultados`.
## Etapa 01-monolito: front, cuentas de prueba y demostración

Parte de Diego (@diegocontrras). Con `docker compose up` corriendo, desde la raíz del repositorio:

**1. Sembrar las cuentas de demo** (idempotente: ids fijos del 1 al 5, se puede correr varias veces):

```bash
docker compose exec -T db psql -U distrinubs -d distrinubs -v ON_ERROR_STOP=1 < scripts/siembra-cuentas.sql
```

**2. Front:** `http://localhost:8080`. HTML y JavaScript sin framework, servido por Spring Boot desde `src/main/resources/static`. Pantallas: elegir alumno de demo, subir envío, ver estado y veredicto, y tabla de posiciones.

**3. Script de demostración** (Python 3, solo biblioteca estándar; recorre los puntos 1 a 6 llamando a la API):

```bash
python3 scripts/demo.py
```

Las soluciones por omisión del script suman dos enteros. Si el problema sembrado es otro, pasa las tuyas con `--correcta` y `--incorrecta`. Requiere el concurso sembrado y el worker de evaluación activo.

**Endpoint que expone esta parte:** `GET /api/cuentas-demo`, con `id`, `usuario` y `nombre` de cada cuenta.

### Riesgos declarados

- **Sin autenticación.** La identidad de demo no tiene contraseña ni token: cualquiera puede enviar con cualquier cuenta de demo.
- **Excepción temporal a la regla del dueño de datos.** La tabla `cuentas` es de Inicio de sesión, pero en 01 ese módulo no existe y `scripts/siembra-cuentas.sql` la escribe (PRD, sección 0, punto 2).
- **Posible envío duplicado.** Si el servidor guarda un envío pero la respuesta se pierde en todos los reintentos automáticos, el clic siguiente del alumno genera un `intentoId` nuevo y crea otro envío. Los reintentos automáticos sí reutilizan el mismo `intentoId`.
- **Contrato con otros módulos por confirmar.** El front y el script asumen el JSON de `POST/GET /api/envios`, `/api/concursos/activo` y `/api/concursos/activo/tabla` según la tabla de endpoints del PRD, y leen las respuestas de forma tolerante hasta que Enrique y Joshua fijen los nombres de campos.

## Etapa 01-monolito: Concursos, problemas y tabla de posiciones

Parte de Joshua (@JoshRozam). Con `docker compose up` corriendo, desde la raíz del repositorio:

**1. Sembrar el concurso y problemas** (idempotente: ids fijos, se puede correr varias veces sin duplicar ni romper nada):

```bash
docker compose exec -T db psql -U distrinubs -d distrinubs -v ON_ERROR_STOP=1 < scripts/siembra-concursos.sql
```

Carga "Distrinubs Round 1" en estado `ABIERTO` con el problema A ("Suma simple", límite 1000 ms, 256 MB y 100 puntos base) y 3 casos de prueba (casos 1 y 2 en el marcador provisional, caso 3 privado). En la etapa 1 el límite de memoria se guarda, pero no se aplica.

**2. Endpoints implementados:**

1. **`GET /api/concursos/activo`**:
   - Devuelve el concurso actualmente abierto con su lista de problemas ordenados por letra: letra, título, enunciado y límites de tiempo y memoria.
   - Si no hay concurso abierto, responde `404 Not Found`.

2. **`GET /api/concursos/activo/tabla`**:
   - Devuelve la tabla de posiciones provisional calculada mediante consultas agregadas sobre envíos y resultados.
   - **Reglas de puntuación y penalización** (PRD secciones 5 y 9):
     - Minuto de la primera aceptación (desde el inicio del concurso) más 5 minutos por cada fallo previo a esa aceptación.
     - Los envíos posteriores a la primera aceptación no penalizan.
     - Problemas no resueltos no suman penalización.
   - **Orden y desempate** (PRD bitácora 6):
     1. Problemas resueltos descendente (`resueltos DESC`).
     2. Menor penalización total ascendente (`penalizacion ASC`).
     3. Desempate por tiempo: tiempo de llegada al servidor del último envío aceptado ascendente (`tiempo_ultimo_aceptado ASC`).
     4. Empates absolutos comparten la misma posición.
   - **Vigilancia de retraso**: si el retraso de envíos sin evaluar supera 5 minutos (300 segundos), se marca `avisoOrganizadores: true` y se registra una advertencia en los logs para los organizadores.
   - Incluye a todas las cuentas registradas en la tabla provisional.

### Reglas de datos (Sección 6 del PRD)
- Concursos y problemas es el único dueño que escribe en `concursos`, `problemas` y `casos_prueba`.
- Tabla de posiciones no tiene tabla propia en la etapa 1: solo lee de `envios`, `resultados`, `problemas`, `cuentas` y `concursos`.

