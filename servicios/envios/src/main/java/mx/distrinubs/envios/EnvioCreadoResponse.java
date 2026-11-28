package mx.distrinubs.envios;

/**
 * Respuesta devuelta tras registrar o recuperar por idempotencia un envío ({@code POST /api/envios}).
 */
public record EnvioCreadoResponse(
        long envioId,
        String estado
) {}
