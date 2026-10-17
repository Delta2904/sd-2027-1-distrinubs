# PRD · Distrinubs

**Equipo:** Calderón Gutiérrez Victor Emiliano (@Delta2904), Contreras del Ángel Diego Adrían (@diegocontrras), Medrano Solano Enrique (@Quique1409), Rodríguez Zamora Joshua (@JoshRozam)
**Fecha:** 09/10/2026 · revisión 15/10/2026

---

## 0. Instrucciones para los agentes y estado del proyecto

Cualquier agente que trabaje en una parte del proyecto lee primero esta sección, las secciones 5 a 9 y la fila de su parte en la tabla de estado.

**Etapa actual: 01-monolito.** Esta etapa es un monolito: una sola aplicación, en un solo proceso, con sus siete módulos y una sola base de datos. **Está prohibido en esta etapa** crear servicios separados o llamadas HTTP entre módulos, gateway, directorio, balanceador o réplicas, cola externa (RabbitMQ o Kafka) y Redis. Si alguien cree que una de estas piezas hace falta, lo propone en la bitácora de la rama antes de implementarla.

**Estado de las partes.** Actualiza esta tabla al terminar cada parte.

| Parte | Rama | Estado | Alcance | Pendiente |
|---|---|---|---|---|
| Monolito | 01-monolito | En curso (entrega del sábado 17) | Los siete módulos de la sección 6 en una aplicación, con PostgreSQL y Docker Compose, con el alcance mínimo que se indica arriba | Lo que no entra en el alcance mínimo; tope de compilación; prueba de carga del marcador en vivo |
| Concursos, problemas y tabla de posiciones | 01-monolito (puntos 1 y 6 del alcance) | Completado | Siembra idempotente en `scripts/siembra-concursos.sql` ("Distrinubs Round 1"), `GET /api/concursos/activo` y `GET /api/concursos/activo/tabla` (con penalización de 5 min por fallo, desempate por tiempo de último aceptado y aviso por retraso > 5 min) | Dificultad por resueltos y desempate completo para etapas posteriores |
| Envíos y estado del envío | 01-monolito (puntos 3 y 5 del alcance) | Completado | Endpoints `POST /api/envios` (persistencia directa en PostgreSQL como cola, idempotencia por `intentoId` y respuesta 201 "recibido") y `GET /api/envios/{envioId}` (derivación de "evaluando" por lease vigente y objeto `resultado`) | Validación de tokens y migración a cola con broker en la etapa 2 |
| Separación en servicios | 02-separacion | Pendiente | Módulos como servicios, con llamadas HTTP y una cola entre envíos y evaluación | Elegir RabbitMQ o Kafka; orden respecto a las siguientes etapas |
| Gateway, réplicas y balanceador | Por definir | Pendiente | Gateway en varias réplicas, balanceador en contenedor por conexiones activas | Herramienta del balanceador; framework de Java |
| Front pequeño | 01-monolito (punto 7 del alcance) | Tecnología decidida para la etapa 1: HTML y JavaScript sin framework | Selección de alumno de demo, subir envío, estado y tabla. El panel de la cola, la lista de envíos para organizadores e inicio de sesión quedan pendientes | Confirmar que el equipo acepta el alcance |
| Front en React | Etapa posterior, cuando el front se separe del back | Decidido: React | Mismas pantallas, sobre el front ya probado | Cuándo entra en el plan de ramas |
| Inicio de sesión y Administración | Etapa posterior (propuesta: primera pieza de la etapa 2) | Pendiente; fuera de 01 | Credenciales, tokens, revocación, validación en la subida, roles y bloqueos | Confirmar la etapa; por ahora en la 01 no se implementa |
| Sesiones en Redis | Por definir | Pendiente | Inicio de sesión como servicio aparte | Cuándo entra en el plan de ramas |
| Partes opcionales | Por definir | Opcional | Cloud o demostraciones más complejas, solo si sobra tiempo | Qué se elige y cuándo |

**Entregables del curso.** El trabajo se organiza por las entregas que pide el profesor. La entrega vigente define el alcance de las partes que toca.

| Entrega | Fecha límite | Qué se pide |
|---|---|---|
| Repo, `main` y `01-monolito` | Sábado 17 de octubre, 07:00 | Repo `sd-2027-1-distrinubs` con el colaborador OscarRuiz21. En `main`: README.md (qué es, quiénes son, cómo se levanta) y `docs/PRD.md`. En `01-monolito`: la aplicación con su base de datos y su `docker-compose.yml`, que corra y tenga las operaciones principales del PRD. Después, respuesta en la Discussion #44 con la liga al repo |
| Etapa 2, `02-separacion` | Por anunciar | Por definir cuando el profesor lo publique |

