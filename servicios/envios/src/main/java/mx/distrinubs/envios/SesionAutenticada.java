package mx.distrinubs.envios;

/**
 * Identidad verificada de la sesión del usuario autenticado.
 */
public record SesionAutenticada(
        long cuentaId,
        String usuario,
        String nombre,
        String rol,
        long exp,
        String jti
) {}
