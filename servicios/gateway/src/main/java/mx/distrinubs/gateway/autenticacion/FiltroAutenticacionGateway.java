package mx.distrinubs.gateway.autenticacion;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Filtro global de Spring Cloud Gateway que exige un token JWT válido y no revocado
 * en rutas protegidas, aplicando falla cerrada si el servicio {@code identidad} cae,
 * limpiando cualquier cabecera {@code X-Usuario-*} falsificada por el cliente y
 * propagando la identidad verificada hacia los servicios internos.
 */
@Component
public class FiltroAutenticacionGateway implements GlobalFilter, Ordered {

    public static final String HEADER_USUARIO_ID = "X-Usuario-Id";
    public static final String HEADER_USUARIO_LOGIN = "X-Usuario-Login";
    public static final String HEADER_USUARIO_ROL = "X-Usuario-Rol";

    /**
     * Lista exacta de rutas que se atienden sin token. No hay prefijos: una ruta que
     * no esté aquí exige token aunque empiece igual que una pública.
     */
    private static final Set<String> RUTAS_GET_PUBLICAS = Set.of(
            "/",
            "/index.html",
            "/app.js",
            "/estilos.css",
            "/favicon.ico",
            "/api/cuentas-demo",
            "/api/concursos/activo",
            "/api/concursos/activo/tabla",
            "/api/observabilidad/estado",
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness",
            "/actuator/info"
    );

    private record ResultadoRemoto(SesionGateway sesion, Throwable error) {}

    private final VerificadorJwtGateway verificadorJwt;
    private final VerificadorRemotoIdentidad verificadorRemoto;

    public FiltroAutenticacionGateway(
            VerificadorJwtGateway verificadorJwt,
            VerificadorRemotoIdentidad verificadorRemoto) {
        this.verificadorJwt = verificadorJwt;
        this.verificadorRemoto = verificadorRemoto;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        HttpMethod metodo = request.getMethod();
        String ruta = rutaNormalizada(request.getURI());
        if (ruta == null) {
            return escribirError(exchange, HttpStatus.BAD_REQUEST, "Ruta inválida");
        }

        if (esRutaPublica(metodo, ruta)) {
            ServerHttpRequest limpia = limpiarCabecerasIdentidad(request).build();
            return chain.filter(exchange.mutate().request(limpia).build());
        }

        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || authHeader.isBlank()) {
            return escribirError(exchange, HttpStatus.UNAUTHORIZED, "Token de autenticación requerido");
        }

        SesionGateway sesionLocal = verificadorJwt.verificar(authHeader);
        if (sesionLocal == null) {
            return escribirError(exchange, HttpStatus.UNAUTHORIZED, "Token inválido o expirado");
        }

        String headerNormalizado = authHeader.trim().regionMatches(true, 0, "Bearer ", 0, 7)
                ? authHeader.trim()
                : "Bearer " + authHeader.trim();

        return verificadorRemoto.validarRemoto(headerNormalizado)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "Servicio de identidad no disponible")))
                .map(sesion -> new ResultadoRemoto(sesion, null))
                .onErrorResume(error -> Mono.just(new ResultadoRemoto(null, error)))
                .flatMap(resultado -> {
                    if (resultado.error() != null) {
                        if (resultado.error() instanceof ResponseStatusException rse) {
                            return escribirError(exchange, rse.getStatusCode(),
                                    rse.getReason() != null ? rse.getReason() : "Acceso rechazado");
                        }
                        return escribirError(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                                "Servicio de identidad no disponible");
                    }
                    SesionGateway sesion = resultado.sesion();
                    ServerHttpRequest requestAutenticado = limpiarCabecerasIdentidad(request)
                            .header(HEADER_USUARIO_ID, String.valueOf(sesion.cuentaId()))
                            .header(HEADER_USUARIO_LOGIN, sesion.usuario())
                            .header(HEADER_USUARIO_ROL, sesion.rol())
                            .build();
                    return chain.filter(exchange.mutate().request(requestAutenticado).build());
                });
    }

    @Override
    public int getOrder() {
        return -100;
    }

    private boolean esRutaPublica(HttpMethod metodo, String ruta) {
        if (metodo == HttpMethod.OPTIONS) {
            return true;
        }
        if (metodo == HttpMethod.POST && "/api/auth/login".equals(ruta)) {
            return true;
        }
        return metodo == HttpMethod.GET && RUTAS_GET_PUBLICAS.contains(ruta);
    }

    /**
     * Devuelve la ruta ya decodificada, o {@code null} si no está en su forma normal:
     * con segmentos {@code .} o {@code ..}, o con {@code //}. Así la ruta que se compara
     * con la lista de públicas es la misma que recibe el servicio de destino.
     */
    private static String rutaNormalizada(URI uri) {
        String ruta = uri.getPath();
        if (ruta == null || ruta.contains("//")) {
            return null;
        }
        try {
            String normalizada = new URI(null, null, ruta, null).normalize().getPath();
            return ruta.equals(normalizada) ? ruta : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private ServerHttpRequest.Builder limpiarCabecerasIdentidad(ServerHttpRequest request) {
        return request.mutate().headers(headers -> {
            headers.remove(HEADER_USUARIO_ID);
            headers.remove(HEADER_USUARIO_LOGIN);
            headers.remove(HEADER_USUARIO_ROL);
            List<String> cabecerasUsuario = headers.keySet().stream()
                    .filter(k -> k.regionMatches(true, 0, "X-Usuario-", 0, 10))
                    .toList();
            cabecerasUsuario.forEach(headers::remove);
        });
    }

    private Mono<Void> escribirError(ServerWebExchange exchange, HttpStatusCode status, String mensaje) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String cuerpo = "{\"error\":\"" + mensaje.replace("\"", "'") + "\"}";
        DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(cuerpo.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