**Responsables (confirmados por el equipo).** Cada integrante es responsable de su parte, de sus commits y de usar sus propios agentes para hacerla. En la defensa se nota quién hizo qué en el historial de commits. La carga relativa, de mayor a menor, es: Emiliano, Joshua, Enrique, Diego.

| Integrante | Responsable de | Depende de |
|---|---|---|
| Emiliano (@Delta2904) | Base de datos (esquema, `docker-compose.yml`), Evaluación de código (compilar o interpretar, casos, veredicto, tiempo y memoria, leases), `main` (README y `docs/PRD.md`) e integración de la demo. Coordina el proyecto | Nadie: entrega primero el esquema |
| Diego (@diegocontrras) | Front pequeño, cuentas de prueba para la identidad de demo y script de demostración. Inicio de sesión y Administración pasan a una etapa posterior | El esquema de Emiliano |
| Enrique (@Quique1409) | Envíos (recibir el código, guardar con su identificador y el del alumno de demo, responder "recibido") | El esquema de Emiliano; el contrato de envíos lo define él y lo usa Evaluación |
| Joshua (@JoshRozam) | Concursos y problemas (carga, casos, límites) y Tabla de posiciones (marcador provisional) | El esquema de Emiliano |

**Alcance de `01-monolito` (recomendación, por confirmar en equipo).** La entrega pide que el monolito corra y tenga las operaciones principales. Se propone un flujo completo y estrecho en lugar de muchas piezas a medias: el alumno entra, sube una solución, la evaluación la juzga y la tabla la refleja. Así se puede demostrar de punta a punta en la defensa.

**Entra en 01:**

1. Concurso "Distrinubs Round 1" con un problema y dos o tres casos de prueba, cargado con un script de siembra y en estado abierto (ver "Creación de concursos" en la sección 5). (Joshua - Implementado)
2. Identidad de demo: el alumno se elige de una lista de cuentas de prueba cargadas por el script de siembra, y su identificador viaja con el envío. No hay contraseña ni token. Se declara como riesgo. Como el módulo de inicio de sesión no está en 01, el script de siembra escribe esas cuentas: es una excepción temporal a la regla del dueño de datos y se declara en el README de la rama. (Diego)
3. Subir envío: responde "recibido" con su identificador solo después de guardarlo. Un reintento con el mismo identificador devuelve el mismo envío. (Enrique - Implementado)
4. Evaluación con un solo worker: toma el envío con un lease simple (columna con vencimiento), ejecuta el código con límite de tiempo y devuelve veredicto: Accepted, Wrong Answer, Time Limit Exceeded o error de compilación. (Emiliano)
5. Ver estado y veredicto del envío. (Enrique - Implementado)
6. Tabla provisional con problemas resueltos y penalización, con 5 minutos por fallo previo a la aceptación. (Joshua - Implementado)
7. Front pequeño con selección de alumno de demo, subir envío, estado y tabla. (Diego)
8. Script de demostración que recorra los puntos 1 a 6. (Diego)

Para los alumnos, en 01 se usa un solo lenguaje: Python 3, por ser el más directo de ejecutar con límite de tiempo.

**No entra en 01 (queda pendiente y se declara en el README de la rama):** inicio de sesión con credenciales y tokens, su revocación y su validación en la subida, que pasan a una etapa posterior (ver la tabla de estado); sandbox con contenedor nuevo por envío; varios lenguajes desde la tabla de configuración; límite de memoria; varios workers y renovación de lease; verificación de la solución de referencia; invalidación al cierre; dificultad por resueltos; desempate completo; roles y bloqueos; registro de acciones; creación de concursos desde una interfaz de organizador (en 01 los crea la siembra); panel de la cola y lista de envíos para organizadores. Sin sandbox, el código de los alumnos corre sin aislamiento en la computadora del equipo. Eso se declara como riesgo y no se presenta como condición de producción.

**Lo que se entrega el sábado 17:**

- `main`: README.md (qué es, quiénes son, cómo se levanta con `docker compose up`) y `docs/PRD.md`.
- `01-monolito`: la aplicación, PostgreSQL, `docker-compose.yml`, un README de la rama (qué hace, qué no hace, pendientes) y un script de demostración que recorra los puntos 1 a 6.
- Respuesta en la Discussion #44 con la liga al repo.

**Reglas para el agente:**

