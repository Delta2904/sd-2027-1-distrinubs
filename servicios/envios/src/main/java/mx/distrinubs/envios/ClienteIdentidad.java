package mx.distrinubs.envios;

/**
 * Consulta autoritativa al servicio {@code identidad} para confirmar que el token
 * no ha sido revocado ni la cuenta bloqueada.
 */
public interface ClienteIdentidad {

    SesionAutenticada validar(String authHeader);
}
