-- Siembra de cuentas de demo.
--
-- Excepción temporal a la regla del dueño de datos: la tabla `cuentas` es de
-- Inicio de sesión, pero en la etapa 2 (paso 1) este servicio todavía siembra
-- las cuentas de demo, hasta que pasen al servicio `identidad`. Se declara en
-- el README del servicio.
--
-- Idempotente: cada cuenta tiene un id fijo del 1 al 5 y se
-- inserta o se actualiza por ese id. Correrlo varias veces no duplica nada.
--
-- Lo ejecuta la aplicación al arrancar (SiembraCuentasDemo, del paquete iniciosesion), después de
-- las migraciones. No hace falta correrlo a mano.

INSERT INTO cuentas (id, usuario, nombre) VALUES
    (1, 'alumno1', 'Alumno Uno'),
    (2, 'alumno2', 'Alumno Dos'),
    (3, 'alumno3', 'Alumno Tres'),
    (4, 'alumno4', 'Alumno Cuatro'),
    (5, 'alumno5', 'Alumno Cinco')
ON CONFLICT (id) DO UPDATE
    SET usuario = EXCLUDED.usuario,
        nombre  = EXCLUDED.nombre;