1. **Decisiones.** Antes de implementar algo, revisa la sección 9. Si lo pedido contradice una decisión, detente y pregunta. No la cambies por tu cuenta.
2. **Dueños de datos.** Respeta la tabla de la sección 6. Si un módulo necesita un dato que no le pertenece, pide el cambio al dueño; no escribas en su tabla.
3. **Antes de cada etapa.** Usa una skill para afinar el enfoque: `metodo-socratico` para decidir cómo se construye la etapa, y `product-manager` si la etapa agrega, quita o cambia funcionalidades. Registra en este documento lo que se decidió.
4. **El PRD es vivo.** Los cambios de funcionalidades se hacen con `product-manager` y se reflejan aquí. Lo que se aprende en clase también puede cambiar una decisión: se revisa en la sección 9 antes de tocar el código.
5. **Opcionales.** Las partes marcadas como opcionales solo empiezan cuando la parte obligatoria corre y está documentada. Su objetivo es demostrar lo aprendido en el curso.
6. **Preguntas abiertas.** Lo que está en la sección 10 se trata como no decidido. No lo implementes como si estuviera resuelto.
7. **Cierre de cada rama.** El README de la rama declara qué hace, qué no hace y qué queda pendiente. La tabla de estado de este documento se actualiza en la misma entrega.
8. **Bitácora de cambios.** A partir de la rama `01-monolito`, todo cambio de enfoque, lo proponga quien lo proponga, se registra en la sección 11 del PRD de esa rama antes de implementarse. Si cambia una decisión, también se actualiza su fila en la sección 9. Si durante la implementación alguien decide otra cosa, lo registra en la misma rama, en el mismo commit que el código. Sin entrada en la bitácora, el cambio no está aprobado. En `main` no se usa la bitácora.

---

## 1. Problema y usuarios

Una universidad necesita un sistema de concursos de programación para competencias internas. Los usuarios son:

- **Alumnos**, que envían código y consultan la tabla de posiciones.
- **Organizadores**, que preparan y operan el concurso.
- **Administradores**, que gestionan cuentas e infraestructura.

El peor momento de uso es durante el concurso. Si el sistema cae, los alumnos no pueden iniciar sesión, subir código ni leer problemas.

## 2. Por qué es un sistema distribuido

Un solo servidor no cubre tres necesidades:

- Que la evaluación siga funcionando si un proceso falla, sin perder envíos.
- Que la carga de 200 alumnos enviando a la vez se reparta entre varios evaluadores.
- Que los resultados no se pierdan aunque se reinicie el sistema.

En la etapa 1 todo corre en una sola computadora con contenedores. La separación en servicios, con su red y sus fallas parciales, llega en etapas posteriores.

## 3. Objetivos y lo que no vamos a hacer

**Objetivos:**
- Evaluar cada envío de forma correcta y bajo límites estrictos de tiempo y memoria.
- No perder envíos aceptados ni resultados.
- Mantener un marcador provisional en vivo y un marcador final al cierre.

**No vamos a hacer:**
- Marcador en vivo exacto (se usa un subconjunto de pruebas).
- Pausar el reloj por fallas de autenticación.
- Rechazar envíos por saturación.
- Consenso en el gateway.
- Atar a un alumno a una instancia específica.
- Demostración con varias ubicaciones ni servidores en la nube (queda para etapas posteriores).

## 4. El requisito que perseguimos y lo que dejamos atrás

**Perseguimos:** evaluación correcta y consistente. Ningún envío aceptado se pierde ni cuenta dos veces. Un envío es "recibido" solo después de quedar guardado en disco.

**Dejamos atrás:** la disponibilidad durante una caída de la computadora. Si la máquina se apaga o se reinicia, el sistema no responde hasta que vuelva a arrancar, incluida la lectura de problemas. Los envíos ya recibidos sobreviven y se evalúan al regresar.

## 5. Historias de usuario

### Funcionalidad 1: Iniciar sesión (alumno)

*Como alumno, quiero iniciar sesión para que mi sesión quede abierta durante el concurso.*

- Las credenciales válidas entregan un token firmado. Las inválidas se rechazan sin revelar si el usuario existe.
- El token sirve durante todo el concurso, salvo que sea revocado.
- Los envíos con un token revocado se rechazan al recibirlos.

### Funcionalidad 2: Subir solución y ver su estado (alumno)

*Como alumno, quiero subir mi solución, ver su estado y su veredicto.*

- La subida responde "recibido" con un identificador solo después de quedar guardada en disco.
- Cada clic en "enviar" genera un identificador de intento. Un reintento automático del mismo intento reutiliza ese identificador y devuelve el mismo envío, sin crear uno nuevo ni cobrar penalización.
- Los estados que ve el alumno son: recibido, en cola, evaluando y resultado.
- El resultado muestra Accepted, Wrong Answer, Time Limit Exceeded, Memory Exceeded o error de compilación.
- Un fallo del servidor no penaliza al alumno. Si el servidor se cae antes de responder "recibido", el alumno ve un error claro y vuelve a subir.

### Funcionalidad 3: Ver la tabla de posiciones (alumno)

*Como alumno, quiero ver la tabla de posiciones con todos los participantes, incluyéndome.*

