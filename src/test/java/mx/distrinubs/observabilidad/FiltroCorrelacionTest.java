package mx.distrinubs.observabilidad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.ServletException;

class FiltroCorrelacionTest {

    private final FiltroCorrelacion filtro = new FiltroCorrelacion();

    @Test
    @DisplayName("Propaga el X-Correlation-Id recibido del cliente a la respuesta y al MDC durante la ejecución")
    void propagaCorrelationIdExistente() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String idEnviado = "mi-id-de-prueba-12345";
        request.addHeader(FiltroCorrelacion.ENCABEZADO_CORRELACION, idEnviado);

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                // Verificar que dentro del filtro el MDC tiene el valor
                assertEquals(idEnviado, MDC.get(FiltroCorrelacion.CLAVE_MDC));
                assertEquals(idEnviado, MDC.get(FiltroCorrelacion.CLAVE_MDC_ALIAS));
            }
        };

        filtro.doFilter(request, response, chain);

        assertEquals(idEnviado, response.getHeader(FiltroCorrelacion.ENCABEZADO_CORRELACION));
        // Al terminar, el MDC debe quedar limpio
        assertNull(MDC.get(FiltroCorrelacion.CLAVE_MDC));
    }

    @Test
    @DisplayName("Genera un UUID cuando la petición no trae encabezado de correlación")
    void generaNuevoCorrelationIdSiFalta() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                assertNotNull(MDC.get(FiltroCorrelacion.CLAVE_MDC));
            }
        };

        filtro.doFilter(request, response, chain);

        String idGenerado = response.getHeader(FiltroCorrelacion.ENCABEZADO_CORRELACION);
        assertNotNull(idGenerado);
        assertEquals(36, idGenerado.length()); // Formato UUID
        assertNull(MDC.get(FiltroCorrelacion.CLAVE_MDC));
    }

    @Test
    @DisplayName("Limpia caracteres inválidos como 'ab$$' antes de recortar sin lanzar excepción")
    void limpiaCaracteresInvalidosSinError() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(FiltroCorrelacion.ENCABEZADO_CORRELACION, "ab$$");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                assertEquals("ab", MDC.get(FiltroCorrelacion.CLAVE_MDC));
            }
        };

        filtro.doFilter(request, response, chain);

        assertEquals("ab", response.getHeader(FiltroCorrelacion.ENCABEZADO_CORRELACION));
        assertNull(MDC.get(FiltroCorrelacion.CLAVE_MDC));
    }

    @Test
    @DisplayName("Genera un UUID cuando el encabezado solo contiene caracteres inválidos")
    void generaUuidSiSoloHayCaracteresInvalidos() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(FiltroCorrelacion.ENCABEZADO_CORRELACION, "$$$!!!");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filtro.doFilter(request, response, chain);

        String idGenerado = response.getHeader(FiltroCorrelacion.ENCABEZADO_CORRELACION);
        assertNotNull(idGenerado);
        assertEquals(36, idGenerado.length());
        assertNull(MDC.get(FiltroCorrelacion.CLAVE_MDC));
    }
}
