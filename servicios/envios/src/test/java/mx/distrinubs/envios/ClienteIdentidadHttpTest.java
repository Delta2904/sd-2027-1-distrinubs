package mx.distrinubs.envios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.sun.net.httpserver.HttpServer;

class ClienteIdentidadHttpTest {

    private HttpServer identidadSimulada;
    private volatile int codigoRespuesta;
    private volatile String cuerpoRespuesta;
    private ClienteIdentidadHttp cliente;

    @BeforeEach
    void levantarIdentidadSimulada() throws IOException {
        identidadSimulada = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        identidadSimulada.createContext("/api/auth/validar", intercambio -> {
            byte[] cuerpo = cuerpoRespuesta.getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.sendResponseHeaders(codigoRespuesta, cuerpo.length);
            try (var salida = intercambio.getResponseBody()) {
                salida.write(cuerpo);
            }
        });
        identidadSimulada.start();
        cliente = new ClienteIdentidadHttp(
                "http://127.0.0.1:" + identidadSimulada.getAddress().getPort(), 2000);
    }

    @AfterEach
    void detenerIdentidadSimulada() {
        identidadSimulada.stop(0);
    }

    private HttpStatus estadoAlValidarCuandoIdentidadResponde(int codigo) {
        codigoRespuesta = codigo;
        cuerpoRespuesta = "{\"error\":\"simulado\"}";
        ResponseStatusException error = assertThrows(
                ResponseStatusException.class, () -> cliente.validar("Bearer token"));
        return HttpStatus.valueOf(error.getStatusCode().value());
    }

    @Test
    @DisplayName("Solo el 401 y el 403 de identidad se devuelven como tales")
    void traduceSolo401Y403() {
        assertEquals(HttpStatus.UNAUTHORIZED, estadoAlValidarCuandoIdentidadResponde(401));
        assertEquals(HttpStatus.FORBIDDEN, estadoAlValidarCuandoIdentidadResponde(403));
    }

    @Test
    @DisplayName("Cualquier otro código de identidad es falla del servicio (503), no token inválido")
    void otrosCodigosSonServicioNoDisponible() {
        for (int codigo : new int[] {404, 405, 429, 500, 502}) {
            assertEquals(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    estadoAlValidarCuandoIdentidadResponde(codigo),
                    "identidad respondió " + codigo);
        }
    }

    @Test
    @DisplayName("Si identidad no responde, falla cerrada con 503")
    void identidadCaidaResponde503() {
        identidadSimulada.stop(0);
        ResponseStatusException error = assertThrows(
                ResponseStatusException.class, () -> cliente.validar("Bearer token"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), error.getStatusCode().value());
    }

    @Test
    @DisplayName("Una sesión válida de identidad se devuelve tal cual")
    void sesionValidaSeDevuelve() {
        codigoRespuesta = 200;
        cuerpoRespuesta = "{\"cuentaId\":4,\"usuario\":\"alumno4\",\"nombre\":\"Alumno Cuatro\","
                + "\"rol\":\"ALUMNO\",\"exp\":9999999999,\"jti\":\"jti-4\"}";

        SesionAutenticada sesion = cliente.validar("Bearer token");

        assertEquals(4L, sesion.cuentaId());
        assertEquals("alumno4", sesion.usuario());
        assertEquals("ALUMNO", sesion.rol());
    }
}
