package mx.distrinubs.iniciosesion;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerTest {

    private JdbcTemplate jdbc;
    private TokenService tokenService;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        tokenService = new TokenService();
        AuthController controller = new AuthController(jdbc, tokenService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("POST /api/auth/login: autentica exitosamente y devuelve token firmado con sub, rol y exp")
    void loginExitoso() throws Exception {
        CuentasDemoController.CuentaDemo cuenta = new CuentasDemoController.CuentaDemo(1L, "alumno1", "Alumno Uno");

        when(jdbc.query(anyString(), any(RowMapper.class), eq("alumno1")))
                .thenReturn(List.of(cuenta));

        String cuerpo = """
                {
                    "usuario": "alumno1",
                    "contrasena": "distrinubs"
                }
                """;

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.usuario", equalTo("alumno1")))
                .andExpect(jsonPath("$.nombre", equalTo("Alumno Uno")))
                .andExpect(jsonPath("$.rol", equalTo("ALUMNO")))
                .andExpect(jsonPath("$.tipo", equalTo("Bearer")))
                .andExpect(jsonPath("$.exp", notNullValue()));
    }

    @Test
    @DisplayName("POST /api/auth/login: rechaza credenciales inexistentes con 401")
    void loginUsuarioInexistente() throws Exception {
        when(jdbc.query(anyString(), any(RowMapper.class), eq("desconocido")))
                .thenReturn(Collections.emptyList());

        String cuerpo = """
                {
                    "usuario": "desconocido",
                    "contrasena": "clave"
                }
                """;

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/auth/yo: valida el token en Authorization y devuelve datos de sesión")
    void obtenerSesionValida() throws Exception {
        String token = tokenService.generarToken(1L, "alumno1", "Alumno Uno", "ALUMNO");

        mockMvc.perform(get("/api/auth/yo")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario", equalTo("alumno1")))
                .andExpect(jsonPath("$.rol", equalTo("ALUMNO")))
                .andExpect(jsonPath("$.cuentaId", equalTo(1)));
    }

    @Test
    @DisplayName("GET /api/auth/yo: rechaza peticiones sin encabezado Authorization con 401")
    void obtenerSesionSinToken() throws Exception {
        mockMvc.perform(get("/api/auth/yo"))
                .andExpect(status().isUnauthorized());
    }
}