- La tabla muestra problemas resueltos, penalización total y el detalle por problema, ordenados por las reglas de desempate.
- Mi fila muestra, por problema, si está resuelto, cuántos intentos fallidos tiene y su penalización.
- El marcador provisional no se atrasa más de 5 minutos. Si se supera, los organizadores reciben aviso.
- Al cierre, el marcador final es el oficial. Si un envío se invalida, el cambio aparece con su penalización recalculada.
- Los empates totales se muestran como posición compartida.

### Funcionalidad 4: Preparar el concurso (organizador)

*Como organizador, quiero crear el concurso con sus problemas, casos de prueba y límites, verificados antes del inicio.*

- Cada problema tiene límite de tiempo, límite de memoria, puntos base y casos privados. Los casos del marcador en vivo son un subconjunto marcado.
- Antes del inicio, la solución de referencia debe pasar dentro de los límites. Si no pasa, el problema no se publica.
- Un problema verificado no puede modificarse sin que el sistema marque la verificación como invalidada.

### Creación de concursos

Un concurso tiene un nombre, una ventana de tiempo y una lista ordenada de problemas.

- **Nombre:** libre y único. La convención es "Distrinubs Round N", con N consecutivo: "Distrinubs Round 1", "Distrinubs Round 2". Es el nombre que ve el alumno. El sistema le asigna un identificador interno numérico, que es el que usan las tablas.
- **Problemas:** cada uno tiene una letra (A, B, C…), título, enunciado, límite de tiempo, límite de memoria, puntos base, casos de prueba privados y el subconjunto de casos que alimenta el marcador provisional.
- **Ventana:** hora de inicio y hora de fin. Los envíos se aceptan dentro de la ventana, y la hora de llegada al servidor define su orden.

Estados del concurso:

1. **Borrador:** se edita libremente y no es visible para los alumnos.
2. **Verificado:** todos sus problemas pasaron la solución de referencia dentro de sus límites. Cualquier cambio a un problema verificado lo devuelve a borrador.
3. **Abierto:** dentro de su ventana acepta envíos y muestra el marcador provisional. No se edita.
4. **Cerrado:** no acepta envíos. Se publica la evaluación final cuando terminan los envíos en cola.

Quién lo crea: el organizador (funcionalidad 4). En la etapa 1 no hay interfaz de organizador: el script de siembra crea "Distrinubs Round 1" con su problema y sus casos, y lo deja abierto. La verificación de la solución de referencia no entra en 01, así que los casos se revisan a mano. Se declara como riesgo.

### Funcionalidad 5: Operar el concurso en vivo (organizador)

*Como organizador, quiero vigilar el concurso mientras ocurre y cerrarlo a tiempo.*

- Veo un panel de la cola: envíos en cola, envíos en evaluación, workers activos y retraso. Recibo aviso si el retraso supera 5 minutos.
- Veo una lista de envíos recientes con código, estado, veredicto y hora de llegada.
- La evaluación final no se publica hasta que terminen todos los envíos. Antes puedo revisar los cambios, incluidos los envíos invalidados.

### Funcionalidad 6: Gestionar cuentas (administrador)

*Como administrador, quiero crear, bloquear y asignar roles a las cuentas.*

- Bloquear una cuenta revoca sus tokens. Sus envíos ya encolados se evalúan y quedan visibles como pendientes.
- Solo el administrador puede cambiar roles o bloquear cuentas.

### Funcionalidad 7: Operar la infraestructura (administrador)

*Como administrador, quiero vigilar el sistema y reaccionar ante fallas sin afectar el marcador.*

- Veo el estado del gateway, los workers, la base de datos y el balanceador.
- Puedo agregar o retirar workers sin interrumpir las evaluaciones en curso.
- Toda acción queda registrada con usuario, hora y motivo.

---

## 6. Del monolito a los servicios

### Monolito (etapa 1)

Siete módulos:

1. **Inicio de sesión:** credenciales, tokens y sesiones.
2. **Envíos:** recibe el código, valida el token, lo guarda y lo deja en espera.
3. **Tabla de posiciones:** calcula el marcador provisional y el final.
4. **Evaluación de código:** toma envíos de la cola con un lease, compila o interpreta, ejecuta los casos de prueba y registra el resultado.
5. **Concursos y problemas:** crea concursos, carga problemas y verifica casos antes del inicio.
6. **Administración:** cuentas, roles, bloqueos e infraestructura.
7. **Base de datos:** almacena datos y metadatos.

En la etapa 1, la cola de envíos vive en PostgreSQL. Los estados "recibido" y "en cola" se guardan; "evaluando" no se guarda, se deriva de que el envío tenga un lease vigente.

### Dueños de datos

Cada dato tiene un solo módulo que escribe:

