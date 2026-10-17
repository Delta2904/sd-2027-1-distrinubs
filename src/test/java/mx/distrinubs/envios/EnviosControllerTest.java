package mx.distrinubs.envios;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class EnviosControllerTest {

    private JdbcTemplate jdbc;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        EnviosController controller = new EnviosController(jdbc);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("POST /api/envios exitoso: guarda en la base y responde 201 con estado recibido")
    void crearEnvio_Exitoso() throws Exception {
        UUID intentoId = UUID.randomUUID();

        // Simula existencia de alumno y problema
        when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM cuentas WHERE id = ?"), eq(Integer.class), eq(1L)))
                .thenReturn(1);
        when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM problemas WHERE id = ?"), eq(Integer.class), eq(2L)))
                .thenReturn(1);

        // Simula INSERT exitoso retornando ID 100
        when(jdbc.queryForObject(
                anyString(),
                eq(Long.class),
                eq(intentoId),
                eq(1L),
                eq(2L),
                eq("PYTHON3"),
                eq("print('hola')")))
                .thenReturn(100L);

        String json = """
                {
                  "alumnoId": 1,
                  "problemaId": 2,
                  "intentoId": "%s",
                  "lenguaje": "PYTHON3",
                  "codigo": "print('hola')"
                }
                """.formatted(intentoId);

        mockMvc.perform(post("/api/envios")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.envioId", equalTo(100)))
                .andExpect(jsonPath("$.estado", equalTo("recibido")));
    }

    @Test
    @DisplayName("POST /api/envios con intentoId repetido: choque de unicidad responde 200 con el envío existente")
    void crearEnvio_ReintentoIdempotente() throws Exception {
        UUID intentoId = UUID.randomUUID();

        when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM cuentas WHERE id = ?"), eq(Integer.class), eq(1L)))
                .thenReturn(1);
        when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM problemas WHERE id = ?"), eq(Integer.class), eq(2L)))
                .thenReturn(1);

        // Falla por restricción UNIQUE(cuenta_id, id_intento)
        when(jdbc.queryForObject(
                anyString(),
                eq(Long.class),
                eq(intentoId),
                eq(1L),
                eq(2L),
                eq("PYTHON3"),
                eq("print('reintento')")))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        // Recupera el ID previamente guardado
        when(jdbc.query(
                eq("SELECT id FROM envios WHERE cuenta_id = ? AND id_intento = ?"),
                any(RowMapper.class),
                eq(1L),
                eq(intentoId)))
                .thenReturn(List.of(100L));

        String json = """
                {
                  "alumnoId": 1,
                  "problemaId": 2,
                  "intentoId": "%s",
                  "lenguaje": "PYTHON3",
                  "codigo": "print('reintento')"
                }
                """.formatted(intentoId);

        mockMvc.perform(post("/api/envios")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.envioId", equalTo(100)))
                .andExpect(jsonPath("$.estado", equalTo("recibido")));
    }

    @Test
    @DisplayName("POST /api/envios con alumno inexistente: responde 400 Bad Request")
    void crearEnvio_AlumnoInexistente() throws Exception {
        when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM cuentas WHERE id = ?"), eq(Integer.class), eq(999L)))
                .thenReturn(0);

        String json = """
                {
                  "alumnoId": 999,
                  "problemaId": 2,
                  "intentoId": "%s",
                  "lenguaje": "PYTHON3",
                  "codigo": "print('hola')"
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/envios")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/envios con lenguaje inválido: responde 400 Bad Request")
    void crearEnvio_LenguajeInvalido() throws Exception {
        String json = """
                {
                  "alumnoId": 1,
                  "problemaId": 2,
                  "intentoId": "%s",
                  "lenguaje": "JAVA",
                  "codigo": "System.out.println();"
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/envios")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/envios/{id} no encontrado: responde 404")
    void obtenerEstado_NoEncontrado() throws Exception {
        when(jdbc.query(eq("SELECT estado FROM envios WHERE id = ?"), any(RowMapper.class), eq(555L)))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/envios/555"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/envios/{id} en cola: sin lease ni resultado, responde 'en cola'")
    void obtenerEstado_EnCola() throws Exception {
        when(jdbc.query(eq("SELECT estado FROM envios WHERE id = ?"), any(RowMapper.class), eq(100L)))
                .thenReturn(List.of("EN_COLA"));
        when(jdbc.query(eq("SELECT veredicto, tiempo_ms, memoria_kb, detalle FROM resultados WHERE envio_id = ?"),
                any(RowMapper.class), eq(100L)))
                .thenReturn(Collections.emptyList());
        when(jdbc.queryForObject(
                eq("SELECT COUNT(*) FROM leases_evaluacion WHERE envio_id = ? AND vence_en > now()"),
                eq(Integer.class), eq(100L)))
                .thenReturn(0);

        mockMvc.perform(get("/api/envios/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.envioId", equalTo(100)))
                .andExpect(jsonPath("$.estado", equalTo("en cola")))
                .andExpect(jsonPath("$.resultado").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/envios/{id} evaluando: con lease vigente, deriva 'evaluando'")
    void obtenerEstado_Evaluando() throws Exception {
        when(jdbc.query(eq("SELECT estado FROM envios WHERE id = ?"), any(RowMapper.class), eq(100L)))
                .thenReturn(List.of("EN_COLA"));
        when(jdbc.query(eq("SELECT veredicto, tiempo_ms, memoria_kb, detalle FROM resultados WHERE envio_id = ?"),
                any(RowMapper.class), eq(100L)))
                .thenReturn(Collections.emptyList());
        when(jdbc.queryForObject(
                eq("SELECT COUNT(*) FROM leases_evaluacion WHERE envio_id = ? AND vence_en > now()"),
                eq(Integer.class), eq(100L)))
                .thenReturn(1);

        mockMvc.perform(get("/api/envios/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.envioId", equalTo(100)))
                .andExpect(jsonPath("$.estado", equalTo("evaluando")))
                .andExpect(jsonPath("$.resultado").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/envios/{id} evaluado: responde 'evaluado' con objeto resultado y veredicto")
    void obtenerEstado_Evaluado() throws Exception {
        when(jdbc.query(eq("SELECT estado FROM envios WHERE id = ?"), any(RowMapper.class), eq(100L)))
                .thenReturn(List.of("EN_COLA"));

        ResultadoEnvioDto resultado = new ResultadoEnvioDto("ACCEPTED", 15, 2048, null);
        when(jdbc.query(eq("SELECT veredicto, tiempo_ms, memoria_kb, detalle FROM resultados WHERE envio_id = ?"),
                any(RowMapper.class), eq(100L)))
                .thenReturn(List.of(resultado));

        mockMvc.perform(get("/api/envios/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.envioId", equalTo(100)))
                .andExpect(jsonPath("$.estado", equalTo("evaluado")))
                .andExpect(jsonPath("$.veredicto", equalTo("ACCEPTED")))
                .andExpect(jsonPath("$.tiempoMs", equalTo(15)))
                .andExpect(jsonPath("$.resultado.veredicto", equalTo("ACCEPTED")))
                .andExpect(jsonPath("$.resultado.tiempoMs", equalTo(15)))
                .andExpect(jsonPath("$.resultado.memoriaKb", equalTo(2048)))
                .andExpect(jsonPath("$.resultado.detalle").doesNotExist());
    }
}
