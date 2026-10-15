# PRD · Distrinubs

**Equipo:** Calderón Gutiérrez Victor Emiliano (@Delta2904), Contreras del Ángel Diego Adrían (@diegocontrras), Medrano Solano Enrique (@Quique1409), Rodríguez Zamora Joshua (@JoshRozam)
**Fecha:** 09/10/2026

---

## 1. Problema y usuarios

Una universidad necesita un sistema de concursos de programación para competencias internas. Los usuarios son:

- **Alumnos**, que envían código y consultan la tabla de posiciones.
- **Organizadores**, que preparan y operan el concurso.
- **Administradores**, que gestionan cuentas e infraestructura.

El peor momento de uso es durante el concurso. Si el sistema cae, los alumnos no pueden iniciar sesión, subir código ni leer problemas.

## 2. Por qué es un sistema distribuido

Un solo servidor no cubre tres necesidades:

- Que la evaluación siga funcionando si una máquina falla.
- Que la carga de 200 alumnos enviando a la vez se reparta entre varios evaluadores.
- Que los resultados no se pierdan aunque caiga una ubicación completa.

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

## 4. El requisito que perseguimos y lo que dejamos atrás

**Perseguimos:** evaluación correcta y consistente. Ningún envío se pierde ni cuenta dos veces.

**Dejamos atrás:** la lectura de problemas durante una falla, porque es lo que el alumno puede esperar sin afectar el concurso.

## 5. Historias de usuario

### Funcionalidad 1: Iniciar sesión (alumno)

*Como alumno, quiero iniciar sesión para que mi sesión quede abierta durante el concurso.*

- Las credenciales válidas entregan un token firmado. Las inválidas se rechazan sin revelar si el usuario existe.
- El token sirve durante todo el concurso, salvo que sea revocado.
- Los envíos con un token revocado se rechazan al recibirlos.

### Funcionalidad 2: Subir solución y ver su estado (alumno)

*Como alumno, quiero subir mi solución y seguir su estado hasta el resultado.*

- La subida responde "recibido" con un identificador de inmediato, sin esperar a la evaluación.
- Los estados son: recibido, en cola, evaluando y resultado.
- El resultado muestra Accepted, Wrong Answer, Time Limit Exceeded, Memory Exceeded o error de compilación.
- Un reintento de una subida ya recibida devuelve el mismo identificador y no crea un envío nuevo.
- Un fallo del servidor no penaliza al alumno.

### Funcionalidad 3: Ver la tabla de posiciones (alumno)

*Como alumno, quiero ver la tabla de posiciones con todos los participantes, incluyéndome.*

- La tabla muestra problemas resueltos, penalización y tiempo, ordenados por las reglas de desempate.
- Mi fila muestra, por problema, si está resuelto, cuántos intentos fallidos tiene y su penalización.
- El marcador provisional no se atrasa más de 5 minutos. Si se supera, los organizadores reciben aviso.
- Al cierre, el marcador final es el oficial. Si un envío se invalida, el cambio aparece con su penalización recalculada.
- Los empates totales se muestran como posición compartida.

### Funcionalidad 4: Preparar el concurso (organizador)

*Como organizador, quiero crear el concurso con sus problemas, casos de prueba y límites, verificados antes del inicio.*

- Cada problema tiene límite de tiempo, límite de memoria, puntos base y casos privados. Los casos del marcador en vivo son un subconjunto marcado.
- Antes del inicio, la solución de referencia debe pasar dentro de los límites. Si no pasa, el problema no se publica.
- Un problema verificado no puede modificarse sin que el sistema marque la verificación como invalidada.

### Funcionalidad 5: Operar el concurso en vivo (organizador)

*Como organizador, quiero vigilar el concurso mientras ocurre y cerrarlo a tiempo.*

- Veo el retraso de la cola, los workers activos y los envíos en cola. Recibo aviso si el retraso supera 5 minutos.
- La evaluación final no se publica hasta que terminen todos los envíos. Antes puedo revisar los cambios, incluidos los envíos invalidados.

### Funcionalidad 6: Gestionar cuentas (administrador)

*Como administrador, quiero crear, bloquear y asignar roles a las cuentas.*

- Bloquear una cuenta revoca sus tokens. Sus envíos ya encolados se evalúan y quedan visibles como pendientes.
- Solo el administrador puede cambiar roles o bloquear cuentas.

### Funcionalidad 7: Operar la infraestructura (administrador)

*Como administrador, quiero vigilar y reaccionar ante fallas sin afectar el marcador.*

- Veo el estado del gateway, los workers, la base principal, la réplica y el balanceador.
- Veo los eventos de redistribución y conmutación, con hora y motivo.
- Puedo agregar o retirar workers sin interrumpir las evaluaciones en curso.
- Toda acción queda registrada con usuario, hora y motivo.

