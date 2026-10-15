/**
 * Base de datos: almacena datos y metadatos.
 *
 * <p>El esquema no vive en Java: lo crean las migraciones de Flyway en
 * {@code src/main/resources/db/migration}. Cada dueño de datos escribe las
 * migraciones de sus tablas (PRD, bitácora 1 y 2).
 */
package mx.distrinubs.basedatos;
