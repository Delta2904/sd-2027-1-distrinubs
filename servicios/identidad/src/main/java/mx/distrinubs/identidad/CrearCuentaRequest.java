package mx.distrinubs.identidad;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CrearCuentaRequest(
        @NotBlank(message = "usuario es obligatorio")
        @Size(max = 50, message = "usuario admite hasta 50 caracteres")
        String usuario,

        @NotBlank(message = "nombre es obligatorio")
        @Size(max = 120, message = "nombre admite hasta 120 caracteres")
        String nombre,

        @NotBlank(message = "contrasena es obligatorio")
        String contrasena,

        String rol
) {}
