package mx.distrinubs.envios;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Respuesta devuelta al consultar el estado de un envío (GET /api/envios/{envioId}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DetalleEnvioResponse(
    long envioId,
    String estado,
    String veredicto,
    Integer tiempoMs,
    ResultadoEnvioDto resultado
) {}
