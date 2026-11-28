package mx.distrinubs.concursos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;

class FiltroCorrelacionTest {

    private final FiltroCorrelacion filtro = new FiltroCorrelacion();

    @Test
    @DisplayName("El id que llega en X-Correlation-Id queda en el MDC durante la petición y vuelve en la respuesta")
    void respetaElIdQueLlega() throws Exception {
        MockHttpServletRequest peticion = new MockHttpServletRequest("GET", "/api/prueba");
        peticion.addHeader(FiltroCorrelacion.ENCABEZADO, "abc-123");
        MockHttpServletResponse respuesta = new MockHttpServletResponse();
        AtomicReference<String> enElMdc = new AtomicReference<>();
        FilterChain cadena = (req, res) -> enElMdc.set(MDC.get(FiltroCorrelacion.CLAVE_MDC));

        filtro.doFilter(peticion, respuesta, cadena);

        assertEquals("abc-123", enElMdc.get());
        assertEquals("abc-123", respuesta.getHeader(FiltroCorrelacion.ENCABEZADO));
        assertNull(MDC.get(FiltroCorrelacion.CLAVE_MDC), "El MDC se limpia al terminar la petición");
    }

    @Test
    @DisplayName("Sin id, o con uno que solo trae caracteres inválidos, genera uno nuevo; uno largo se recorta")
    void generaOLimpiaElId() throws Exception {
        MockHttpServletRequest sinId = new MockHttpServletRequest("GET", "/api/prueba");
        MockHttpServletResponse respuesta = new MockHttpServletResponse();
        filtro.doFilter(sinId, respuesta, (req, res) -> { });
        String generado = respuesta.getHeader(FiltroCorrelacion.ENCABEZADO);
        assertTrue(generado != null && generado.length() == 36, "Se esperaba un UUID");

        assertEquals(36, FiltroCorrelacion.limpiar("\n\r ").length());
        assertEquals("ab", FiltroCorrelacion.limpiar("a\nb"));
        assertEquals(64, FiltroCorrelacion.limpiar("x".repeat(200)).length());
    }
}
