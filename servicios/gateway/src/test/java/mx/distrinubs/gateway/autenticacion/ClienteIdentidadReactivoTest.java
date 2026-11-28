package mx.distrinubs.gateway.autenticacion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ClienteIdentidadReactivoTest {

    /** Cliente cuya llamada a {@code identidad} siempre recibe la respuesta dada, sin red. */
    private ClienteIdentidadReactivo clienteQueRecibe(ClientResponse respuesta) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(peticion -> Mono.just(respuesta));
        return new ClienteIdentidadReactivo(builder, "http://identidad:8083", 2000);
    }

    private void verificarTraduccion(HttpStatus deIdentidad, HttpStatus esperado) {
        ClienteIdentidadReactivo cliente = clienteQueRecibe(ClientResponse.create(deIdentidad).build());

        StepVerifier.create(cliente.validarRemoto("Bearer token"))
                .expectErrorSatisfies(error -> {
                    ResponseStatusException rse = (ResponseStatusException) error;
                    assertEquals(esperado, rse.getStatusCode(), "identidad respondió " + deIdentidad.value());
                })
                .verify();
    }

    @Test
    @DisplayName("Solo el 401 y el 403 de identidad se devuelven como tales")
    void traduceSolo401Y403() {
        verificarTraduccion(HttpStatus.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        verificarTraduccion(HttpStatus.FORBIDDEN, HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Cualquier otro código de identidad es falla del servicio (503), no token inválido")
    void otrosCodigosSonServicioNoDisponible() {
        verificarTraduccion(HttpStatus.NOT_FOUND, HttpStatus.SERVICE_UNAVAILABLE);
        verificarTraduccion(HttpStatus.METHOD_NOT_ALLOWED, HttpStatus.SERVICE_UNAVAILABLE);
        verificarTraduccion(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.SERVICE_UNAVAILABLE);
        verificarTraduccion(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("Una sesión válida de identidad se devuelve tal cual")
    void sesionValidaSeDevuelve() {
        ClientResponse respuesta = ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("""
                        {"cuentaId": 4, "usuario": "alumno4", "nombre": "Alumno Cuatro",
                         "rol": "ALUMNO", "exp": 9999999999, "jti": "jti-4"}
                        """)
                .build();

        StepVerifier.create(clienteQueRecibe(respuesta).validarRemoto("Bearer token"))
                .assertNext(sesion -> {
                    assertEquals(4L, sesion.cuentaId());
                    assertEquals("alumno4", sesion.usuario());
                    assertEquals("ALUMNO", sesion.rol());
                })
                .verifyComplete();
    }
}
