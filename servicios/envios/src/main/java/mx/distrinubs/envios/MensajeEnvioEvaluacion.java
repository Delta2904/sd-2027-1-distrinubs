package mx.distrinubs.envios;

/**
 * Mensaje publicado en RabbitMQ para solicitar la evaluación de un envío.
 * El estado autoritativo del envío permanece en PostgreSQL.
 */
public record MensajeEnvioEvaluacion(long envioId) {}
