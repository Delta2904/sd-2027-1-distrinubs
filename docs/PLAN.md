# Plan

Dos etapas: terminar el monolito y separar todo. Aquí está el orden, el reparto y las fechas. Las decisiones están en el [PRD](PRD.md) y las reglas para agentes en [CLAUDE.md](../CLAUDE.md).

**Entrega: sábado 28/11, 07:00.** Código y `docs/reporte-final.pdf` en el repo. Ese día, pitch con demo y defensa. El examen escrito y la entrevista son individuales, el sábado 21/11.

## Ramas

- `main`: README, PRD y reporte final.
- `01-monolito`: etapa 1. Se cierra el 15/11 con el tag `01-cierre` y queda de respaldo: si la etapa 2 no llega al paso 3 el 25/11, se entrega esta.
- `02-separacion`: etapa 2.

Cada quien trabaja en su rama personal y la integra con un pull request hacia la rama de la etapa. Los puntos de retorno son tags, no ramas.

## Etapa 1: terminar el monolito (hasta el 15/11)

Hecho: esquema y arranque, concursos y tabla, envíos y estado, evaluación con lease, front y script de demostración.

Falta:

- [ ] Siembra automática: `docker compose up` deja todo listo, sin comandos `psql < archivo`, que fallan en PowerShell.
- [ ] Tope de reintentos por envío: hoy uno que se cuelga se reintenta sin límite.
- [ ] Probar 2 o más workers sobre la misma cola.
- [ ] Prueba de carga del marcador con 200 alumnos: el retraso debe quedar bajo 5 minutos.
- [ ] Limpieza: quitar el `.pyc` versionado y unir las dos identidades de Emiliano en el historial con un `.mailmap`.
- [ ] README al día y tag `01-cierre`.

Pasa a la etapa 2: sandbox, límite de memoria, evaluación final, inicio de sesión e interfaz de organizador.

## Etapa 2: separar todo (16/11 al 25/11)

Pasos en orden; cada uno deja el sistema funcionando y se comprueba con `scripts/demo.py`. **El 24/11 lo que no corre de punta a punta se declara fuera** en el README y el reporte.

1. `envios`, `evaluacion` y `concursos` como servicios en Compose, con RabbitMQ entre envíos y evaluación y varios workers. (mensajería, idempotencia)
2. Gateway en 2 réplicas, nginx delante y Eureka. (gateway, directorio, balanceo)
3. Demo de fallas: matar un worker a media evaluación (el envío se evalúa una vez), apagar una réplica del gateway (la demo sigue) y ver salir de Eureka a una instancia caída. **Hasta aquí es el mínimo.**
4. `marcador` aparte, con Redis y mensajes de resultado. (caché, consistencia)
5. Circuit breaker y timeouts en las rutas del gateway; cola de mensajes muertos con tope de reintentos. (fallas)
6. Observabilidad mínima: id de correlación en los logs, Actuator y una vista del estado de los servicios.
7. Si alcanza: `identidad` (inicio de sesión, tokens, roles, Redis para sesiones), interfaz de organizador, sandbox por envío, evaluación final y saga de cierre.

Opcionales, solo con lo anterior corriendo: Kubernetes, MongoDB, cadena de hashes, Prometheus, Grafana y Jaeger.

## Reparto

Pendiente: se define a más tardar el 15/11. Cada integrante es dueño de al menos un área completa (código, pruebas, Dockerfile y su parte del README), defiende un tema en la defensa y hace sus commits con su cuenta.

| Área | Pasos | Responsable | Tema que defiende |
|---|---|---|---|
| Evaluación | 1, 7 | Por definir | Fallas y reentrega |
| Envíos y mensajería | 1, 5 | Por definir | Operaciones entre servicios |
| Entrada e infraestructura | 2, 3, 5 | Por definir | Gateway, directorio y balanceador |
| Concursos, marcador y caché | 1, 4 | Por definir | Datos y consistencia |

Reporte, diagramas, pitch y demo de fallas son de todos: cada quien escribe la parte de su área.

## Calendario

| Fecha | Qué |
|---|---|
| Jue 12 a dom 15 | Etapa 1, reparto y confirmar este plan con los cuatro. Crear `02-separacion` |
| Lun 16 a vie 20 | Pasos 1 a 3 |
| Sáb 21 | Examen escrito y entrevista; sin trabajo del proyecto |
| Dom 22 a mar 24 | Pasos 4 a 6. El martes 24, corte |
| Mié 25 | Se congela el código con un tag y se prueba `docker compose up` desde cero |
| Mié 25 a vie 27 | Reporte PDF, diagramas y ensayo del pitch y de la demo de fallas |
| Sáb 28, 07:00 | Entrega; después, la presentación |

## Qué pide la rúbrica

- **Prototipo, 30 %:** se levanta con el README sin pasos escondidos, y se ve cómo entran las peticiones, cómo se comunican las partes, dónde viven los datos y qué pasa si algo falla.
- **Reporte, 30 %:** portada, problema, diagrama funcional, diagrama de arquitectura, al menos tres decisiones con lo que se dejó atrás, manejo de fallas, cómo levantarlo, qué falta y quién hizo qué.
- **Pitch, 20 %:** problema, solución y demo; todos presentan una parte.
- **Defensa y participación, 20 %:** preguntas a cualquiera sobre gateway, directorio, balanceador, operaciones entre servicios y fallas; y el historial de commits de cada quien.
