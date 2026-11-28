package mx.distrinubs.observabilidad;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class EstadoServiciosControllerTest {

    /** Presupuesto corto para que las pruebas no esperen los 6 s reales. */
    private static final Duration PRESUPUESTO_PRUEBA = Duration.ofMillis(300);

    /** Margen para comprobar que la respuesta no espera a la verificación bloqueada. */
    private static final Duration RESPUESTA_A_TIEMPO = Duration.ofSeconds(3);

    private DataSource dataSource;
    private HttpClient httpClient;

    @BeforeEach
    void setup() throws Exception {
        dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);

        httpClient = mock(HttpClient.class);
    }

    @Test
    @DisplayName("GET /api/observabilidad/estado: con servicios no configurados el estado global es DEGRADADO, cada uno NO_DISPONIBLE y la base de datos UP")
    void serviciosNoConfiguradosReportanNoDisponible() throws Exception {
        EstadoServiciosController controller =
                new EstadoServiciosController(dataSource, httpClient, Map.of(), PRESUPUESTO_PRUEBA);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/observabilidad/estado"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estadoGlobal", equalTo("DEGRADADO")))
                .andExpect(jsonPath("$.servicios", hasSize(9)))
                .andExpect(jsonPath("$.resumen.activos", equalTo(1)))
                .andExpect(jsonPath("$.resumen.noDisponibles", equalTo(8)))
                .andExpect(jsonPath("$.servicios[?(@.nombre=='gateway')].estado", hasItem("NO_DISPONIBLE")))
                .andExpect(jsonPath("$.servicios[?(@.nombre=='identidad')].estado", hasItem("NO_DISPONIBLE")))
                .andExpect(jsonPath("$.servicios[?(@.nombre=='basedatos')].estado", hasItem("UP")));
    }

    @Test
    @DisplayName("GET /api/observabilidad/estado: consulta /actuator/health cuando el servicio está configurado")
    @SuppressWarnings("unchecked")
    void consultaHealthDeServiciosConfigurados() throws Exception {
        HttpResponse<String> respOk = mock(HttpResponse.class);
        when(respOk.statusCode()).thenReturn(200);
        HttpResponse<String> respCaido = mock(HttpResponse.class);
        when(respCaido.statusCode()).thenReturn(503);

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(inv -> {
                    HttpRequest req = inv.getArgument(0);
                    return req.uri().toString().contains("8081") ? respOk : respCaido;
                });

        EstadoServiciosController controller = new EstadoServiciosController(
                dataSource,
                httpClient,
                Map.of("gateway", "http://localhost:8081", "identidad", "http://localhost:8082"),
                PRESUPUESTO_PRUEBA);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/observabilidad/estado"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estadoGlobal", equalTo("DEGRADADO")))
                .andExpect(jsonPath("$.resumen.activos", equalTo(2)))
                .andExpect(jsonPath("$.resumen.caidos", equalTo(1)))
                .andExpect(jsonPath("$.servicios[?(@.nombre=='gateway')].estado", hasItem("UP")))
                .andExpect(jsonPath("$.servicios[?(@.nombre=='identidad')].estado", hasItem("DOWN")));
    }

    @Test
    @DisplayName("GET /api/observabilidad/estado: si la base de datos no contesta dentro del presupuesto, responde a tiempo y la reporta DOWN")
    void baseDeDatosBloqueadaNoRetrasaLaRespuesta() throws Exception {
        CountDownLatch soltar = new CountDownLatch(1);
        when(dataSource.getConnection()).thenAnswer(inv -> {
            soltar.await(30, TimeUnit.SECONDS);
            throw new SQLException("conexión agotada");
        });
        EstadoServiciosController controller =
                new EstadoServiciosController(dataSource, httpClient, Map.of(), PRESUPUESTO_PRUEBA);
        try {
            EstadoServiciosResponse respuesta = assertTimeout(RESPUESTA_A_TIEMPO, controller::obtenerEstado);

            EstadoServiciosResponse.ServicioDetalle basedatos = detalleDe(respuesta, "basedatos");
            assertEquals("DOWN", basedatos.estado());
            assertEquals("Sin respuesta en 300 ms", basedatos.detalle());
            assertEquals("DEGRADADO", respuesta.estadoGlobal());
            assertEquals(1, respuesta.resumen().caidos());
            assertEquals(9, respuesta.servicios().size());
        } finally {
            soltar.countDown();
            controller.cerrar();
        }
    }

    @Test
    @DisplayName("GET /api/observabilidad/estado: un servicio configurado que no contesta a tiempo conserva su nombre y queda NO_DISPONIBLE")
    @SuppressWarnings("unchecked")
    void servicioConfiguradoQueNoRespondeATiempoConservaSuNombre() throws Exception {
        CountDownLatch soltar = new CountDownLatch(1);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(inv -> {
                    soltar.await(30, TimeUnit.SECONDS);
                    throw new java.io.IOException("sin respuesta");
                });
        EstadoServiciosController controller = new EstadoServiciosController(
                dataSource, httpClient, Map.of("envios", "http://localhost:8083"), PRESUPUESTO_PRUEBA);
        try {
            EstadoServiciosResponse respuesta = assertTimeout(RESPUESTA_A_TIEMPO, controller::obtenerEstado);

            EstadoServiciosResponse.ServicioDetalle envios = detalleDe(respuesta, "envios");
            assertEquals("NO_DISPONIBLE", envios.estado());
            assertEquals("servicio", envios.tipo());
            assertEquals("Sin respuesta en 300 ms", envios.detalle());
            assertEquals("UP", detalleDe(respuesta, "basedatos").estado());
            assertEquals(0, respuesta.servicios().stream().filter(s -> "unknown".equals(s.nombre())).count());
            assertEquals(9, respuesta.servicios().size());
        } finally {
            soltar.countDown();
            controller.cerrar();
        }
    }

    private static EstadoServiciosResponse.ServicioDetalle detalleDe(EstadoServiciosResponse respuesta, String nombre) {
        return respuesta.servicios().stream()
                .filter(s -> nombre.equals(s.nombre()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Falta la tarjeta de " + nombre));
    }
}
