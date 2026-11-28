package mx.distrinubs.identidad;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "usuario es obligatorio")
        String usuario,

        @NotBlank(message = "contrasena es obligatorio")
        String contrasena
) {}