---

## 6. Del monolito a los servicios

### Monolito (primera etapa)

Siete módulos:

1. **Inicio de sesión:** credenciales, tokens y sesiones.
2. **Envíos:** recibe el código, valida el token, lo registra y lo deja en espera.
3. **Tabla de posiciones:** calcula el marcador provisional y el final.
4. **Evaluación de código:** compila o interpreta y ejecuta los casos de prueba.
5. **Concursos y problemas:** crea concursos, carga problemas y verifica casos antes del inicio.
6. **Administración:** cuentas, roles, bloqueos e infraestructura.
7. **Base de datos:** almacena datos y metadatos.

En el monolito, la cola de envíos vive en la base de datos con estado "en cola".

### Dueños de datos

Cada dato tiene un solo módulo que escribe:

| Dato | Dueño (escribe) | Lo leen |
|---|---|---|
| Cuentas, contraseñas, roles y tokens | Inicio de sesión | Administración, Envíos |
| Concursos, problemas, casos y límites | Concursos y problemas | Evaluación, Tabla |
| Envíos (código, estado, identificador) | Envíos | Evaluación, Tabla |
| Resultados por envío | Evaluación de código | Tabla |
| Marcador y dificultad final | Tabla de posiciones | Alumnos y organizadores |
| Registro de acciones | Administración | Organizadores |

Administración no escribe cuentas directamente: pide los cambios a Inicio de sesión.

### Servicios resultantes

Los mismos módulos separados como servicios, con Kafka entre envíos y evaluación.

### Plan de ramas

1. Evaluación de código, con un programa mínimo que compila o interpreta, corre un caso y devuelve veredicto, tiempo y memoria.
2. Concursos y problemas.
3. Inicio de sesión y envíos.
4. Tabla de posiciones y administración.

---

## 7. Dónde vive cada pieza

| Pieza | Dónde vive | Por qué | Qué pasa si falla |
|---|---|---|---|
| API gateway | Varias instancias detrás de un balanceador | Sin estado, no necesita consenso | Se retira la instancia; los reintentos llegan a otra, con idempotencia |
| Directorio / DNS | DNS con TTL de 10 s hacia la IP del balanceador | Mismo URL sin depender de una IP fija | Los clientes se actualizan en unos 10 s; los que ignoran el TTL tardan más |
| Balanceo | Dos balanceadores con IP flotante | Sin punto único de falla | El respaldo toma la IP; caída breve |
| Datos durables | PostgreSQL con Patroni y réplica síncrona en otra ubicación | Envíos, resultados y marcador no se pierden | Conmutación automática por consenso; el envío se reintenta con su identificador |
| Sesiones | Almacén rápido separado | Se reconstruyen | Los alumnos vuelven a iniciar sesión |
| Operaciones entre servicios | Kafka transporta identificadores; el estado vive en PostgreSQL | Desacopla gateway y evaluación | Con saturación se aceptan todos los envíos y los resultados se atrasan |
| Manejo de fallas | Lease con renovación, resultado único por envío, tope de compilación, reintento en worker limpio | Ningún envío se pierde ni cuenta dos veces | El envío vuelve a la cola; las fallas del servidor no penalizan |

---

## 8. Stack y cómo se levanta

- **Lenguaje del sistema:** Java.
- **Lenguajes de los alumnos:** varios. Cada uno tiene su compilador o intérprete y sus límites en una tabla de configuración.
- **Aislamiento del código:** contenedor sin privilegios, sin red, sistema de archivos de solo lectura salvo un directorio temporal, y límites de memoria, CPU y procesos. Un contenedor nuevo por envío, destruido al terminar.
- **Base de datos:** PostgreSQL con replicación síncrona y Patroni, con etcd o Consul para el consenso.
- **Cola:** la base de datos en el monolito. Kafka al dividir en servicios.
- **Contenedores:** todo en contenedores desde el inicio, para que el entorno de desarrollo sea igual al de la universidad.
- **Cómo se levanta:** en desarrollo, Docker Compose en una computadora para el monolito. Para la parte distribuida, k3s en las máquinas disponibles.
- **Infraestructura disponible:** una computadora por integrante, en su casa. La demostración de dos ubicaciones usa dos de ellas, en casas distintas.
- **Limitación conocida:** la IP pública cambia, el internet y la energía de una casa no son confiables, y la latencia entre casas afecta cada envío. Se documenta como riesgo, no como condición de producción.

---

## 9. Decisiones de arquitectura

