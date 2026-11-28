package mx.distrinubs.concursos;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Pone el id de correlación de cada petición en los logs del servicio.
 *
 * <p>Toma el id del encabezado {@code X-Correlation-Id}, que agrega la entrada y
 * propaga el gateway; si no llega, genera uno. Lo guarda en el MDC con la clave
 * {@code idCorrelacion}, que el patrón de log imprime en cada línea, lo devuelve
 * en la respuesta y escribe una línea por petición con el método, la ruta, el
 * estado y el tiempo. Las consultas de salud no se registran: llegan cada pocos
 * segundos y taparían lo demás.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FiltroCorrelacion extends OncePerRequestFilter {

    public static final String ENCABEZADO = "X-Correlation-Id";
    public static final String CLAVE_MDC = "idCorrelacion";

    private static final Logger log = LoggerFactory.getLogger(FiltroCorrelacion.class);

    @Override
    protected void doFilterInternal(HttpServletRequest peticion, HttpServletResponse respuesta, FilterChain cadena)
            throws ServletException, IOException {
        String id = limpiar(peticion.getHeader(ENCABEZADO));
        MDC.put(CLAVE_MDC, id);
        respuesta.setHeader(ENCABEZADO, id);
        long inicio = System.nanoTime();
        try {
            cadena.doFilter(peticion, respuesta);
        } finally {
            if (!peticion.getRequestURI().startsWith("/actuator")) {
                log.info("{} {} -> {} ({} ms)", peticion.getMethod(), peticion.getRequestURI(),
                        respuesta.getStatus(), (System.nanoTime() - inicio) / 1_000_000);
            }
            MDC.remove(CLAVE_MDC);
        }
    }

    /** Deja solo letras, dígitos, guiones y guiones bajos, hasta 64 caracteres; si no queda nada, genera un id. */
    static String limpiar(String recibido) {
        if (recibido == null) {
            return UUID.randomUUID().toString();
        }
        String limpio = recibido.replaceAll("[^a-zA-Z0-9_-]", "");
        if (limpio.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return limpio.substring(0, Math.min(limpio.length(), 64));
    }
}
