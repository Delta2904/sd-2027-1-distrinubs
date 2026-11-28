package mx.distrinubs.identidad;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.Base64;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

class IdentidadIntegracionTest {

    private static JdbcTemplate jdbcCompartido;

    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private SiembraCuentasIdentidad siembra;
    private IdentidadService identidadService;
    private MockMvc mockMvc;

    private static synchronized JdbcTemplate obtenerJdbc() {
        if (jdbcCompartido == null) {
            try {
                DataSource fuente = EmbeddedPostgres.builder().start().getPostgresDatabase();
                Flyway.configure()
                        .dataSource(fuente)
                        .locations("classpath:db/migration")
                        .outOfOrder(true)
                        .load()
                        .migrate();
                jdbcCompartido = new JdbcTemplate(fuente);
            } catch (IOException e) {
                throw new IllegalStateException("No arrancó PostgreSQL embebido", e);
            }
        }
        return jdbcCompartido;
    }

    @BeforeEach
    void preparar() {
        jdbc = obtenerJdbc();
        jdbc.execute("TRUNCATE tokens_revocados, resultados, leases_evaluacion, envios, "
                + "casos_prueba, problemas, concursos, cuentas RESTART IDENTITY CASCADE");

        TokenJwtService tokenService = new TokenJwtService(
                mapper, "secreto-integracion-identidad-distrinubs", 3600);
        identidadService = new IdentidadService(jdbc, tokenService);
        siembra = new SiembraCuentasIdentidad(jdbc, 0, "distrinubs", "claveAdminSegura!");
        siembra.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(
                new AuthController(identidadService),
                new CuentasAdminController(identidadService),
                new CuentasDemoController(identidadService))
                .build();
    }

    private String iniciarSesion(String usuario, String contrasena) throws Exception {
        String cuerpo = """
                {
                  "usuario": "%s",
                  "contrasena": "%s"
                }
                """.formatted(usuario, contrasena);

        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = mapper.readTree(res.getResponse().getContentAsString());
        return json.get("token").asText();
    }

    @Test
    @DisplayName("Siembra es idempotente y guarda contraseñas con hash BCrypt y roles")
    void siembraIdempotenteConHashBCrypt() {
        siembra.afterPropertiesSet();

        Integer totalCuentas = jdbc.queryForObject("SELECT COUNT(*) FROM cuentas", Integer.class);
        assertEquals(7, totalCuentas);

        String hash = jdbc.queryForObject(
                "SELECT contrasena_hash FROM cuentas WHERE usuario = 'alumno1'",
                String.class);
        assertNotEquals("distrinubs", hash);
        org.junit.jupiter.api.Assertions.assertTrue(hash.startsWith("$2"));
    }

    @Test
    @DisplayName("Login exitoso devuelve token JWT con rol y funciona en /api/auth/yo y /api/auth/validar")
    void loginExitosoYValidacion() throws Exception {
        String token = iniciarSesion("alumno1", "distrinubs");

        mockMvc.perform(get("/api/auth/yo")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cuentaId", equalTo(1)))
                .andExpect(jsonPath("$.usuario", equalTo("alumno1")))
                .andExpect(jsonPath("$.rol", equalTo("ALUMNO")))
                .andExpect(jsonPath("$.jti", notNullValue()));

        mockMvc.perform(get("/api/auth/validar")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario", equalTo("alumno1")));
    }

    @Test
    @DisplayName("Credenciales inválidas responden 401 con el mismo mensaje para usuario inexistente o contraseña incorrecta")
    void credencialesInvalidasSinRevelarUsuario() throws Exception {
        MvcResult usuarioInexistente = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"usuario": "no_existe", "contrasena": "abc"}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn();

        MvcResult contrasenaMala = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"usuario": "alumno1", "contrasena": "incorrecta"}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertEquals(
                usuarioInexistente.getResponse().getErrorMessage(),
                contrasenaMala.getResponse().getErrorMessage());
    }

