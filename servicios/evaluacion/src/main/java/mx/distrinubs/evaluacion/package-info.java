/**
 * Servicio de evaluación de código: consume de RabbitMQ los envíos por evaluar,
 * toma cada uno con un lease, ejecuta los casos de prueba y registra el resultado.
 *
 * <p>Escribe: {@code leases_evaluacion} y {@code resultados}. Lee: {@code envios},
 * {@code problemas} y {@code casos_prueba}. El mensaje solo trae el
 * {@code envioId}: el estado sigue en PostgreSQL y manda el lease.
 */
package mx.distrinubs.evaluacion;
