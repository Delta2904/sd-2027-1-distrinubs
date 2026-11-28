package mx.distrinubs.envios;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Petición para registrar un nuevo envío de código ({@code POST /api/envios}).
 * El campo {@code alumnoId} es opcional; cuando se envía debe coincidir con la
 * cuenta autenticada del token cuando el rol es {@code ALUMNO}.
 */
public record CrearEnvioRequest(
        Long alumnoId,

        @NotNull(message = "problemaId es obligatorio")
        Long problemaId,

        @NotBlank(message = "intentoId es obligatorio")
        String intentoId,

        @NotBlank(message = "lenguaje es obligatorio")
        String lenguaje,

        @NotBlank(message = "codigo es obligatorio")
        String codigo
) {}