| Dato | Dueño (escribe) | Lo leen |
|---|---|---|
| Cuentas, contraseñas, roles y tokens | Inicio de sesión | Administración, Envíos |
| Concursos, problemas, casos y límites | Concursos y problemas | Evaluación, Tabla |
| Envíos (código, identificador de intento, hora de llegada, estado "recibido"/"en cola") | Envíos | Evaluación, Tabla |
| Leases de evaluación | Evaluación | Envíos (deriva "evaluando"), Administración |
| Resultados por envío y su invalidación | Evaluación | Tabla |
| Marcador y dificultad final | Tabla de posiciones | Alumnos y organizadores |
| Registro de acciones | Administración | Organizadores |

Administración no escribe cuentas directamente: pide los cambios a Inicio de sesión.

### Servicios resultantes

Los mismos módulos separados como servicios, con una cola entre envíos y evaluación. El tipo de cola (RabbitMQ o Kafka) se decide en esa etapa.

### Plan de ramas

Cada etapa vive en su propia rama, con su README y su docker-compose.yml:

1. **01-monolito:** los siete módulos en una aplicación, con PostgreSQL, cola en la base y Docker Compose.
2. **02-separacion:** los módulos como servicios, con llamadas por HTTP y la cola entre envíos y evaluación.
3. **Etapas posteriores:** gateway con réplicas, balanceador, sesiones en Redis cuando inicio de sesión sea un servicio aparte. El orden exacto de estas etapas está por definir.

---

## 7. Dónde vive cada pieza

| Pieza | Dónde vive | Por qué | Qué pasa si falla |
|---|---|---|---|
| API gateway | Varias réplicas en Docker Compose (`--scale`), sin estado | Sin estado, no necesita consenso; la redundancia evita un punto único en el gateway | Si cae una réplica, las demás siguen atendiendo. Si se cae la computadora, el sistema no responde hasta reiniciar |
| Directorio / DNS | No aplica en la etapa 1. Los alumnos entran por `http://localhost:8080` | Es una demostración en una sola máquina; no hay nombres que resolver | Si la máquina se reinicia, la URL vuelve a funcionar al arrancar |
| Balanceo | Un contenedor balanceador aparte (herramienta por decidir), delante de las réplicas del gateway, que reparte por conexiones activas | Cumple la decisión de la sección 9 sin trabajo extra | Si cae el balanceador, no hay entrada hasta que Compose lo reinicie |
| Datos durables | PostgreSQL en un contenedor con volumen persistente, sin réplica | Un solo lugar donde vive el estado: envíos, resultados, leases y marcador | Si la máquina se reinicia, lo confirmado en disco sobrevive. Los envíos en evaluación recuperan su lease cuando expira |
| Sesiones | PostgreSQL en la etapa 1. Redis en etapas posteriores | Una sola fuente de verdad en la etapa 1; Redis cuando inicio de sesión sea un servicio aparte | Si la base se reinicia, los alumnos vuelven a iniciar sesión |
| Operaciones entre servicios | En la etapa 1 no hay llamadas de red: la cola es una tabla de PostgreSQL. En la etapa 2, una cola entre servicios transporta identificadores; el estado sigue en PostgreSQL | Desacopla gateway y evaluación cuando se separan | Etapa 1: un envío en evaluación vuelve a la cola cuando su lease expira |
| Manejo de fallas | Lease con renovación escrito por Evaluación; resultado único por envío (restricción de unicidad); tope fijo de compilación con reintento en worker limpio; identificador por intento de envío | Ningún envío se pierde ni cuenta dos veces | El envío vuelve a la cola; las fallas del servidor no penalizan al alumno |

---

## 8. Stack y cómo se levanta

- **Lenguaje y framework del back:** Java 21 con Spring Boot 3.3.x, la misma línea que usa la referencia de la materia. Spring Web para la API, Spring Data JPA para la base, Validation y Actuator. Las dependencias de Spring Cloud entran en las etapas de gateway y directorio.
- **Lenguajes de los alumnos:** varios. Cada uno tiene su compilador o intérprete y sus límites en una tabla de configuración.
- **Aislamiento del código:** contenedor sin privilegios, sin red, sistema de archivos de solo lectura salvo un directorio temporal, y límites de memoria, CPU y procesos. Un contenedor nuevo por envío, destruido al terminar.
- **Base de datos:** PostgreSQL en un solo contenedor con volumen persistente. Sin Patroni, etcd ni réplicas en la etapa 1. El esquema se crea con migraciones de Flyway (bitácora 1 y 2).
- **Construcción:** Maven dentro de Docker, con un Dockerfile de dos etapas. No hace falta tener Java ni Maven instalados (bitácora 4).
- **Cola:** la base de datos en la etapa 1. RabbitMQ o Kafka en la etapa 2, por decidir.
- **Front:** en la etapa 1, un front pequeño en HTML y JavaScript sin framework, como archivos estáticos que llaman a la API por HTTP, servidos por Spring Boot. Pantallas: selección de alumno de demo, subir envío y ver su estado, tabla de posiciones. El panel de la cola y la lista de envíos para organizadores quedan pendientes, y el inicio de sesión llega en una etapa posterior. En la etapa siguiente, cuando el front se separe del back, se reescribe en React.
- **Contenedores:** todo en contenedores desde el inicio.
- **Cómo se levanta:** Docker Compose en una computadora. Se entra por `http://localhost:8080`.
- **Limitación conocida:** una sola computadora es un punto único de falla. Su reinicio detiene el concurso hasta que vuelva a arrancar. Se documenta como riesgo aceptado, no como condición de producción.

