# Distrinubs

Juez en línea para práctica y concursos de programación competitiva. Proyecto final de la asignatura Sistemas Distribuidos (FI-UNAM, 2027-1).

## Qué es

Los alumnos envían una solución a un problema. El sistema la evalúa contra casos de prueba, con límite de tiempo, y publica el veredicto (Accepted, Wrong Answer, Time Limit Exceeded o error de compilación). Con los envíos construye una tabla de posiciones: un marcador provisional durante el concurso y uno final al cierre.

Los usuarios son alumnos, organizadores y administradores. Las cuentas y los roles de administración llegan en una etapa posterior.

## Estado actual

El proyecto se construye por etapas, cada una en su rama.

- **Etapa 1, `01-monolito`:** una sola aplicación, un proceso y una base de datos PostgreSQL, todo en contenedores con Docker Compose en una computadora. Es el alcance de esta entrega.
- **Etapa 2, `02-separacion`:** los módulos pasan a servicios con una cola entre envíos y evaluación. Por definir.
- Gateway, réplicas, balanceador e inicio de sesión como servicio aparte llegan después.

**Lo que hace la etapa 1:** un concurso con problemas y casos de prueba, la entrada del alumno con una identidad de demo, subir un envío que solo se confirma después de guardarse, evaluación con un worker en Python 3, consulta del estado y del veredicto, y una tabla provisional con la penalización de 5 minutos por cada fallo antes de la aceptación.

**Lo que no hace la etapa 1:** inicio de sesión con contraseña o tokens, aislamiento de código por contenedor, varios lenguajes, límite de memoria, varios workers, invalidación al cierre y desempate completo.

**Riesgos declarados:**
- Sin autenticación, cualquiera puede enviar con cualquier identidad de demo.
- Sin aislamiento, el código de los alumnos corre sin sandbox en la computadora del equipo.
- Una caída de la computadora detiene el sistema hasta que vuelva a arrancar. Los envíos ya confirmados se conservan.

## Quiénes somos

Equipo Distrinubs:

| Integrante | GitHub | Parte principal en la etapa 1 |
|---|---|---|
| Calderón Gutiérrez Victor Emiliano | [@Delta2904](https://github.com/Delta2904) | Base de datos, evaluación de código e integración. Coordina el proyecto |
| Contreras del Ángel Diego Adrián | [@diegocontrras](https://github.com/diegocontrras) | Front pequeño, cuentas de prueba y script de demostración |
| Medrano Solano Enrique | [@Quique1409](https://github.com/Quique1409) | Envíos y estado del envío |
| Rodríguez Zamora Joshua | [@JoshRozam](https://github.com/JoshRozam) | Concursos y problemas, y tabla de posiciones |

## Cómo se levanta

Este README está en `main`, que contiene la documentación y no la aplicación. Para levantar la etapa 1:

```bash
git checkout 01-monolito
docker compose up
```

La entrada del sistema es `http://localhost:8080`. Los pasos completos, incluida la siembra de concursos y cuentas de prueba, están en el README de la rama `01-monolito`.

Requisito: Docker con Docker Compose.

## Documentación

- [docs/PRD-Distrinubs.md](docs/PRD-Distrinubs.md): problema, alcance, decisiones de arquitectura, estado de cada parte y bitácora de cambios de enfoque. La bitácora vive en la rama `01-monolito`, no en `main`.
- [CLAUDE.md](CLAUDE.md): reglas para los agentes que trabajan en el repositorio.
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
