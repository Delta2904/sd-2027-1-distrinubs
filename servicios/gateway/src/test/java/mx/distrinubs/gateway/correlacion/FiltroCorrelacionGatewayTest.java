package mx.distrinubs.gateway.correlacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class FiltroCorrelacionGatewayTest {

    private final FiltroCorrelacionGateway filtro = new FiltroCorrelacionGateway();

    @Test
    @DisplayName("El id que llega se manda igual al servicio y vuelve en la respuesta")
    void propagaElIdQueLlega() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/concursos/activo").header(FiltroCorrelacionGateway.ENCABEZADO, "abc-123"));
        AtomicReference<ServerWebExchange> visto = new AtomicReference<>();
        // El servicio de mentira responde con su propio encabezado, como hacen los servicios reales.
        GatewayFilterChain cadena = e -> {
            visto.set(e);
            e.getResponse().getHeaders().add(FiltroCorrelacionGateway.ENCABEZADO, "abc-123");
            return e.getResponse().setComplete();
        };

        StepVerifier.create(filtro.filter(exchange, cadena)).verifyComplete();

        assertEquals("abc-123", visto.get().getRequest().getHeaders().getFirst(FiltroCorrelacionGateway.ENCABEZADO));
        assertEquals(java.util.List.of("abc-123"),
                exchange.getResponse().getHeaders().get(FiltroCorrelacionGateway.ENCABEZADO),
                "El encabezado llega una sola vez, aunque el servicio también lo mande");
    }

    @Test
    @DisplayName("Sin id genera uno, y uno con caracteres inválidos se limpia antes de mandarlo")
    void generaOLimpiaElId() {
        MockServerWebExchange sinId = MockServerWebExchange.from(MockServerHttpRequest.get("/api/concursos/activo"));
        AtomicReference<ServerWebExchange> visto = new AtomicReference<>();
        StepVerifier.create(filtro.filter(sinId, e -> {
            visto.set(e);
            return e.getResponse().setComplete();
        })).verifyComplete();
        String generado = visto.get().getRequest().getHeaders().getFirst(FiltroCorrelacionGateway.ENCABEZADO);
        assertNotNull(generado);
        assertEquals(36, generado.length());
        assertEquals(generado, sinId.getResponse().getHeaders().getFirst(FiltroCorrelacionGateway.ENCABEZADO));

        assertEquals("ab", FiltroCorrelacionGateway.limpiar("a b"));
        assertEquals(64, FiltroCorrelacionGateway.limpiar("x".repeat(200)).length());
        assertEquals(36, FiltroCorrelacionGateway.limpiar("  ").length());
    }
}