---

## 9. Decisiones de arquitectura

| Decisión | Alternativas descartadas | Por qué |
|---|---|---|
| Gateway sin maestro ni Raft, en réplicas detrás de un balanceador | Gateway con maestro y consenso; un solo contenedor de gateway | No guarda estado; la redundancia evita que un contenedor caído detenga la entrada |
| Balanceo por conexiones activas, en un contenedor aparte | Reparto por turnos; DNS interno de Docker; atar por IP o sesión | Cumple el reparto por conexiones; revisa qué réplicas responden; el token permite cualquier instancia |
| Entrada por `localhost` en la etapa 1 | IP local de la red; nombre con DNS local; túnel público | Es una demostración en una sola máquina; los servidores externos se evalúan en etapas posteriores |
| Concurso con nombre "Distrinubs Round N" y estados borrador, verificado, abierto y cerrado | Identificador tipo contest-1 como nombre visible; nombre libre sin convención; concurso sin estados (abierto según la fecha) | La convención cuenta las rondas y el nombre distingue el concurso que ve el alumno; los estados separan lo que se puede editar de lo que ya corre |
| Siembra idempotente: cada registro de la siembra tiene identificador fijo y se inserta o actualiza por él, así que correrla varias veces no duplica datos | Siembra que solo inserta (duplica al correrla dos veces); cargar los datos a mano en cada computadora | Todos los integrantes tienen los mismos datos de prueba en su computadora, y cualquiera puede volver a correr la siembra sin dañar lo que ya tiene |
| Una sola fuente de verdad para el estado (PostgreSQL) | Varias fuentes con sincronización (buffer, log temporal) | Cada hecho se guarda una sola vez; los estados que se muestran se derivan de esos hechos |
| "Recibido" solo después de guardar en disco | Responder antes de guardar | Un envío aceptado no se pierde; si falla antes de responder, el alumno sabe que debe reintentar |
| Identificador de intento generado por el cliente; reintentos reutilizan el identificador | Comparar contenido dentro de una ventana de tiempo; contar cada subida como envío nuevo | Un reintento no cobra penalización, sin depender del tiempo ni de comparar el código |
| Un envío con el mismo identificador devuelve el mismo envío y no crea uno nuevo | Crear un envío por cada subida | Un reintento de red no cuenta dos veces |
| Lease escrito por Evaluación; estado "evaluando" derivado del lease | Estado "evaluando" escrito por Envíos | Una sola fuente para quién ejecuta un envío; sin dos escritores sobre la misma fila |
| Invalidación automática por Evaluación al cierre | Invalidación manual por el organizador | Un solo módulo decide; el organizador no puede corregir un caso de prueba a mano (ver sección 10) |
| Invalidación guardada como hecho aparte del resultado | Sobrescribir el resultado | Respeta la restricción de resultado único y evita dos escritores sobre la misma fila |
| Penalización por problema: minuto de la primera aceptación más 5 minutos por cada envío fallido antes de esa aceptación | Penalización de 1 minuto por fallo, sin minuto de aceptación; penalización solo por fallos | Compara la velocidad de resolución y castiga los fallos con más fuerza |
| Envíos posteriores a la primera aceptación no penalizan | Cada fallo cuenta aunque el problema ya esté resuelto | Un fallo al optimizar una solución aceptada no debe quitar puntos |
| Desempate en etapa 1: problemas resueltos, penalización total, tiempo del último envío aceptado; empate compartido (bitácora 6) | Tiempo de ejecución y memoria como desempate; último problema resuelto | El tiempo de ejecución no es parte de la competencia; ante igualdad en resueltos y penalización, desempata la hora de llegada del último envío aceptado |
| Dificultad por cuántos alumnos resolvieron, aplicada al cierre; problema sin resolver vale cero | Dificultad fija asignada por organizadores; dificultad que cambia en vivo | El marcador en vivo no cambia a mitad del concurso |
| Marcador provisional en vivo y final al cierre | Evaluar todo en vivo | Retroalimentación durante el concurso y carga repartida |
| Evaluación en vivo con subconjunto de pruebas; si la final invalida un envío, se quita el punto | Mantener el punto aunque la final falle | Un código incorrecto no debe ganar puntos |
| Reloj corre sin pausas | Pausar por fallas de autenticación | Mantiene la regla igual para todos |
| Hora de llegada al servidor para el marcador | Hora del reloj del cliente | Los relojes de cada equipo pueden desfasarse |
| Lease con renovación mientras el worker trabaja | Lease fijo | Un envío largo no pierde su lease; un worker caído sí |
| Resultado único por envío (restricción de unicidad) | Contar cada evaluación | Un envío reevaluado tras una caída no suma dos veces |
| Tope fijo de compilación; error no sintáctico se recompila en worker limpio | Revisión manual de casos dudosos | Separa fallas del servidor de fallas del alumno sin intervención humana |
| Límites de tiempo y memoria validados días antes | Validar durante el concurso | Evita problemas imposibles de resolver dentro del límite |
| Retraso máximo de 5 min con aviso a organizadores | Aviso a alumnos; sin límite | Los organizadores reaccionan sin alarmar a los alumnos |
| Contenedor nuevo por envío | Reutilizar contenedores | Evita contaminación entre envíos; el costo de arranque lo paga el servidor |
| PostgreSQL en un solo contenedor con volumen | PostgreSQL con Patroni y réplica síncrona; CockroachDB | En una sola máquina, la réplica no protege contra una caída de la máquina |
| Sesiones en PostgreSQL en la etapa 1 | Sesiones en Redis desde ahora | Menos componentes para una sola máquina; Redis llega cuando inicio de sesión sea un servicio aparte |
| Cola en PostgreSQL en la etapa 1 | Cola con broker desde ahora | Una sola fuente de verdad; el broker llega en la etapa 2 |
| Consistencia sobre disponibilidad (principio) | Seguir atendiendo con datos parciales | Un marcador incorrecto invalida el concurso; en la etapa 1 se traduce en detenerse hasta reiniciar |
| Token validado al recibir el envío | Validar al ejecutar; consultar sesiones en cada subida | Rechaza a alumnos revocados en el momento de recibir |
| Kafka para la división en servicios; el estado sigue en PostgreSQL | Kafka como única fuente de estado | Kafka no maneja leases ni reintentos por envío; PostgreSQL sí (la comparación con RabbitMQ sigue abierta, ver sección 10) |
| Java 21 con Spring Boot 3.3.x para el back | JDK puro; otros frameworks | Es la línea de la referencia de la materia; trae HTTP, acceso a base, validación y pruebas listas, y tiene Spring Cloud para las etapas de gateway y directorio |
| Etapa 1: front pequeño en HTML y JavaScript sin framework. Etapa posterior: React | React desde la etapa 1; Thymeleaf | Dos días y un equipo que aprende: sin paso de compilación ni segundo lenguaje. React llega cuando el front se separa y el gateway exista |
| Esquema con migraciones de Flyway en SQL; Hibernate no crea ni modifica tablas (bitácora 1) | `ddl-auto: update` como en mexi-banco; `schema.sql` en cada arranque | El esquema queda en SQL revisable, con unicidad explícita, y cada cambio es un archivo nuevo con su autor en el historial |
| Cada dueño de datos escribe las migraciones de sus tablas, con versión por fecha y hora y aplicación fuera de orden (bitácora 2) | Solo Emiliano escribe migraciones; números consecutivos apartados en el chat | Trabajo en paralelo sin choques de versión; el cambio queda en el commit del dueño, como en la sección 6 |

