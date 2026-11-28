package mx.distrinubs.identidad;

public record SesionUsuario(
        long cuentaId,
        String usuario,
        String nombre,
        String rol,
        long exp,
        String jti
) {}
