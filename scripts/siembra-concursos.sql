-- Siembra de concursos, problemas y casos de prueba (PRD, sección 0, punto 1 y sección 5).
--
-- Dueño de datos: Concursos y problemas (PRD, sección 6).
-- Solo este módulo escribe en las tablas concursos, problemas y casos_prueba.
--
-- Idempotente (PRD, sección 9): usa identificadores fijos del 1 al 999 y hace
-- upsert por id (ON CONFLICT (id) DO UPDATE). Correrlo varias veces no duplica
-- ni rompe nada.
--
-- En la etapa 1 el límite de memoria se guarda, pero no se aplica.
--
-- Uso, con `docker compose up` ya corriendo (desde la raíz del repositorio):
--   docker compose exec -T db psql -U distrinubs -d distrinubs -v ON_ERROR_STOP=1 < scripts/siembra-concursos.sql

-- 1. Concurso "Distrinubs Round 1" en estado ABIERTO
INSERT INTO concursos (id, nombre, inicio, fin, estado) VALUES
    (1, 'Distrinubs Round 1', now() - INTERVAL '1 hour', now() + INTERVAL '24 hours', 'ABIERTO')
ON CONFLICT (id) DO UPDATE
    SET nombre = EXCLUDED.nombre,
        inicio = CASE WHEN concursos.fin < now() THEN EXCLUDED.inicio ELSE concursos.inicio END,
        fin    = CASE WHEN concursos.fin < now() THEN EXCLUDED.fin ELSE concursos.fin END,
        estado = EXCLUDED.estado;

-- 2. Problema A: Suma simple
INSERT INTO problemas (id, concurso_id, letra, titulo, enunciado, limite_tiempo_ms, limite_memoria_mb, puntos_base) VALUES
    (1, 1, 'A', 'Suma simple', 'Dados dos números enteros a y b separados por un espacio en la entrada estándar, calcula e imprime su suma.', 1000, 256, 100)
ON CONFLICT (id) DO UPDATE
    SET concurso_id       = EXCLUDED.concurso_id,
        letra             = EXCLUDED.letra,
        titulo            = EXCLUDED.titulo,
        enunciado         = EXCLUDED.enunciado,
        limite_tiempo_ms  = EXCLUDED.limite_tiempo_ms,
        limite_memoria_mb = EXCLUDED.limite_memoria_mb,
        puntos_base       = EXCLUDED.puntos_base;

-- 3. Casos de prueba:
-- Casos 1 y 2: subconjunto del marcador provisional (en_marcador_provisional = TRUE).
-- Caso 3: caso privado adicional (en_marcador_provisional = FALSE).
INSERT INTO casos_prueba (id, problema_id, orden, entrada, salida_esperada, en_marcador_provisional) VALUES
    (1, 1, 1, '2 3' || E'\n', '5' || E'\n', TRUE),
    (2, 1, 2, '10 20' || E'\n', '30' || E'\n', TRUE),
    (3, 1, 3, '-5 15' || E'\n', '10' || E'\n', FALSE)
ON CONFLICT (id) DO UPDATE
    SET problema_id             = EXCLUDED.problema_id,
        orden                   = EXCLUDED.orden,
        entrada                 = EXCLUDED.entrada,
        salida_esperada         = EXCLUDED.salida_esperada,
        en_marcador_provisional = EXCLUDED.en_marcador_provisional;
