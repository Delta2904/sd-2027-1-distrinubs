package mx.distrinubs.envios;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Petición para registrar un nuevo envío de código (POST /api/envios).
 */
public record CrearEnvioRequest(
    @NotNull(message = "alumnoId es obligatorio")
    Long alumnoId,

    @NotNull(message = "problemaId es obligatorio")
    Long problemaId,

    @NotNull(message = "intentoId es obligatorio")
    String intentoId,

    @NotBlank(message = "lenguaje es obligatorio")
    String lenguaje,

    @NotBlank(message = "codigo es obligatorio")
    String codigo
) {}
