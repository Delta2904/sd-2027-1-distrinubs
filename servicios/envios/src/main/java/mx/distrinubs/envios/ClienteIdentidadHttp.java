package mx.distrinubs.envios;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cliente HTTP síncrono hacia {@code identidad} con política de falla cerrada:
 * solo un 401 o un 403 de {@code identidad} hablan del token o de la cuenta. Si
 * {@code identidad} no responde o devuelve cualquier otro código, rechaza la petición
 * con {@code 503 Service Unavailable} para no aceptar envíos con tokens posiblemente revocados.
 */
@Component
public class ClienteIdentidadHttp implements ClienteIdentidad {

    private static final Logger log = LoggerFactory.getLogger(ClienteIdentidadHttp.class);

    private final RestClient restClient;

    public ClienteIdentidadHttp(
            @Value("${envios.autenticacion.identidad-url:http://identidad:8083}") String identidadUrl,
            @Value("${envios.autenticacion.timeout-ms:2000}") int timeoutMs) {
        String base = identidadUrl.endsWith("/")
                ? identidadUrl.substring(0, identidadUrl.length() - 1)
                : identidadUrl;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));

        this.restClient = RestClient.builder()
                .baseUrl(base)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public SesionAutenticada validar(String authHeader) {
        try {
            SesionAutenticada sesion = restClient.get()
                    .uri("/api/auth/validar")
                    .header(HttpHeaders.AUTHORIZATION, authHeader)
                    .retrieve()
                    .body(SesionAutenticada.class);

            if (sesion == null) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Respuesta vacía de identidad al validar la sesión");
            }
            return sesion;
        } catch (HttpStatusCodeException ex) {
            int codigo = ex.getStatusCode().value();
            if (codigo == HttpStatus.FORBIDDEN.value()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cuenta sin permiso o bloqueada");
            }
            if (codigo == HttpStatus.UNAUTHORIZED.value()) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token inválido o revocado");
            }
            // Un 404, 405 o 429 no dice nada del token: es identidad mal configurada o saturada.
            log.warn("Falla cerrada: identidad respondió HTTP {} al validar el token", codigo);
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Servicio de identidad no disponible");
        } catch (RestClientException ex) {
            log.warn("Falla cerrada al validar token contra identidad: {}", ex.getMessage());
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Servicio de identidad no disponible; no se puede validar el token");
        }
    }
}
