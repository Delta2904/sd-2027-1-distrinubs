-- Siembra de cuentas de demo (PRD, sección 0, punto 2).
--
-- Excepción temporal a la regla del dueño de datos: la tabla `cuentas` es de
-- Inicio de sesión, pero en 01 no existe ese módulo, así que la escribe este
-- script. Se declara en el README de la etapa.
--
-- Idempotente (PRD, sección 9): cada cuenta tiene un id fijo del 1 al 5 y se
-- inserta o se actualiza por ese id. Correrlo varias veces no duplica nada.
--
-- Uso, con `docker compose up` ya corriendo (desde la raíz del repositorio):
--   docker compose exec -T db psql -U distrinubs -d distrinubs -v ON_ERROR_STOP=1 < scripts/siembra-cuentas.sql

INSERT INTO cuentas (id, usuario, nombre) VALUES
    (1, 'alumno1', 'Alumno Uno'),
    (2, 'alumno2', 'Alumno Dos'),
    (3, 'alumno3', 'Alumno Tres'),
    (4, 'alumno4', 'Alumno Cuatro'),
    (5, 'alumno5', 'Alumno Cinco')
ON CONFLICT (id) DO UPDATE
    SET usuario = EXCLUDED.usuario,
        nombre  = EXCLUDED.nombre;
