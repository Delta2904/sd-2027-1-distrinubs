package mx.distrinubs.envios;

/**
 * Puerto de publicación de mensajes hacia la cola de evaluación.
 */
public interface PublicadorEnvios {

    void publicar(long envioId);
}
