package mx.distrinubs.observabilidad;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class EstadoServiciosControllerTest {

    private JdbcTemplate jdbc;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        EstadoServiciosController controller = new EstadoServiciosController(jdbc, 2);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET /api/observabilidad/estado: devuelve lista de servicios, estado global y métricas")
    void obtenerEstadoExitoso() throws Exception {
        when(jdbc.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForObject(eq("SELECT count(*) FROM envios WHERE estado = 'EN_COLA'"), eq(Integer.class))).thenReturn(3);
        when(jdbc.queryForObject(eq("SELECT count(*) FROM leases_evaluacion WHERE vence_en > now()"), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForObject(eq("SELECT count(*) FROM cuentas"), eq(Integer.class))).thenReturn(5);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of("Distrinubs Round 1"));

        mockMvc.perform(get("/api/observabilidad/estado"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estadoGlobal", equalTo("UP")))
                .andExpect(jsonPath("$.servicios", hasSize(greaterThanOrEqualTo(6))))
                .andExpect(jsonPath("$.metricas.enviosEnCola", equalTo(3)))
                .andExpect(jsonPath("$.metricas.leasesActivos", equalTo(1)))
                .andExpect(jsonPath("$.metricas.totalCuentas", equalTo(5)))
                .andExpect(jsonPath("$.metricas.concursoActivo", equalTo("Distrinubs Round 1")));
    }
}
