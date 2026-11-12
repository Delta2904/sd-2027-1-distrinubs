# CLAUDE.md

Entrada para cualquier agente que trabaje en el repositorio del equipo Distrinubs (proyecto final de Sistemas Distribuidos, FI-UNAM, 2027-1): un juez en línea para práctica y concursos de programación competitiva.

## Lee antes de cualquier cambio

1. **`docs/PRD.md`:** qué se construye y por qué. Sobre todo la sección 6 (dueños de datos), la 9 (decisiones vigentes, con sus alternativas descartadas) y la 10 (lo que el equipo no ha decidido).
2. **`docs/PLAN.md`:** la etapa actual, el orden del trabajo, el reparto y las fechas.

Si algo de este archivo contradice una decisión del PRD, manda el PRD.

## Reglas

1. **Decisiones.** Antes de implementar algo, revisa la sección 9 del PRD. Si lo pedido contradice una decisión, detente y pregunta; no la cambies por tu cuenta.
2. **Dueños de datos.** Ningún módulo escribe en la tabla de otro. Si un módulo necesita un dato que no le pertenece, pide el cambio al dueño.
3. **Preguntas abiertas.** Lo que está en la sección 10 no está decidido. No lo implementes como si lo estuviera; si tu cambio depende de ello, pregunta antes.
4. **Antes de cada etapa**, afina el enfoque con las skills de `.claude/skills/`: `metodo-socratico` para decidir cómo se construye y `product-manager` si se agregan, quitan o cambian funcionalidades. Si tu herramienta no carga skills, lee sus `SKILL.md` como instrucciones.
5. **El PRD es vivo.** Los cambios de funcionalidades se hacen con `product-manager` y se reflejan en el PRD. Lo que se aprende en clase también puede cambiar una decisión: se revisa en la sección 9 antes de tocar el código.
6. **Bitácora.** Todo cambio de enfoque, lo proponga quien lo proponga, se registra en la sección 11 del PRD (fecha, quién, cambio, motivo y secciones que afecta) antes de implementarse y en la rama de la etapa. Si cambia una decisión, también se actualiza su fila en la sección 9. Sin entrada, el cambio no está aprobado. En `main` no se usa la bitácora.
7. **Cierre de cada rama.** El README de la rama declara qué hace, qué no hace y qué queda pendiente, y `docs/PLAN.md` se actualiza en la misma entrega.
8. **Ramas.** Solo hay tres: `main`, `01-monolito` y `02-separacion`. Cada integrante trabaja en una rama personal y la integra con un pull request hacia la rama de la etapa. Los puntos de retorno son tags.
9. **Opcionales.** Solo empiezan cuando lo obligatorio corre y está documentado.
10. **Etapa 1.** En `01-monolito` está prohibido crear servicios separados o llamadas HTTP entre módulos, gateway, directorio, balanceador o réplicas, cola externa (RabbitMQ o Kafka) y Redis. Si una de estas piezas hace falta, se propone en la bitácora antes de implementarla.

## Cómo se trabaja

- Cada integrante es responsable de su parte, según `docs/PLAN.md`. Usa tu propio agente para esa parte y haz los commits con tu cuenta. La defensa revisa el historial de commits.
- No hagas push sin que el equipo lo confirme.
- Los comandos para levantar y probar están en el README de la rama. No inventes otros.

## Convenciones

- Los términos del dominio van en español, en identificadores y en textos de interfaz: concurso, envío, marcador, tabla de posiciones, evaluación, lease, penalización.
- **No cites secciones ni entradas de la bitácora del PRD en el código, los scripts ni el README.** Los números cambian y el código no debe depender de la estructura de un documento. Escribe la regla o el motivo directamente. Las migraciones ya aplicadas no se editan, así que sus referencias antiguas se quedan.
- Referencia docente: el repositorio de la materia, con una rama por etapa, [mexi-banco](https://github.com/OscarRuiz21/mexi-banco).
