package mx.distrinubs.identidad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class TokenJwtServiceTest {

    private static final String SECRETO = "secreto-de-prueba-para-token-jwt-distrinubs";

    @Test
    @DisplayName("Genera y valida un token HS256 con claims sub (id), usuario, rol, jti y exp")
    void generaYValidaTokenCorrectamente() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TokenJwtService servicio = new TokenJwtService(mapper, SECRETO, 3600);
        Instant ahora = Instant.now();

        String token = servicio.generarToken(1L, "alumno1", "Alumno Uno", "ALUMNO", ahora);
        String[] partes = token.split("\\.");
        JsonNode payload = mapper.readTree(Base64.getUrlDecoder().decode(partes[1]));

        assertEquals("1", payload.get("sub").asText());
        assertEquals("alumno1", payload.get("usuario").asText());
        assertFalse(payload.has("cuentaId"));

        TokenJwtService.DatosToken datos = servicio.validarFirmaYExpiracion("Bearer " + token);

        assertNotNull(datos);
        assertEquals(1L, datos.cuentaId());
        assertEquals("alumno1", datos.usuario());
        assertEquals("Alumno Uno", datos.nombre());
        assertEquals("ALUMNO", datos.rol());
        assertFalse(datos.jti().isBlank());
    }

    @Test
    @DisplayName("Rechaza un token cuya firma o carga útil fue alterada")
    void rechazaTokenAlterado() {
        TokenJwtService servicio = new TokenJwtService(new ObjectMapper(), SECRETO, 3600);
        String token = servicio.generarToken(1L, "alumno1", "Alumno Uno", "ALUMNO");

        String[] partes = token.split("\\.");
        String alterado = partes[0] + "." + partes[1] + ".firma_invalida";

        assertNull(servicio.validarFirmaYExpiracion(alterado));
        assertNull(servicio.validarFirmaYExpiracion("Bearer token.mal.formado"));
    }

    @Test
    @DisplayName("Rechaza un token expirado")
    void rechazaTokenExpirado() {
        TokenJwtService servicio = new TokenJwtService(new ObjectMapper(), SECRETO, 60);
        Instant pasado = Instant.now().minusSeconds(120);

        String tokenExpirado = servicio.generarToken(1L, "alumno1", "Alumno Uno", "ALUMNO", pasado);

        assertNull(servicio.validarFirmaYExpiracion(tokenExpirado));
    }

    @Test
    @DisplayName("Rechaza un token firmado con otro secreto")
    void rechazaTokenDeOtroSecreto() {
        TokenJwtService emisor = new TokenJwtService(new ObjectMapper(), SECRETO, 3600);
        TokenJwtService verificador = new TokenJwtService(new ObjectMapper(), "otro-secreto-distinto", 3600);

        String token = emisor.generarToken(1L, "alumno1", "Alumno Uno", "ALUMNO");

        assertNull(verificador.validarFirmaYExpiracion(token));
    }
}