---

## 10. Preguntas abiertas y desacuerdos

- **Alcance de la rama 01 en dos días:** el alcance mínimo de la sección 0 y el reparto de responsables deben confirmarse por el equipo antes del sábado 17. Si algo no entra, se documenta en el README de la rama.
- **Límite de compilación:** el valor numérico del tope fijo sigue pendiente.
- **Límite del marcador en vivo:** 5 minutos está definido, pero debe confirmarse con una prueba de carga.
- **Costo del contenedor por envío:** el arranque lo paga el servidor, no el alumno. Debe medirse en la prueba de carga.
- **Problemas sin resolver:** definidos con valor cero. Falta confirmar si el organizador puede retirarlos antes del cierre.
- **Corrección de casos de prueba:** la invalidación es automática. Si un caso privado estaba mal, el organizador no tiene forma de corregirlo a mano. Hay que decidir si se permite y cómo se registra.
- **Cola entre servicios (etapa 2):** RabbitMQ o Kafka. Ambos dejan el estado en PostgreSQL; hay que comparar garantías, operación y peso en una laptop.
- **Defensa:** hay que poder explicar cómo se resolvieron el gateway, el balanceo y el manejo de fallas, aunque el framework ya esté decidido.
- **Redistribución y conmutación de workers:** las filas de la versión anterior (drenado gradual, conmutación automática) no aplican en la etapa 1. Quedan para etapas posteriores.
- **Bono por orden de resolución:** propuesta de dar más puntaje a quien resuelve primero, en la línea de Codeforces. Quedó fuera de la etapa 1 y se revisará al final, junto con su relación con la dificultad de la sección 9.

