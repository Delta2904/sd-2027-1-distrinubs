package mx.distrinubs.gateway;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.test.context.TestPropertySource;

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
    }
}
