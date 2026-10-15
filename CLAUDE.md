# CLAUDE.md

Entrada para cualquier agente que trabaje en el repositorio del equipo Distrinubs (proyecto final de Sistemas Distribuidos, FI-UNAM, 2027-1): un juez en línea para práctica y concursos de programación competitiva.

Este archivo no repite reglas. Las reglas, el estado y el trabajo viven en el PRD.

## Lee antes de cualquier cambio

1. **`docs/PRD-Distrinubs.md`, sección 0:** etapa actual, alcance de la entrega, prohibiciones de la etapa, tabla de estado de cada parte, responsables y reglas para el agente. Es lo que manda.
2. **Sección 6:** dueños de datos. Ningún módulo escribe en la tabla de otro.
3. **Sección 9:** decisiones vigentes, con sus alternativas descartadas. No las cambies por tu cuenta.
4. **Sección 10:** lo que el equipo no ha decidido. Si tu cambio depende de algo ahí, pregunta antes.

Si el PRD y este archivo se contradicen, manda el PRD.

## Cómo se trabaja

- Cada integrante es responsable de una parte, según la tabla de responsables de la sección 0. Usa tu propio agente para esa parte y haz los commits con tu cuenta. La defensa revisa el historial de commits.
- Cada etapa vive en su rama. El README de la rama declara qué hace, qué no hace y qué queda pendiente.
- Todo cambio de enfoque se registra en la bitácora de la sección 11 del PRD, en la rama de la etapa, antes de implementarse. La bitácora no vive en `main`.
- No hagas push sin que el equipo lo confirme.
- No hay código ni comandos de build, lint o test todavía. No inventes comandos: cuando existan, el README de la rama `01-monolito` los documenta.
- Antes de decidir cómo se construye una etapa o de cambiar funcionalidades, afina el enfoque con las skills de `.claude/skills/`: `metodo-socratico` y `product-manager`. Si tu herramienta no carga skills, lee sus `SKILL.md` como instrucciones.

## Convenciones

- Los términos del dominio van en español, en identificadores y en textos de interfaz: concurso, envío, marcador, tabla de posiciones, evaluación, lease, penalización.
- Referencia docente: el repositorio de la materia, con una rama por etapa, [mexi-banco](https://github.com/OscarRuiz21/mexi-banco).
