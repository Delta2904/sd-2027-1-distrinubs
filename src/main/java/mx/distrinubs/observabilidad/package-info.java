/**
 * Módulo de Observabilidad mínima (Paso 7 de la etapa 2).
 *
 * <p>Responsable de:
 * <ul>
 *   <li>Filtro de ID de correlación ({@code X-Correlation-Id}) en cada petición HTTP,
 *       propagado al MDC de SLF4J y a las respuestas para trazabilidad de logs.</li>
 *   <li>Indicadores de salud y métricas mediante Spring Boot Actuator.</li>
 *   <li>Endpoint {@code GET /api/observabilidad/estado} que ofrece una vista centralizada
 *       del estado de los servicios, workers y base de datos para la interfaz.</li>
 * </ul>
 */
package mx.distrinubs.observabilidad;
