package mx.distrinubs.gateway.correlacion;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Propaga el id de correlación de cada petición hacia los servicios y lo escribe en el log del gateway.
 *
 * <p>Toma el id del encabezado {@code X-Correlation-Id}, que agrega la entrada; si
 * no llega, genera uno. Lo manda al servicio destino en ese mismo encabezado, lo
 * devuelve en la respuesta y escribe una línea por petición con el id, el método,
 * la ruta, el estado y el tiempo. Corre antes que el filtro de autenticación, así
 * que también quedan registradas las peticiones que se rechazan por el token.
 */
@Component
public class FiltroCorrelacionGateway implements GlobalFilter, Ordered {

    public static final String ENCABEZADO = "X-Correlation-Id";

    private static final Logger log = LoggerFactory.getLogger(FiltroCorrelacionGateway.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String id = limpiar(exchange.getRequest().getHeaders().getFirst(ENCABEZADO));
        ServerHttpRequest conId = exchange.getRequest().mutate()
                .headers(cabeceras -> cabeceras.set(ENCABEZADO, id))
                .build();
        ServerWebExchange conIdExchange = exchange.mutate().request(conId).build();
        // Se pone justo antes de responder y reemplaza al que traiga la respuesta
        // del servicio: así el cliente recibe el encabezado una sola vez.
        conIdExchange.getResponse().beforeCommit(() -> {
            conIdExchange.getResponse().getHeaders().set(ENCABEZADO, id);
            return Mono.empty();
        });
        long inicio = System.nanoTime();
        String metodo = conId.getMethod().name();
        String ruta = conId.getURI().getRawPath();
        return chain.filter(conIdExchange).doFinally(senal -> {
            HttpStatusCode estado = conIdExchange.getResponse().getStatusCode();
            log.info("[{}] {} {} -> {} ({} ms)", id, metodo, ruta,
                    estado != null ? estado.value() : "sin estado", (System.nanoTime() - inicio) / 1_000_000);
        });
    }

    /** Antes que el filtro de autenticación. */
    @Override
    public int getOrder() {
        return -200;
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
