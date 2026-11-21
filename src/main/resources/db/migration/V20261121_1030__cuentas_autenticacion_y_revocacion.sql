-- Agrega credenciales con hash, rol, bloqueo y revocación de tokens a cuentas,
-- y crea la tabla de tokens revocados individualmente (jti).
-- Dueño: Inicio de sesión / identidad.

ALTER TABLE cuentas
    ADD COLUMN IF NOT EXISTS contrasena_hash VARCHAR(100),
    ADD COLUMN IF NOT EXISTS rol VARCHAR(20) NOT NULL DEFAULT 'ALUMNO',
    ADD COLUMN IF NOT EXISTS bloqueada BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS revocada_desde TIMESTAMPTZ;

ALTER TABLE cuentas
    DROP CONSTRAINT IF EXISTS cuentas_rol_valido;

ALTER TABLE cuentas
    ADD CONSTRAINT cuentas_rol_valido
        CHECK (rol IN ('ALUMNO', 'ORGANIZADOR', 'ADMINISTRADOR'));

COMMENT ON COLUMN cuentas.contrasena_hash IS
    'Hash BCrypt de la contraseña de la cuenta. Dueño: Inicio de sesión / identidad.';
COMMENT ON COLUMN cuentas.rol IS
    'Rol de autorización: ALUMNO, ORGANIZADOR o ADMINISTRADOR. Dueño: Inicio de sesión / identidad.';
COMMENT ON COLUMN cuentas.bloqueada IS
    'Indica si la cuenta está bloqueada para iniciar sesión y usar tokens. Dueño: Inicio de sesión / identidad.';
COMMENT ON COLUMN cuentas.revocada_desde IS
    'Marca temporal a partir de la cual todo token emitido antes queda revocado. Dueño: Inicio de sesión / identidad.';

CREATE TABLE IF NOT EXISTS tokens_revocados (
    jti         VARCHAR(64) PRIMARY KEY,
    cuenta_id   BIGINT      NOT NULL REFERENCES cuentas (id),
    revocado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    expira_en   TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS tokens_revocados_expira_idx ON tokens_revocados (expira_en);

COMMENT ON TABLE tokens_revocados IS
    'Dueño: Inicio de sesión / identidad. Registra identificadores jti revocados individualmente antes de su vencimiento.';
