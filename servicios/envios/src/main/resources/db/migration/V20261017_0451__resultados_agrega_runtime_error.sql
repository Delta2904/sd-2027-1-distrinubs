-- Agrega el veredicto RUNTIME_ERROR: el código pasa la revisión de sintaxis
-- pero termina con error al ejecutarse (PRD, bitácora 9).
-- Dueño de la tabla: Evaluación.

ALTER TABLE resultados DROP CONSTRAINT resultados_veredicto_valido;

ALTER TABLE resultados ADD CONSTRAINT resultados_veredicto_valido CHECK (veredicto IN (
    'ACCEPTED',
    'WRONG_ANSWER',
    'TIME_LIMIT_EXCEEDED',
    'MEMORY_EXCEEDED',
    'RUNTIME_ERROR',
    'ERROR_COMPILACION'
));
