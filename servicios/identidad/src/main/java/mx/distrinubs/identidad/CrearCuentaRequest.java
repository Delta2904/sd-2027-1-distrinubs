package mx.distrinubs.identidad;

import jakarta.validation.constraints.NotBlank;

public record CrearCuentaRequest(
        @NotBlank(message = "usuario es obligatorio")
        String usuario,

        @NotBlank(message = "nombre es obligatorio")
        String nombre,

        @NotBlank(message = "contrasena es obligatorio")
        String contrasena,

        String rol
) {}
