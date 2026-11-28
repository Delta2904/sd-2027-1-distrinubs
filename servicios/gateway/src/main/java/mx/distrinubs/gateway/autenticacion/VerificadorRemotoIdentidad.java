package mx.distrinubs.gateway.autenticacion;

import reactor.core.publisher.Mono;

/**
 * Puerto reactivo para validar contra el servicio {@code identidad} que el token
 * no haya sido revocado ni la cuenta bloqueada.
 */
public interface VerificadorRemotoIdentidad {

    Mono<SesionGateway> validarRemoto(String authHeader);
}
