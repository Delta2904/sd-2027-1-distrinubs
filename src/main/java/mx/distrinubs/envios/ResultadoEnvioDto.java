package mx.distrinubs.envios;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Detalle del resultado de la evaluación de un envío.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResultadoEnvioDto(
    String veredicto,
    Integer tiempoMs,
    Integer memoriaKb,
    String detalle
) {}
