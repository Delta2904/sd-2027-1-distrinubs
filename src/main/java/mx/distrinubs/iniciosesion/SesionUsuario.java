package mx.distrinubs.iniciosesion;

/**
 * Representa la sesión decodificada de un usuario a partir del token JWT.
 */
public record SesionUsuario(
        long cuentaId,
        String usuario,
        String nombre,
        String rol,
        long exp
) {
}
