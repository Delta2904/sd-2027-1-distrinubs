package mx.distrinubs.gateway.circuito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.sun.net.httpserver.HttpServer;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * El circuit breaker de una ruta, de punta a punta: el gateway real contra un
 * servicio de mentira que se apaga y se vuelve a prender en el mismo puerto.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "EUREKA_CLIENT_ENABLED=false",
        "spring.cloud.discovery.enabled=false",
        "JWT_SECRETO=secreto-prueba-gateway-distrinubs",
        "IDENTIDAD_URL=http://localhost:8083",
        "distrinubs.circuito.espera-abierto=1s"
})
class CircuitoPorRutaTest {

    private static final String CIRCUITO = "circuito-concursos";
    private static final int PUERTO = puertoLibre();

    @Autowired
    private WebTestClient cliente;

    @Autowired
    private CircuitBreakerRegistry registro;

    private HttpServer servicio;

    @DynamicPropertySource
    static void rutas(DynamicPropertyRegistry propiedades) {
        propiedades.add("RUTA_CONCURSOS_URI", () -> "http://localhost:" + PUERTO);
    }

    @BeforeEach
    void cerrarCircuito() {
        registro.find(CIRCUITO).ifPresent(CircuitBreaker::reset);
    }

    @AfterEach
    void apagarServicio() {
        if (servicio != null) {
            servicio.stop(0);
            servicio = null;
        }
    }

    @Test
    @DisplayName("Si el servicio no contesta, el gateway responde 503 con un JSON que dice qué servicio falta")
    void servicioCaidoResponde503ConElNombreDelServicio() {
        cliente.get().uri("/api/concursos/activo").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.error").isEqualTo("SERVICIO_NO_DISPONIBLE")
                .jsonPath("$.servicio").isEqualTo("concursos")
                .jsonPath("$.status").isEqualTo(503);
    }

    @Test
    @DisplayName("Tras 5 fallas seguidas el circuito abre y deja de intentar la llamada")
    void elCircuitoAbreTrasLasFallas() {
        for (int i = 0; i < 5; i++) {
            cliente.get().uri("/api/concursos/activo").exchange().expectStatus().isEqualTo(503);
        }
        CircuitBreaker circuito = registro.find(CIRCUITO).orElseThrow();
        assertEquals(CircuitBreaker.State.OPEN, circuito.getState());

        // Con el circuito abierto la respuesta es la misma, pero ya no se intenta la llamada.
        cliente.get().uri("/api/concursos/activo").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.servicio").isEqualTo("concursos");
        assertTrue(circuito.getMetrics().getNumberOfNotPermittedCalls() >= 1);
    }

    @Test
    @DisplayName("Cuando el servicio vuelve, las llamadas de prueba salen bien y el circuito cierra")
    void elCircuitoCierraCuandoElServicioVuelve() throws Exception {
        for (int i = 0; i < 5; i++) {
            cliente.get().uri("/api/concursos/activo").exchange().expectStatus().isEqualTo(503);
        }
        CircuitBreaker circuito = registro.find(CIRCUITO).orElseThrow();
        assertEquals(CircuitBreaker.State.OPEN, circuito.getState());

        prenderServicio(200);
        esperarEstado(circuito, CircuitBreaker.State.HALF_OPEN);

        for (int i = 0; i < 2; i++) {
            cliente.get().uri("/api/concursos/activo").exchange()
                    .expectStatus().isOk()
                    .expectBody(String.class).isEqualTo("{\"origen\":\"servicio\"}");
        }
        assertEquals(CircuitBreaker.State.CLOSED, circuito.getState());
    }

    @Test
    @DisplayName("Un error que responde el servicio pasa tal cual y no abre el circuito")
    void unErrorDelServicioNoAbreElCircuito() throws Exception {
        prenderServicio(500);
        for (int i = 0; i < 6; i++) {
            cliente.get().uri("/api/concursos/activo").exchange()
                    .expectStatus().isEqualTo(500)
                    .expectBody(String.class).isEqualTo("{\"origen\":\"servicio\"}");
        }
        assertEquals(CircuitBreaker.State.CLOSED, registro.find(CIRCUITO).orElseThrow().getState());
    }

    private void prenderServicio(int estado) throws IOException {
        servicio = HttpServer.create(new InetSocketAddress("localhost", PUERTO), 0);
        servicio.createContext("/", intercambio -> {
            byte[] cuerpo = "{\"origen\":\"servicio\"}".getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.sendResponseHeaders(estado, cuerpo.length);
            intercambio.getResponseBody().write(cuerpo);
            intercambio.close();
        });
        servicio.start();
    }

    private static void esperarEstado(CircuitBreaker circuito, CircuitBreaker.State esperado)
            throws InterruptedException {
        long limite = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (circuito.getState() != esperado && System.nanoTime() < limite) {
            Thread.sleep(50);
        }
        assertEquals(esperado, circuito.getState());
    }

    private static int puertoLibre() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("No se encontró un puerto libre", e);
        }
    }
}
