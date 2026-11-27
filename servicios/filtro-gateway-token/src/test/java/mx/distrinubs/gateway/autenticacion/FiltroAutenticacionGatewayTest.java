package mx.distrinubs.gateway.autenticacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class FiltroAutenticacionGatewayTest {

    private static final String SECRETO = "secreto-prueba-gateway-distrinubs";

    private final ObjectMapper mapper = new ObjectMapper();
    private VerificadorRemotoIdentidad verificadorRemoto;
    private FiltroAutenticacionGateway filtro;

    @BeforeEach
    void preparar() {
        VerificadorJwtGateway verificadorJwt = new VerificadorJwtGateway(mapper, SECRETO);
        verificadorRemoto = mock(VerificadorRemotoIdentidad.class);
        filtro = new FiltroAutenticacionGateway(verificadorJwt, verificadorRemoto);
    }

    private String firmarJwt(long cuentaId, String usuario, String rol) throws Exception {
        ObjectNode header = mapper.createObjectNode();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        ObjectNode payload = mapper.createObjectNode();
        payload.put("sub", String.valueOf(cuentaId));
        payload.put("usuario", usuario);
        payload.put("nombre", usuario);
        payload.put("rol", rol);
        payload.put("jti", UUID.randomUUID().toString());
        payload.put("iat", Instant.now().getEpochSecond());
        payload.put("exp", Instant.now().getEpochSecond() + 3600);

        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String datos = enc.encodeToString(mapper.writeValueAsBytes(header))
                + "." + enc.encodeToString(mapper.writeValueAsBytes(payload));

        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(SECRETO.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return datos + "." + enc.encodeToString(hmac.doFinal(datos.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("Rutas públicas pasan sin token y eliminan cabeceras X-Usuario-* falsificadas")
    void rutaPublicaPasaYLimpiaCabeceras() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login")
                        .header(FiltroAutenticacionGateway.HEADER_USUARIO_ID, "999")
                        .header(FiltroAutenticacionGateway.HEADER_USUARIO_ROL, "ADMINISTRADOR")
                        .build());

        AtomicReference<ServerWebExchange> reenviado = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            reenviado.set(ex);
            return Mono.empty();
        };

        StepVerifier.create(filtro.filter(exchange, chain)).verifyComplete();

        assertNull(reenviado.get().getRequest().getHeaders().getFirst(FiltroAutenticacionGateway.HEADER_USUARIO_ID));
        assertNull(reenviado.get().getRequest().getHeaders().getFirst(FiltroAutenticacionGateway.HEADER_USUARIO_ROL));
        verify(verificadorRemoto, never()).validarRemoto(anyString());
    }

    @Test
    @DisplayName("Ruta protegida sin token responde 401 sin invocar la cadena")
    void rutaProtegidaSinTokenResponde401() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/envios").build());

        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        StepVerifier.create(filtro.filter(exchange, chain)).verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verify(chain, never()).filter(any());
        verify(verificadorRemoto, never()).validarRemoto(anyString());
    }

    @Test
    @DisplayName("Ruta protegida con token alterado responde 401 sin consultar a identidad")
    void rutaProtegidaTokenInvalidoResponde401() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/envios/10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer a.b.c")
                        .build());

        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        StepVerifier.create(filtro.filter(exchange, chain)).verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verify(chain, never()).filter(any());
        verify(verificadorRemoto, never()).validarRemoto(anyString());
    }

    @Test
    @DisplayName("Falla cerrada: si identidad no responde, el gateway rechaza con 503 Service Unavailable")
    void fallaCerradaSiIdentidadNoResponde() throws Exception {
        String token = firmarJwt(1L, "alumno1", "ALUMNO");
        when(verificadorRemoto.validarRemoto("Bearer " + token))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Caído")));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/envios")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());

        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        StepVerifier.create(filtro.filter(exchange, chain)).verifyComplete();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("Token válido reemplaza cabeceras X-Usuario-* falsificadas por la identidad verificada")
    void tokenValidoPropagaIdentidadVerificada() throws Exception {
        String token = firmarJwt(4L, "alumno4", "ALUMNO");
        when(verificadorRemoto.validarRemoto("Bearer " + token))
                .thenReturn(Mono.just(new SesionGateway(4L, "alumno4", "Alumno Cuatro", "ALUMNO", 9999999999L, "jti-4")));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/envios")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(FiltroAutenticacionGateway.HEADER_USUARIO_ID, "1")
                        .header(FiltroAutenticacionGateway.HEADER_USUARIO_ROL, "ADMINISTRADOR")
                        .build());

        AtomicReference<ServerWebExchange> reenviado = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            reenviado.set(ex);
            return Mono.empty();
        };

        StepVerifier.create(filtro.filter(exchange, chain)).verifyComplete();

        HttpHeaders headers = reenviado.get().getRequest().getHeaders();
        assertEquals("4", headers.getFirst(FiltroAutenticacionGateway.HEADER_USUARIO_ID));
        assertEquals("alumno4", headers.getFirst(FiltroAutenticacionGateway.HEADER_USUARIO_SUB));
        assertEquals("ALUMNO", headers.getFirst(FiltroAutenticacionGateway.HEADER_USUARIO_ROL));
    }
}
