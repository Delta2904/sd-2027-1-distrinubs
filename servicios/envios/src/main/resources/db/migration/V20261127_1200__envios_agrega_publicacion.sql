-- Agrega columnas de seguimiento de publicación en RabbitMQ para envíos.
-- Dueño: envios.

ALTER TABLE envios
    ADD COLUMN IF NOT EXISTS publicado_en TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS veces_publicado INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN envios.publicado_en IS
    'Última vez que envios publicó o reclamó el envío para publicarlo en RabbitMQ. Dueño: envios.';
COMMENT ON COLUMN envios.veces_publicado IS
    'Número de publicaciones del envío aceptadas por el broker RabbitMQ. Dueño: envios.';
