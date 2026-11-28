-- Amplía los lenguajes válidos en la tabla envios (Python 3, C++, C, Java y JavaScript).
-- Dueño: envios.

ALTER TABLE envios DROP CONSTRAINT IF EXISTS envios_lenguaje_valido;

ALTER TABLE envios ADD CONSTRAINT envios_lenguaje_valido CHECK (lenguaje IN (
    'PYTHON3',
    'CPP',
    'C',
    'JAVA',
    'JAVASCRIPT'
));
