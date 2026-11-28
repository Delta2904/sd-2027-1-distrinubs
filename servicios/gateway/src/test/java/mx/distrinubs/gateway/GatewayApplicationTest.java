package mx.distrinubs.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;

import mx.distrinubs.gateway.autenticacion.FiltroAutenticacionGateway;

@SpringBootTest
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "EUREKA_CLIENT_ENABLED=false",
        "spring.cloud.discovery.enabled=false",
        "JWT_SECRETO=secreto-prueba-gateway-distrinubs",
        "IDENTIDAD_URL=http://localhost:8083",
        "RUTA_CONCURSOS_URI=http://localhost:8082",
        "RUTA_CUENTAS_DEMO_URI=http://localhost:8082",
        "RUTA_ENVIOS_URI=http://localhost:8081",
        "RUTA_AUTH_URI=http://localhost:8083",
        "RUTA_IDENTIDAD_URI=http://localhost:8083",
        "RUTA_MARCADOR_URI=http://localhost:8084"
})
class GatewayApplicationTest {

    @Autowired
    private RouteLocator routeLocator;

    @Autowired
    private FiltroAutenticacionGateway filtroAutenticacion;

    @Test
    void contextLoadsYRutasConfiguradas() {
        assertNotNull(routeLocator);
        assertNotNull(filtroAutenticacion);

        Map<String, Route> rutas = routeLocator.getRoutes()
                .collectMap(Route::getId)
                .block();

        assertNotNull(rutas);
        assertEquals(6, rutas.size());

        verificarRuta(rutas, "ruta-concursos", "http://localhost:8082", "/api/concursos/activo");
        verificarRuta(rutas, "ruta-cuentas-demo", "http://localhost:8082", "/api/cuentas-demo");
        verificarRuta(rutas, "ruta-envios", "http://localhost:8081", "/api/envios/1");
        verificarRuta(rutas, "ruta-auth", "http://localhost:8083", "/api/auth/login");
        verificarRuta(rutas, "ruta-identidad", "http://localhost:8083", "/api/identidad/cuentas");
        verificarRuta(rutas, "ruta-marcador", "http://localhost:8084", "/api/marcador/tabla");
    }

    private static void verificarRuta(Map<String, Route> rutas, String id, String uriEsperada, String pathPrueba) {
        Route ruta = rutas.get(id);
        assertNotNull(ruta, "Falta la ruta " + id);
        assertEquals(URI.create(uriEsperada), ruta.getUri(), "URI inesperada para " + id);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get(pathPrueba).build());
        Boolean coincide = Mono.from(ruta.getPredicate().apply(exchange)).block();
        assertTrue(Boolean.TRUE.equals(coincide), "La ruta " + id + " debería coincidir con " + pathPrueba);
    }
}