| Decisión | Alternativas descartadas | Por qué |
|---|---|---|
| Gateway sin maestro ni Raft | Gateway con maestro y consenso | No guarda estado; solo necesita redundancia |
| Balanceador por conexiones activas, sin sticky sessions | Reparto por turnos; atar por IP o sesión | Evita concentración; el token permite cualquier instancia |
| Dos balanceadores con IP flotante | Balanceador único | Evita punto único de falla |
| DNS con TTL de 10 s | TTL largo; IP fija | El cambio se propaga en segundos |
| Réplica en otra ubicación, escritura síncrona | Réplica en la misma sala; escritura asíncrona | La misma sala no protege de fallas del edificio; la asíncrona puede perder envíos confirmados |
| Conmutación automática por consenso | Conmutación manual por operador | Evita dos principales y no depende de una persona despierta |
| Consistencia sobre disponibilidad ante corte de red | Seguir con una sola ubicación | Un marcador incorrecto invalida el concurso |
| Sesiones en almacén separado | Sesiones en la base durable | Se reconstruyen; no justifican escritura síncrona |
| Cola con pool de workers | Una máquina fija por envío | Evita ociosidad y ventajas arbitrarias entre alumnos |
| Comunicación indirecta mediante cola | Llamada directa que espera respuesta | Desacopla gateway y evaluación |
| Aceptar todos los envíos; resultados pueden atrasarse | Rechazar al saturarse | No se pierde ningún envío |
| Token validado al recibir el envío | Validar al ejecutar; consultar sesiones en cada subida | Rechaza a alumnos revocados sin depender del servicio de sesiones |
| Reloj corre sin pausas | Pausar por fallas de autenticación | Mantiene la regla igual para todos |
| Hora de llegada al servidor para el marcador | Hora del reloj del cliente | Los relojes de cada equipo pueden desfasarse |
| Desempate: problemas resueltos, penalización, tiempo de ejecución, memoria, y como último recurso el momento de su último problema resuelto; empate compartido | Promedio sobre problemas resueltos | Compara lo resuelto y evita un criterio que favorece a quien resuelve pocos problemas |
| Penalización de 1 minuto por intento fallido, incluido el invalidado | Solo fallos antes de la primera aceptación | Cada fallo cuenta |
| Dificultad por cuántos alumnos resolvieron, aplicada al cierre; problema sin resolver vale cero | Dificultad fija asignada por organizadores; dificultad que cambia en vivo | El marcador en vivo no cambia a mitad del concurso |
| Marcador provisional en vivo y final al cierre | Evaluar todo en vivo | Retroalimentación durante el concurso y carga repartida |
| Evaluación en vivo con subconjunto de pruebas; si la final invalida un envío, se quita el punto | Mantener el punto aunque la final falle | Un código incorrecto no debe ganar puntos |
| Lease con renovación mientras el worker trabaja | Lease fijo | Un envío largo no pierde su lease; un worker caído sí |
| Resultado único por envío (restricción de unicidad) | Contar cada evaluación | Un envío reevaluado tras una caída no suma dos veces |
| Tope fijo de compilación; error no sintáctico se recompila en worker limpio | Revisión manual de casos dudosos | Separa fallas del servidor de fallas del alumno sin intervención humana |
| Límites de tiempo y memoria validados días antes | Validar durante el concurso | Evita problemas imposibles de resolver dentro del límite |
| Retraso máximo de 5 min con aviso a organizadores | Aviso a alumnos; sin límite | Los organizadores reaccionan sin alarmar a los alumnos |
| Redistribución automática con drenado gradual y umbral con enfriamiento | Redistribución manual | Corrige desbalances sin caídas masivas |
| Contenedor nuevo por envío | Reutilizar contenedores | Evita contaminación entre envíos; el costo de arranque lo paga el servidor |
| PostgreSQL con Patroni | CockroachDB | Más control sobre la conmutación para un equipo que aprende la distribución |
| Kafka para la división en servicios; el estado sigue en PostgreSQL | Kafka como única fuente de estado | Kafka no maneja leases ni reintentos por envío; PostgreSQL sí |

---

## 10. Preguntas abiertas y desacuerdos

- **Infraestructura real:** la demostración de dos ubicaciones usa computadoras de casa. Falta confirmar que lo aceptan como limitación documentada.
- **Límite de compilación:** el valor numérico del tope fijo sigue pendiente.
- **Límite del marcador en vivo:** 5 minutos está definido, pero debe confirmarse con una prueba de carga.
- **Costo del contenedor por envío:** el arranque lo paga el servidor, no el alumno. Debe medirse en la prueba de carga.
- **Problemas sin resolver:** definidos con valor cero. Conviene confirmar si el organizador puede retirarlos antes del cierre.
