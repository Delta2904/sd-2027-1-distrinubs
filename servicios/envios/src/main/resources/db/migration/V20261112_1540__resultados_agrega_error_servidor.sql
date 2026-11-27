-- Agrega el veredicto ERROR_SERVIDOR: el servidor no logró evaluar el envío
-- después de agotar sus tomas (tope de reintentos). No es un fallo del alumno:
-- no penaliza y la tabla de posiciones no lo cuenta.
-- Dueño de la tabla: Evaluación.

ALTER TABLE resultados DROP CONSTRAINT resultados_veredicto_valido;

ALTER TABLE resultados ADD CONSTRAINT resultados_veredicto_valido CHECK (veredicto IN (
    'ACCEPTED',
    'WRONG_ANSWER',
    'TIME_LIMIT_EXCEEDED',
    'MEMORY_EXCEEDED',
    'RUNTIME_ERROR',
    'ERROR_COMPILACION',
    'ERROR_SERVIDOR'
));
