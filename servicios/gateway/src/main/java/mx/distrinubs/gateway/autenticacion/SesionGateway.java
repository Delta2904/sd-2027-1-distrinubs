package mx.distrinubs.gateway.autenticacion;

/**
 * Datos de sesión autenticada validados por el filtro del gateway.
 */
public record SesionGateway(
        long cuentaId,
        String usuario,
        String nombre,
        String rol,
        long exp,
        String jti
) {}
