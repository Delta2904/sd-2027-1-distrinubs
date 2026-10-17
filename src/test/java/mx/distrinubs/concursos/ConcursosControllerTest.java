package mx.distrinubs.concursos;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ConcursosControllerTest {

    private JdbcTemplate jdbc;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        ConcursosController controller = new ConcursosController(jdbc);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET /api/concursos/activo: devuelve el concurso abierto con sus problemas")
    void obtenerConcursoActivo_Exitoso() throws Exception {
        Instant inicio = Instant.parse("2026-10-17T00:00:00Z");
        Instant fin = Instant.parse("2026-10-18T00:00:00Z");

        // Simula la consulta del concurso activo
        when(jdbc.query(anyString(), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getLong("id")).thenReturn(1L);
                    when(rs.getString("nombre")).thenReturn("Distrinubs Round 1");
                    when(rs.getTimestamp("inicio")).thenReturn(Timestamp.from(inicio));
                    when(rs.getTimestamp("fin")).thenReturn(Timestamp.from(fin));
                    when(rs.getString("estado")).thenReturn("ABIERTO");
                    return List.of(mapper.mapRow(rs, 1));
                });

        // Simula la consulta de problemas para el concurso 1
        ProblemaResumenDto probA = new ProblemaResumenDto(1L, "A", "Suma simple", "Calcula a + b", 1000, 256, 100);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(1L)))
                .thenReturn(List.of(probA));

        mockMvc.perform(get("/api/concursos/activo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(1)))
                .andExpect(jsonPath("$.nombre", equalTo("Distrinubs Round 1")))
                .andExpect(jsonPath("$.estado", equalTo("ABIERTO")))
                .andExpect(jsonPath("$.problemas", hasSize(1)))
                .andExpect(jsonPath("$.problemas[0].letra", equalTo("A")))
                .andExpect(jsonPath("$.problemas[0].titulo", equalTo("Suma simple")))
                .andExpect(jsonPath("$.problemas[0].limiteTiempoMs", equalTo(1000)))
                .andExpect(jsonPath("$.problemas[0].limiteMemoriaMb", equalTo(256)));
    }

    @Test
    @DisplayName("GET /api/concursos/activo: responde 404 si no hay ningún concurso abierto")
    void obtenerConcursoActivo_NoHayAbierto() throws Exception {
        when(jdbc.query(anyString(), any(RowMapper.class)))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/concursos/activo"))
                .andExpect(status().isNotFound());
    }
}
