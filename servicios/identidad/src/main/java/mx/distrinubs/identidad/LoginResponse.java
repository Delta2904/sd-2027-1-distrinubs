package mx.distrinubs.identidad;

public record LoginResponse(
        String token,
        String tipo,
        long cuentaId,
        String usuario,
        String nombre,
        String rol,
        long exp
) {}
