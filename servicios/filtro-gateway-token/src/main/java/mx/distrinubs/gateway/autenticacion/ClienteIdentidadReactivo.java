package mx.distrinubs.gateway.autenticacion;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

/**
 * Cliente reactivo hacia {@code identidad} con política de falla cerrada:
 * si {@code identidad} no responde, vence el timeout o devuelve 5xx, falla con
 * {@code 503 Service Unavailable}.
 */
@Component
public class ClienteIdentidadReactivo implements VerificadorRemotoIdentidad {

    private static final Logger log = LoggerFactory.getLogger(ClienteIdentidadReactivo.class);

    private final WebClient webClient;
    private final Duration timeout;

    public ClienteIdentidadReactivo(
            WebClient.Builder webClientBuilder,
            @Value("${distrinubs.autenticacion.identidad-url:http://identidad:8083}") String identidadUrl,
            @Value("${distrinubs.autenticacion.timeout-ms:2000}") long timeoutMs) {
        String base = identidadUrl.endsWith("/")
                ? identidadUrl.substring(0, identidadUrl.length() - 1)
                : identidadUrl;
        this.webClient = webClientBuilder.baseUrl(base).build();
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    @Override
    public Mono<SesionGateway> validarRemoto(String authHeader) {
        return webClient.get()
                .uri("/api/auth/validar")
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .retrieve()
                .bodyToMono(SesionGateway.class)
                .timeout(timeout)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Respuesta vacía de identidad")))
                .onErrorMap(this::traducirError);
    }

    private Throwable traducirError(Throwable error) {
        if (error instanceof ResponseStatusException rse) {
            return rse;
        }
        if (error instanceof WebClientResponseException wcre) {
            if (wcre.getStatusCode() == HttpStatus.FORBIDDEN) {
                return new ResponseStatusException(HttpStatus.FORBIDDEN, "Cuenta sin permiso o bloqueada");
            }
            if (wcre.getStatusCode().is4xxClientError()) {
                return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token inválido o revocado");
            }
        }
        log.warn("Falla cerrada en gateway al validar token contra identidad: {}", error.getMessage());
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Servicio de identidad no disponible");
    }
}
