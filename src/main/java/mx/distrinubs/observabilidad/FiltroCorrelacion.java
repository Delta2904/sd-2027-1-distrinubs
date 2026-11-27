package mx.distrinubs.observabilidad;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Filtro que captura o genera un identificador de correlación para cada petición HTTP.
 *
 * <p>El identificador se extrae del encabezado {@code X-Correlation-Id} (o {@code X-Request-Id}).
 * Si no está presente, genera un UUID aleatorio.
 *
 * <p>Se almacena en el MDC de SLF4J bajo las claves {@code idCorrelacion} y {@code correlationId}
 * para que todas las líneas de log asociadas a la petición lo incluyan en su formato,
 * y se agrega como encabezado a la respuesta HTTP para que el cliente / front pueda
 * asociar cualquier error al log del servidor.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FiltroCorrelacion extends OncePerRequestFilter {

    public static final String ENCABEZADO_CORRELACION = "X-Correlation-Id";
    public static final String ENCABEZADO_PETICION = "X-Request-Id";
    public static final String CLAVE_MDC = "idCorrelacion";
    public static final String CLAVE_MDC_ALIAS = "correlationId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String idCorrelacion = request.getHeader(ENCABEZADO_CORRELACION);
        if (idCorrelacion == null || idCorrelacion.isBlank()) {
            idCorrelacion = request.getHeader(ENCABEZADO_PETICION);
        }
        if (idCorrelacion == null || idCorrelacion.isBlank()) {
            idCorrelacion = UUID.randomUUID().toString();
        } else {
            // Limpiar primero caracteres inválidos y luego recortar sobre el texto limpio
            String limpio = idCorrelacion.replaceAll("[^a-zA-Z0-9_-]", "");
            if (limpio.isBlank()) {
                idCorrelacion = UUID.randomUUID().toString();
            } else {
                idCorrelacion = limpio.substring(0, Math.min(limpio.length(), 64));
            }
        }

        MDC.put(CLAVE_MDC, idCorrelacion);
        MDC.put(CLAVE_MDC_ALIAS, idCorrelacion);
        response.setHeader(ENCABEZADO_CORRELACION, idCorrelacion);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(CLAVE_MDC);
            MDC.remove(CLAVE_MDC_ALIAS);
        }
    }
}
