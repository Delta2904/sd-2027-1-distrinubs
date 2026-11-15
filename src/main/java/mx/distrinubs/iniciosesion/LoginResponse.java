package mx.distrinubs.iniciosesion;

/**
 * Respuesta del inicio de sesión con el token emitido y los metadatos de la sesión.
 */
public record LoginResponse(
        String token,
        String tipo,
        long cuentaId,
        String usuario,
        String nombre,
        String rol,
        long exp
) {
}
