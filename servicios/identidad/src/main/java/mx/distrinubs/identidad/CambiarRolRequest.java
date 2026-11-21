package mx.distrinubs.identidad;

import jakarta.validation.constraints.NotBlank;

public record CambiarRolRequest(
        @NotBlank(message = "rol es obligatorio")
        String rol
) {}
