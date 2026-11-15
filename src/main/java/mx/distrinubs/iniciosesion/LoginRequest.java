package mx.distrinubs.iniciosesion;

import jakarta.validation.constraints.NotBlank;

/**
 * Petición de inicio de sesión según el contrato acordado en la etapa 2:
 * {@code POST /api/auth/login}.
 */
public record LoginRequest(
        @NotBlank(message = "El usuario es obligatorio")
        String usuario,

        @NotBlank(message = "La contraseña es obligatoria")
        String contrasena
) {
}
