-- Garantiza unicidad del nombre de usuario sin distinguir mayúsculas ni minúsculas.
-- Dueño: identidad.

CREATE UNIQUE INDEX IF NOT EXISTS cuentas_usuario_minusculas_unico
    ON cuentas (LOWER(usuario));