---

## 11. Bitácora de cambios de enfoque

Vacía en `main`, donde se definen las reglas del proyecto. La bitácora empieza en la rama `01-monolito`, en el PRD de esa rama. Cada entrada tiene fecha, quién lo decidió, el cambio, el motivo y la sección que afecta. Las entradas no se borran: si una decisión cambia, se agrega una entrada nueva que la reemplaza.

| # | Fecha | Quién | Cambio | Motivo | Afecta |
|---|---|---|---|---|---|
| 1 | 15/10/2026 | Emiliano (@Delta2904) | El esquema se crea y se cambia con Flyway: archivos SQL versionados en `src/main/resources/db/migration`, que la aplicación aplica al arrancar. Hibernate no crea ni modifica tablas | El PRD no decía cómo se crea el esquema. Con Flyway el esquema queda en SQL revisable, con unicidad y restricciones explícitas, y cada cambio es un archivo nuevo en el commit de quien lo hace. Se descartan `ddl-auto: update`, que usa mexi-banco, porque el esquema saldría de las entidades de cada módulo, no borra ni cambia columnas y deja bases distintas en cada computadora. También se descarta `schema.sql` al arrancar, porque no registra versiones y obliga a escribir `ALTER` a mano | Secciones 8 y 9 |
| 2 | 15/10/2026 | Emiliano (@Delta2904) | Cada dueño de datos (sección 6) escribe las migraciones de sus tablas. El nombre lleva fecha y hora, `V<AAAAMMDD>_<HHMM>__<tabla>_<cambio>.sql`, y Flyway acepta migraciones fuera de orden. Una migración ya aplicada no se edita: se agrega otra | Cuatro personas trabajan en paralelo, cada una en su rama. Con números consecutivos, dos `V2` chocan. Con fecha y hora no chocan, y el cambio queda en el commit del dueño de la tabla. Se descartan que solo Emiliano escriba migraciones, porque sería cuello de botella y los cambios de otros aparecerían como suyos, y apartar números en el chat, porque depende de que todos avisen | Secciones 6 y 9 |
| 3 | 15/10/2026 | Emiliano (@Delta2904) | El lease de evaluación vive en su propia tabla, `leases_evaluacion`, con una fila por envío y la columna de vencimiento `vence_en`. No es una columna de `envios` | La sección 6 da los envíos a Envíos y los leases a Evaluación. Una columna de lease en `envios` haría que dos módulos escriban la misma fila, algo que la sección 9 descarta. La "columna con vencimiento" del punto 4 de la sección 0 es `vence_en` | Secciones 0 (punto 4) y 6 |
| 4 | 15/10/2026 | Emiliano (@Delta2904) | La aplicación se construye con Maven dentro de Docker: un Dockerfile de dos etapas con la imagen oficial de Maven, sin wrapper `mvnw`. La base es PostgreSQL 17 | Igual que mexi-banco, que usa Maven, PostgreSQL 17 y un Dockerfile de dos etapas. Nadie necesita Java ni Maven instalados: `docker compose up` compila. A diferencia de mexi-banco, no se usa `mvnw`, porque en Windows con `core.autocrlf` se descarga con saltos de línea CRLF y falla dentro del contenedor Linux | Sección 8 |
| 5 | 16/10/2026 | Enrique (@Quique1409) | `POST /api/envios` persiste el envío directamente con `estado = 'EN_COLA'` y responde `201 Created` con `estado: "recibido"`. Si choca por `(cuenta_id, id_intento)` (código 23505), recupera el envío y responde `200 OK` con `estado: "recibido"`. `GET /api/envios/{envioId}` devuelve `estado: "evaluado"` con el objeto `resultado` cuando existe veredicto, `estado: "evaluando"` con lease vigente, o `estado: "en cola"` | Define el ciclo de vida sin pasos intermedios: PostgreSQL es la cola directa para Evaluación. La concurrencia e idempotencia se delegan a la restricción de la base sin bloqueos manuales. `GET` separa el ciclo de vida del resultado técnico | Secciones 0, 5, 6 y 9 |
| 6 | 17/10/2026 | Joshua (@JoshRozam) | El marcador provisional en la etapa 1 se ordena por: (1) problemas resueltos DESC, (2) penalización total ASC, (3) desempate por tiempo con la hora del último envío aceptado ASC (`llegada_en`), y si persiste empate exacto en todo, comparten posición. La tabla provisional incluye a todas las cuentas registradas | En la etapa 1 no entra el desempate completo (sección 10). Se define un orden mínimo determinista basado en el tiempo de llegada al servidor del último problema aceptado para resolver empates sin recurrir a memoria ni tiempo de ejecución, respetando empates compartidos si son idénticos | Secciones 0, 5 (funcionalidad 3), 9 y 10 |

