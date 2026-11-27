package mx.distrinubs.observabilidad;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
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
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class EstadoServiciosControllerTest {

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
    @DisplayName("GET /api/observabilidad/estado: reporta NO_DISPONIBLE para servicios no configurados y UP para base de datos")
    void serviciosNoConfiguradosReportanNoDisponible() throws Exception {
        EstadoServiciosController controller = new EstadoServiciosController(dataSource, httpClient, Map.of());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/observabilidad/estado"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estadoGlobal", equalTo("UP")))
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
                Map.of("gateway", "http://localhost:8081", "identidad", "http://localhost:8082"));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/observabilidad/estado"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estadoGlobal", equalTo("DEGRADADO")))
                .andExpect(jsonPath("$.resumen.activos", equalTo(2)))
                .andExpect(jsonPath("$.resumen.caidos", equalTo(1)))
                .andExpect(jsonPath("$.servicios[?(@.nombre=='gateway')].estado", hasItem("UP")))
                .andExpect(jsonPath("$.servicios[?(@.nombre=='identidad')].estado", hasItem("DOWN")));
    }
}