    @Test
    @DisplayName("Logout revoca inmediatamente el jti del token en tokens_revocados")
    void logoutRevocaToken() throws Exception {
        String token = iniciarSesion("alumno1", "distrinubs");

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado", equalTo("sesion_cerrada")));

        mockMvc.perform(get("/api/auth/validar")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Bloquear cuenta revoca sus tokens activos e impide nuevos inicios de sesión hasta desbloquear")
    void bloquearYDesbloquearCuenta() throws Exception {
        String tokenAlumno = iniciarSesion("alumno2", "distrinubs");
        String tokenAdmin = iniciarSesion("admin", "claveAdminSegura!");

        mockMvc.perform(post("/api/identidad/cuentas/2/bloquear")
                        .header("Authorization", "Bearer " + tokenAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bloqueada", equalTo(true)));

        // El token previo queda rechazado con 403 mientras la cuenta está bloqueada
        mockMvc.perform(get("/api/auth/validar")
                        .header("Authorization", "Bearer " + tokenAlumno))
                .andExpect(status().isForbidden());

        // No puede volver a iniciar sesión mientras esté bloqueada
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"usuario": "alumno2", "contrasena": "distrinubs"}
                                """))
                .andExpect(status().isForbidden());

        // Desbloquear permite volver a entrar, pero el token anterior sigue revocado
        mockMvc.perform(post("/api/identidad/cuentas/2/desbloquear")
                        .header("Authorization", "Bearer " + tokenAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bloqueada", equalTo(false)));

        mockMvc.perform(get("/api/auth/validar")
                        .header("Authorization", "Bearer " + tokenAlumno))
                .andExpect(status().isUnauthorized());

        String nuevoToken = iniciarSesion("alumno2", "distrinubs");
        mockMvc.perform(get("/api/auth/validar")
                        .header("Authorization", "Bearer " + nuevoToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Un alumno no puede acceder a endpoints administrativos y recibe 403 Forbidden")
    void alumnoRecibeForbiddenEnRutasAdmin() throws Exception {
        String tokenAlumno = iniciarSesion("alumno1", "distrinubs");

        mockMvc.perform(get("/api/identidad/cuentas")
                        .header("Authorization", "Bearer " + tokenAlumno))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/identidad/cuentas/2/bloquear")
                        .header("Authorization", "Bearer " + tokenAlumno))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Admin crea cuenta, cambia su rol y la nueva cuenta puede autenticarse")
    void adminCreaCuentaYCambiaRol() throws Exception {
        String tokenAdmin = iniciarSesion("admin", "claveAdminSegura!");

        MvcResult creada = mockMvc.perform(post("/api/identidad/cuentas")
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "usuario": "nuevo_alumno",
                                  "nombre": "Nuevo Alumno",
                                  "contrasena": "claveSegura123",
                                  "rol": "ALUMNO"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.usuario", equalTo("nuevo_alumno")))
                .andExpect(jsonPath("$.rol", equalTo("ALUMNO")))
                .andReturn();

        long idNuevo = mapper.readTree(creada.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(patch("/api/identidad/cuentas/" + idNuevo + "/rol")
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rol": "ORGANIZADOR"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rol", equalTo("ORGANIZADOR")));

        String tokenNuevo = iniciarSesion("nuevo_alumno", "claveSegura123");
        mockMvc.perform(get("/api/auth/yo")
                        .header("Authorization", "Bearer " + tokenNuevo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rol", equalTo("ORGANIZADOR")));
    }

    @Test
    @DisplayName("GET /api/cuentas-demo lista solo alumnos activos")
    void listaCuentasDemoSoloAlumnosActivos() throws Exception {
        String tokenAdmin = iniciarSesion("admin", "claveAdminSegura!");
        mockMvc.perform(post("/api/identidad/cuentas/3/bloquear")
                        .header("Authorization", "Bearer " + tokenAdmin))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/cuentas-demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].usuario", containsString("alumno")));
    }

    @Test
    @DisplayName("El payload del token usa sub como id de cuenta en texto y expone el claim usuario")
    void payloadTokenConSubCuentaIdYUsuario() throws Exception {
        String token = iniciarSesion("alumno1", "distrinubs");
        String[] partes = token.split("\\.");
        JsonNode payload = mapper.readTree(Base64.getUrlDecoder().decode(partes[1]));

        assertEquals("1", payload.get("sub").asText());
        assertEquals("alumno1", payload.get("usuario").asText());
        org.junit.jupiter.api.Assertions.assertFalse(payload.has("cuentaId"));
    }

    @Test
    @DisplayName("Reiniciar la siembra conserva los cambios de rol hechos por el administrador")
    void siembraConservaCambiosDeRol() throws Exception {
        String tokenAdmin = iniciarSesion("admin", "claveAdminSegura!");

        mockMvc.perform(patch("/api/identidad/cuentas/2/rol")
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rol": "ORGANIZADOR"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rol", equalTo("ORGANIZADOR")));

        siembra.afterPropertiesSet();

        String rolActual = jdbc.queryForObject("SELECT rol FROM cuentas WHERE id = 2", String.class);
        assertEquals("ORGANIZADOR", rolActual);
    }

    @Test
    @DisplayName("Los usuarios se normalizan a minúsculas y no se duplican por mayúsculas")
    void usuariosUnicosSinDistinguirMayusculas() throws Exception {
        String tokenAdmin = iniciarSesion("admin", "claveAdminSegura!");

        mockMvc.perform(post("/api/identidad/cuentas")
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "usuario": "NuevoUsuario",
                                  "nombre": "Usuario Prueba",
                                  "contrasena": "claveSegura123",
                                  "rol": "ALUMNO"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.usuario", equalTo("nuevousuario")));

        String token = iniciarSesion("NUEVOUSUARIO", "claveSegura123");
        mockMvc.perform(get("/api/auth/yo")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario", equalTo("nuevousuario")));

        mockMvc.perform(post("/api/identidad/cuentas")
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "usuario": "nuevousuario",
                                  "nombre": "Otro Usuario",
                                  "contrasena": "claveSegura123",
                                  "rol": "ALUMNO"
                                }
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Admin y organizador rechazan la contraseña de demo y usan SIEMBRA_CONTRASENA_ADMIN")
    void adminYOrganizadorUsanContrasenaPropia() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"usuario": "admin", "contrasena": "distrinubs"}
                                """))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"usuario": "organizador1", "contrasena": "distrinubs"}
                                """))
                .andExpect(status().isUnauthorized());

        iniciarSesion("admin", "claveAdminSegura!");
        iniciarSesion("organizador1", "claveAdminSegura!");

        assertThrows(IllegalArgumentException.class,
                () -> new SiembraCuentasIdentidad(jdbc, 0, "misma", "misma"));
    }

    @Test
    @DisplayName("Cambiar las variables de siembra rota las contraseñas al reiniciar sin sobrescribir roles")
    void siembraRotaContrasenasSinSobrescribirRoles() throws Exception {
        String tokenAdmin = iniciarSesion("admin", "claveAdminSegura!");

        mockMvc.perform(patch("/api/identidad/cuentas/2/rol")
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rol": "ORGANIZADOR"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rol", equalTo("ORGANIZADOR")));

        new SiembraCuentasIdentidad(jdbc, 0, "nuevaClaveDemo", "nuevaClaveAdmin").afterPropertiesSet();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"usuario": "alumno1", "contrasena": "distrinubs"}
                                """))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"usuario": "admin", "contrasena": "claveAdminSegura!"}
                                """))
                .andExpect(status().isUnauthorized());

        iniciarSesion("alumno1", "nuevaClaveDemo");
        iniciarSesion("admin", "nuevaClaveAdmin");

        String rolActual = jdbc.queryForObject("SELECT rol FROM cuentas WHERE id = 2", String.class);
        assertEquals("ORGANIZADOR", rolActual);
    }

    @Test
    @DisplayName("Crear una cuenta con usuario o nombre demasiado largo responde 400, no 409")
    void crearCuentaConDatosDemasiadoLargosResponde400() throws Exception {
        String tokenAdmin = iniciarSesion("admin", "claveAdminSegura!");

        // La petición se rechaza al validarla, antes de llegar a la base.
        mockMvc.perform(post("/api/identidad/cuentas")
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"usuario": "%s", "nombre": "Nombre", "contrasena": "claveSegura123"}
                                """.formatted("u".repeat(51))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/identidad/cuentas")
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"usuario": "usuario_corto", "nombre": "%s", "contrasena": "claveSegura123"}
                                """.formatted("n".repeat(121))))
                .andExpect(status().isBadRequest());

        // Si la validación no la detiene, la base la rechaza y tampoco es un 409.
        ResponseStatusException porLongitud = assertThrows(
                ResponseStatusException.class,
                () -> identidadService.crearCuenta(
                        new CrearCuentaRequest("u".repeat(51), "Nombre", "claveSegura123", "ALUMNO")));
        assertEquals(HttpStatus.BAD_REQUEST, porLongitud.getStatusCode());

        // El usuario repetido sigue siendo el único caso de 409.
        ResponseStatusException porRepetido = assertThrows(
                ResponseStatusException.class,
                () -> identidadService.crearCuenta(
                        new CrearCuentaRequest("Alumno1", "Otro", "claveSegura123", "ALUMNO")));
        assertEquals(HttpStatus.CONFLICT, porRepetido.getStatusCode());

        Integer totalCuentas = jdbc.queryForObject("SELECT COUNT(*) FROM cuentas", Integer.class);
        assertEquals(7, totalCuentas);
    }
}
