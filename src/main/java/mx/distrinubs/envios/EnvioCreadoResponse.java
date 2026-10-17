package mx.distrinubs.envios;

/**
 * Respuesta devuelta tras registrar o consultar un intento de envío (POST /api/envios).
 */
public record EnvioCreadoResponse(
    long envioId,
    String estado
) {}
