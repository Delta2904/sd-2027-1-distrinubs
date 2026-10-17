package mx.distrinubs.tablaposiciones;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
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

class TablaPosicionesControllerTest {

    private JdbcTemplate jdbc;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        TablaPosicionesController controller = new TablaPosicionesController(jdbc);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET /api/concursos/activo/tabla: responde 404 si no hay concurso abierto")
    void obtenerTabla_SinConcursoAbierto() throws Exception {
        when(jdbc.query(contains("FROM concursos"), any(RowMapper.class)))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/concursos/activo/tabla"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/concursos/activo/tabla: calcula resueltos, penalización (minuto + 5*fallos) y desempate por tiempo")
    void obtenerTabla_CalculoCorrectoYDesempate() throws Exception {
        Instant inicio = Instant.parse("2026-10-17T00:00:00Z");
        Instant fin = Instant.parse("2026-10-17T05:00:00Z");

        // 1. Concurso activo
        TablaPosicionesController.ConcursoInfo concurso =
                new TablaPosicionesController.ConcursoInfo(1L, "Distrinubs Round 1", inicio, fin);
        when(jdbc.query(contains("FROM concursos"), any(RowMapper.class)))
                .thenReturn(List.of(concurso));

        // 2. Sin retraso de envíos pendientes
        when(jdbc.query(contains("MIN(e.llegada_en)"), any(RowMapper.class), eq(1L)))
                .thenReturn(Collections.emptyList());

        // 3. Problemas del concurso: Problema A y B
        TablaPosicionesController.ProblemaInfo probA =
                new TablaPosicionesController.ProblemaInfo(10L, "A", "Problema A", 100);
        TablaPosicionesController.ProblemaInfo probB =
                new TablaPosicionesController.ProblemaInfo(20L, "B", "Problema B", 100);
        when(jdbc.query(contains("FROM problemas"), any(RowMapper.class), eq(1L)))
                .thenReturn(List.of(probA, probB));

        // 4. Cuentas: alumno1, alumno2, alumno3
        TablaPosicionesController.CuentaInfo c1 = new TablaPosicionesController.CuentaInfo(1L, "alumno1", "Alumno Uno");
        TablaPosicionesController.CuentaInfo c2 = new TablaPosicionesController.CuentaInfo(2L, "alumno2", "Alumno Dos");
        TablaPosicionesController.CuentaInfo c3 = new TablaPosicionesController.CuentaInfo(3L, "alumno3", "Alumno Tres");
        when(jdbc.query(contains("FROM cuentas"), any(RowMapper.class)))
                .thenReturn(List.of(c1, c2, c3));

        // 5. Envíos:
        // - alumno1:
        //     Problema A: fallo a min 5, fallo a min 8, ACCEPTED a min 14 -> penalización = 14 + 2*5 = 24.
        //     Envío posterior a la aceptación a min 20 (WA) -> no debe penalizar.
        // - alumno2:
        //     Problema A: fallo a min 4, ACCEPTED a min 19 -> penalización = 19 + 1*5 = 24.
        //     Empata en resueltos (1) y penalización (24) con alumno1.
        //     Desempate: alumno1 aceptó a min 14, alumno2 a min 19. Alumno 1 va primero.
        // - alumno3:
        //     Sin envíos -> 0 resueltos, 0 penalización.
        Instant tEnv1 = inicio.plusSeconds(5 * 60);
        Instant tEnv2 = inicio.plusSeconds(8 * 60);
        Instant tEnv3 = inicio.plusSeconds(14 * 60);
        Instant tEnv4 = inicio.plusSeconds(20 * 60);

        Instant tEnv5 = inicio.plusSeconds(4 * 60);
        Instant tEnv6 = inicio.plusSeconds(19 * 60);

        List<TablaPosicionesController.EnvioEvaluado> envios = List.of(
                new TablaPosicionesController.EnvioEvaluado(101L, 1L, 10L, tEnv1, "WRONG_ANSWER"),
                new TablaPosicionesController.EnvioEvaluado(102L, 1L, 10L, tEnv2, "TIME_LIMIT_EXCEEDED"),
                new TablaPosicionesController.EnvioEvaluado(103L, 1L, 10L, tEnv3, "ACCEPTED"),
                new TablaPosicionesController.EnvioEvaluado(104L, 1L, 10L, tEnv4, "WRONG_ANSWER"), // posterior, no penaliza
                new TablaPosicionesController.EnvioEvaluado(105L, 2L, 10L, tEnv5, "WRONG_ANSWER"),
                new TablaPosicionesController.EnvioEvaluado(106L, 2L, 10L, tEnv6, "ACCEPTED"));

        when(jdbc.query(contains("r.veredicto"), any(RowMapper.class), eq(1L)))
                .thenReturn(envios);

        mockMvc.perform(get("/api/concursos/activo/tabla"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.concursoId", equalTo(1)))
                .andExpect(jsonPath("$.concursoNombre", equalTo("Distrinubs Round 1")))
                .andExpect(jsonPath("$.avisoOrganizadores", equalTo(false)))
                .andExpect(jsonPath("$.filas", hasSize(3)))
                // Alumno 1: posición 1, 1 resuelto, 24 penalización
                .andExpect(jsonPath("$.filas[0].posicion", equalTo(1)))
                .andExpect(jsonPath("$.filas[0].usuario", equalTo("alumno1")))
                .andExpect(jsonPath("$.filas[0].resueltos", equalTo(1)))
                .andExpect(jsonPath("$.filas[0].penalizacion", equalTo(24)))
                // Alumno 2: posición 2 (desempatado por tiempo de aceptación 14 min < 19 min), 1 resuelto, 24 pen
                .andExpect(jsonPath("$.filas[1].posicion", equalTo(2)))
                .andExpect(jsonPath("$.filas[1].usuario", equalTo("alumno2")))
                .andExpect(jsonPath("$.filas[1].resueltos", equalTo(1)))
                .andExpect(jsonPath("$.filas[1].penalizacion", equalTo(24)))
                // Alumno 3: posición 3, 0 resueltos, 0 penalización
                .andExpect(jsonPath("$.filas[2].posicion", equalTo(3)))
                .andExpect(jsonPath("$.filas[2].usuario", equalTo("alumno3")))
                .andExpect(jsonPath("$.filas[2].resueltos", equalTo(0)))
                .andExpect(jsonPath("$.filas[2].penalizacion", equalTo(0)));
    }

    @Test
    @DisplayName("GET /api/concursos/activo/tabla: activa avisoOrganizadores si el retraso supera 5 minutos")
    void obtenerTabla_AvisoRetrasoOrganizadores() throws Exception {
        Instant inicio = Instant.parse("2026-10-17T00:00:00Z");
        Instant fin = Instant.parse("2026-10-17T05:00:00Z");

        TablaPosicionesController.ConcursoInfo concurso =
                new TablaPosicionesController.ConcursoInfo(1L, "Distrinubs Round 1", inicio, fin);
        when(jdbc.query(contains("FROM concursos"), any(RowMapper.class)))
                .thenReturn(List.of(concurso));

        // Simula un envío en cola con llegada hace 10 minutos (600 s > 300 s)
        Instant llegadaAntigua = Instant.now().minusSeconds(600);
        when(jdbc.query(contains("MIN(e.llegada_en)"), any(RowMapper.class), eq(1L)))
                .thenReturn(List.of(Timestamp.from(llegadaAntigua)));

        when(jdbc.query(contains("FROM problemas"), any(RowMapper.class), eq(1L)))
                .thenReturn(Collections.emptyList());
        when(jdbc.query(contains("FROM cuentas"), any(RowMapper.class)))
                .thenReturn(Collections.emptyList());
        when(jdbc.query(contains("r.veredicto"), any(RowMapper.class), eq(1L)))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/concursos/activo/tabla"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avisoOrganizadores", equalTo(true)));
    }
}
