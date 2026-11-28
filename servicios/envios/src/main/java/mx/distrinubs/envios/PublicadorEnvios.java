package mx.distrinubs.envios;

/**
 * Puerto de publicación de mensajes hacia la cola de evaluación.
 */
public interface PublicadorEnvios {

    /**
     * Publica el envío en el broker y devuelve {@code true} si el mensaje fue aceptado.
     */
    boolean publicar(long envioId);
}
