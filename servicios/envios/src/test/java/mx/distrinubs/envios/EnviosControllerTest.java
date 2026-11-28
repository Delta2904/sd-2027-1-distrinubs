package mx.distrinubs.envios;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class EnviosControllerTest {

    private static final String SECRETO = "secreto-prueba-envios-distrinubs";

    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private PublicadorEnvios publicadorEnvios;
    private ClienteIdentidad clienteIdentidad;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        publicadorEnvios = mock(PublicadorEnvios.class);
        clienteIdentidad = mock(ClienteIdentidad.class);

        VerificadorTokenLocal verificadorLocal = new VerificadorTokenLocal(mapper, SECRETO);
        ValidadorTokenEnvios validador = new ValidadorTokenEnvios(verificadorLocal, clienteIdentidad);
        EnviosController controller = new EnviosController(jdbc, publicadorEnvios, validador, "PYTHON3");
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private String firmarJwt(long cuentaId, String usuario, String rol, long expSegundos) throws Exception {
        ObjectNode header = mapper.createObjectNode();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        ObjectNode payload = mapper.createObjectNode();
        payload.put("sub", String.valueOf(cuentaId));
        payload.put("usuario", usuario);
        payload.put("nombre", usuario);
        payload.put("rol", rol);
        payload.put("jti", UUID.randomUUID().toString());
        payload.put("iat", Instant.now().getEpochSecond());
        payload.put("exp", expSegundos);

        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String datos = enc.encodeToString(mapper.writeValueAsBytes(header))
                + "." + enc.encodeToString(mapper.writeValueAsBytes(payload));

        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(SECRETO.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String firma = enc.encodeToString(hmac.doFinal(datos.getBytes(StandardCharsets.UTF_8)));
        return datos + "." + firma;
    }

    @Test
    @DisplayName("POST /api/envios sin token: responde 401 Unauthorized")
    void crearEnvio_SinToken() throws Exception {
        String json = """
                {
                  "problemaId": 1,
                  "intentoId": "%s",
                  "lenguaje": "PYTHON3",
                  "codigo": "print(5)"
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/envios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isUnauthorized());

        verify(publicadorEnvios, never()).publicar(anyLong());
    }

    @Test
    @DisplayName("POST /api/envios con token inválido: responde 401 sin llamar a identidad")
    void crearEnvio_TokenInvalido() throws Exception {
        String json = """
                {
                  "problemaId": 1,
                  "intentoId": "%s",
                  "lenguaje": "PYTHON3",
                  "codigo": "print(5)"
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/envios")
                        .header("Authorization", "Bearer token.falso.invalido")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isUnauthorized());

        verify(clienteIdentidad, never()).validar(anyString());
    }

    @Test
    @DisplayName("POST /api/envios con identidad caída (falla cerrada): responde 503 Service Unavailable")
    void crearEnvio_IdentidadCaidaFallaCerrada() throws Exception {
        String token = firmarJwt(1L, "alumno1", "ALUMNO", Instant.now().getEpochSecond() + 3600);
        when(clienteIdentidad.validar("Bearer " + token))
                .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Identidad caída"));

        String json = """
                {
                  "problemaId": 1,
                  "intentoId": "%s",
                  "lenguaje": "PYTHON3",
                  "codigo": "print(5)"
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/envios")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isServiceUnavailable());

        verify(publicadorEnvios, never()).publicar(anyLong());
    }

    @Test
    @DisplayName("POST /api/envios con alumnoId distinto al token: responde 403 Forbidden (anti-suplantación)")
    void crearEnvio_SuplantacionAlumnoId() throws Exception {
        String token = firmarJwt(1L, "alumno1", "ALUMNO", Instant.now().getEpochSecond() + 3600);
        when(clienteIdentidad.validar("Bearer " + token))
                .thenReturn(new SesionAutenticada(1L, "alumno1", "Alumno Uno", "ALUMNO", 9999999999L, "jti-1"));

        String json = """
                {
                  "alumnoId": 2,
                  "problemaId": 1,
                  "intentoId": "%s",
                  "lenguaje": "PYTHON3",
                  "codigo": "print(5)"
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/envios")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isForbidden());

        verify(publicadorEnvios, never()).publicar(anyLong());
    }

    @Test
    @DisplayName("POST /api/envios exitoso: guarda con la cuenta del token, publica en RabbitMQ y responde 201")
    void crearEnvio_ExitosoYPublica() throws Exception {
        UUID intentoId = UUID.randomUUID();
        String token = firmarJwt(1L, "alumno1", "ALUMNO", Instant.now().getEpochSecond() + 3600);
        when(clienteIdentidad.validar("Bearer " + token))
                .thenReturn(new SesionAutenticada(1L, "alumno1", "Alumno Uno", "ALUMNO", 9999999999L, "jti-1"));

        when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM cuentas WHERE id = ?"), eq(Integer.class), eq(1L)))
                .thenReturn(1);
        when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM problemas WHERE id = ?"), eq(Integer.class), eq(2L)))
                .thenReturn(1);
        when(jdbc.queryForObject(
                anyString(),
                eq(Long.class),
                eq(intentoId),
                eq(1L),
                eq(2L),
                eq("PYTHON3"),
                eq("print('hola')")))
                .thenReturn(100L);

        when(publicadorEnvios.publicar(100L)).thenReturn(true);

        String json = """
                {
                  "problemaId": 2,
                  "intentoId": "%s",
                  "lenguaje": "PYTHON3",
                  "codigo": "print('hola')"
                }
                """.formatted(intentoId);

        mockMvc.perform(post("/api/envios")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.envioId", equalTo(100)))
                .andExpect(jsonPath("$.estado", equalTo("recibido")));

        verify(publicadorEnvios).publicar(100L);
        verify(jdbc).update(
                "UPDATE envios SET publicado_en = now(), veces_publicado = veces_publicado + 1 WHERE id = ?",
                100L);
    }

    @Test
    @DisplayName("POST /api/envios reintento idempotente: responde 200 con envío existente y no vuelve a publicar")
    void crearEnvio_ReintentoIdempotente() throws Exception {
        UUID intentoId = UUID.randomUUID();
        String token = firmarJwt(1L, "alumno1", "ALUMNO", Instant.now().getEpochSecond() + 3600);
        when(clienteIdentidad.validar("Bearer " + token))
                .thenReturn(new SesionAutenticada(1L, "alumno1", "Alumno Uno", "ALUMNO", 9999999999L, "jti-1"));

        when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM cuentas WHERE id = ?"), eq(Integer.class), eq(1L)))
                .thenReturn(1);
        when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM problemas WHERE id = ?"), eq(Integer.class), eq(2L)))
                .thenReturn(1);
        when(jdbc.queryForObject(
                anyString(),
                eq(Long.class),
                eq(intentoId),
                eq(1L),
                eq(2L),
                eq("PYTHON3"),
                eq("print('hola')")))
                .thenThrow(new DuplicateKeyException("unique violation", new SQLException("duplicate key", "23505")));
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
                  "codigo": "print('hola')"
                }
                """.formatted(intentoId);

        mockMvc.perform(post("/api/envios")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.envioId", equalTo(100)))
                .andExpect(jsonPath("$.estado", equalTo("recibido")));

        verify(publicadorEnvios, never()).publicar(anyLong());
    }

    @Test
    @DisplayName("GET /api/envios/{id} de otro alumno: responde 403 para ALUMNO y 200 para ORGANIZADOR")
    void obtenerEstado_ControlAccesoPorCuentaYRol() throws Exception {
        String tokenAlumno = firmarJwt(1L, "alumno1", "ALUMNO", Instant.now().getEpochSecond() + 3600);
        when(clienteIdentidad.validar("Bearer " + tokenAlumno))
                .thenReturn(new SesionAutenticada(1L, "alumno1", "Alumno Uno", "ALUMNO", 9999999999L, "jti-1"));

        String tokenOrganizador = firmarJwt(10L, "organizador1", "ORGANIZADOR", Instant.now().getEpochSecond() + 3600);
        when(clienteIdentidad.validar("Bearer " + tokenOrganizador))
                .thenReturn(new SesionAutenticada(10L, "organizador1", "Org", "ORGANIZADOR", 9999999999L, "jti-2"));

        when(jdbc.query(eq("SELECT cuenta_id, estado FROM envios WHERE id = ?"), any(RowMapper.class), eq(100L)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> rm = inv.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getLong("cuenta_id")).thenReturn(2L);
                    when(rs.getString("estado")).thenReturn("EN_COLA");
                    return List.of(rm.mapRow(rs, 0));
                });

        mockMvc.perform(get("/api/envios/100")
                        .header("Authorization", "Bearer " + tokenAlumno))
                .andExpect(status().isForbidden());

        ResultadoEnvioDto resultado = new ResultadoEnvioDto("ACCEPTED", 15, null, null);
        when(jdbc.query(
                eq("SELECT veredicto, tiempo_ms, memoria_kb, detalle FROM resultados WHERE envio_id = ?"),
                any(RowMapper.class),
                eq(100L)))
                .thenReturn(List.of(resultado));

        mockMvc.perform(get("/api/envios/100")
                        .header("Authorization", "Bearer " + tokenOrganizador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado", equalTo("evaluado")))
                .andExpect(jsonPath("$.veredicto", equalTo("ACCEPTED")));
    }

    @Test
    @DisplayName("GET /api/envios/{id} del dueño: deriva 'en cola', 'evaluando' y 'evaluado'")
    void obtenerEstado_CicloDeVidaPropio() throws Exception {
        String token = firmarJwt(1L, "alumno1", "ALUMNO", Instant.now().getEpochSecond() + 3600);
        when(clienteIdentidad.validar("Bearer " + token))
                .thenReturn(new SesionAutenticada(1L, "alumno1", "Alumno Uno", "ALUMNO", 9999999999L, "jti-1"));

        when(jdbc.query(eq("SELECT cuenta_id, estado FROM envios WHERE id = ?"), any(RowMapper.class), eq(100L)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> rm = inv.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getLong("cuenta_id")).thenReturn(1L);
                    when(rs.getString("estado")).thenReturn("EN_COLA");
                    return List.of(rm.mapRow(rs, 0));
                });

        when(jdbc.query(
                eq("SELECT veredicto, tiempo_ms, memoria_kb, detalle FROM resultados WHERE envio_id = ?"),
                any(RowMapper.class),
                eq(100L)))
                .thenReturn(Collections.emptyList());

        when(jdbc.queryForObject(
                eq("SELECT COUNT(*) FROM leases_evaluacion WHERE envio_id = ? AND vence_en > now()"),
                eq(Integer.class),
                eq(100L)))
                .thenReturn(0)
                .thenReturn(1);

        mockMvc.perform(get("/api/envios/100")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado", equalTo("en cola")));

        mockMvc.perform(get("/api/envios/100")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado", equalTo("evaluando")));
    }

    @Test
    @DisplayName("POST /api/envios con violación CHECK (23514) responde 400 sin tratarla como duplicado")
    void crearEnvio_CheckViolationResponde400SinBuscarDuplicado() throws Exception {
        UUID intentoId = UUID.randomUUID();
        String token = firmarJwt(1L, "alumno1", "ALUMNO", Instant.now().getEpochSecond() + 3600);
        when(clienteIdentidad.validar("Bearer " + token))
                .thenReturn(new SesionAutenticada(1L, "alumno1", "Alumno Uno", "ALUMNO", 9999999999L, "jti-1"));

        when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM cuentas WHERE id = ?"), eq(Integer.class), eq(1L)))
                .thenReturn(1);
        when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM problemas WHERE id = ?"), eq(Integer.class), eq(2L)))
                .thenReturn(1);
        when(jdbc.queryForObject(
                anyString(),
                eq(Long.class),
                eq(intentoId),
                eq(1L),
                eq(2L),
                eq("PYTHON3"),
                eq("print('hola')")))
                .thenThrow(new DataIntegrityViolationException(
                        "check violation",
                        new SQLException("check constraint", "23514")));

        String json = """
                {
                  "problemaId": 2,
                  "intentoId": "%s",
                  "lenguaje": "PYTHON3",
                  "codigo": "print('hola')"
                }
                """.formatted(intentoId);

        mockMvc.perform(post("/api/envios")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());

        verify(jdbc, never()).query(
                eq("SELECT id FROM envios WHERE cuenta_id = ? AND id_intento = ?"),
                any(RowMapper.class),
                anyLong(),
                any(UUID.class));
        verify(publicadorEnvios, never()).publicar(anyLong());
    }

    @Test
    @DisplayName("POST /api/envios con alumnoId ajeno responde 403 incluso para ORGANIZADOR")
    void crearEnvio_OrganizadorConAlumnoIdAjenoResponde403() throws Exception {
        String tokenOrg = firmarJwt(10L, "organizador1", "ORGANIZADOR", Instant.now().getEpochSecond() + 3600);
        when(clienteIdentidad.validar("Bearer " + tokenOrg))
                .thenReturn(new SesionAutenticada(10L, "organizador1", "Org", "ORGANIZADOR", 9999999999L, "jti-2"));

        String json = """
                {
                  "alumnoId": 2,
                  "problemaId": 1,
                  "intentoId": "%s",
                  "lenguaje": "PYTHON3",
                  "codigo": "print(5)"
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/envios")
                        .header("Authorization", "Bearer " + tokenOrg)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isForbidden());

        verify(publicadorEnvios, never()).publicar(anyLong());
    }

    @Test
    @DisplayName("Un rol desconocido recibe 403 Forbidden en POST y GET /api/envios")
    void rolDesconocidoRecibe403() throws Exception {
        String tokenInvitado = firmarJwt(99L, "invitado", "INVITADO", Instant.now().getEpochSecond() + 3600);
        when(clienteIdentidad.validar("Bearer " + tokenInvitado))
                .thenReturn(new SesionAutenticada(99L, "invitado", "Invitado", "INVITADO", 9999999999L, "jti-99"));

        String json = """
                {
                  "problemaId": 1,
                  "intentoId": "%s",
                  "lenguaje": "PYTHON3",
                  "codigo": "print(5)"
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/envios")
                        .header("Authorization", "Bearer " + tokenInvitado)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/envios/100")
                        .header("Authorization", "Bearer " + tokenInvitado))
                .andExpect(status().isForbidden());

        verify(publicadorEnvios, never()).publicar(anyLong());
    }
}
