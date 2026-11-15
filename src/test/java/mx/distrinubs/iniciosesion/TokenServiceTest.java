package mx.distrinubs.iniciosesion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TokenServiceTest {

    private final TokenService tokenService = new TokenService();

    @Test
    @DisplayName("Genera un token firmado con sub, rol y exp según el contrato")
    void generarYValidarTokenExitoso() {
        String token = tokenService.generarToken(1L, "alumno1", "Alumno Uno", "ALUMNO");
        assertNotNull(token);
        assertTrue(token.contains("."));

        SesionUsuario sesion = tokenService.validarToken(token);
        assertNotNull(sesion);
        assertEquals(1L, sesion.cuentaId());
        assertEquals("alumno1", sesion.usuario());
        assertEquals("Alumno Uno", sesion.nombre());
        assertEquals("ALUMNO", sesion.rol());
        assertTrue(sesion.exp() > System.currentTimeMillis() / 1000);
    }

    @Test
    @DisplayName("Acepta el token con el prefijo 'Bearer '")
    void validarTokenConPrefijoBearer() {
        String token = tokenService.generarToken(2L, "alumno2", "Alumno Dos", "ALUMNO");
        SesionUsuario sesion = tokenService.validarToken("Bearer " + token);
        assertNotNull(sesion);
        assertEquals("alumno2", sesion.usuario());
    }

    @Test
    @DisplayName("Rechaza un token alterado o con firma falsa")
    void rechazaTokenAlterado() {
        String token = tokenService.generarToken(1L, "alumno1", "Alumno Uno", "ALUMNO");
        String[] partes = token.split("\\.");
        // Alterar el payload
        String alterado = partes[0] + ".e30." + partes[2];
        assertNull(tokenService.validarToken(alterado));
    }

    @Test
    @DisplayName("Rechaza tokens nulos, vacíos o mal formados")
    void rechazaTokensMalformados() {
        assertNull(tokenService.validarToken(null));
        assertNull(tokenService.validarToken(""));
        assertNull(tokenService.validarToken("invalido.sin.firma.correcta"));
    }
}
