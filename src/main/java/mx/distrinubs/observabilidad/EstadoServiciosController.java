package mx.distrinubs.observabilidad;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlador de observabilidad que entrega la vista del estado de los servicios (Paso 7).
 *
 * <p>No consulta tablas de otros módulos: verifica la conectividad propia y, para los
 * servicios separados, consulta su endpoint {@code /actuator/health} si está configurado,
 * o reporta {@code NO_DISPONIBLE} mientras el servicio no exista o no esté configurado.
 */
@RestController
@RequestMapping("/api/observabilidad")
public class EstadoServiciosController {

    private static final Duration TIMEOUT_SONDEO = Duration.ofMillis(1500);

    private final DataSource dataSource;
    private final HttpClient httpClient;
    private final Map<String, String> urlsServicios;

    @Autowired
    public EstadoServiciosController(
            DataSource dataSource,
            @Value("${observabilidad.servicios.gateway:}") String gatewayUrl,
            @Value("${observabilidad.servicios.identidad:}") String identidadUrl,
            @Value("${observabilidad.servicios.envios:}") String enviosUrl,
            @Value("${observabilidad.servicios.evaluacion:}") String evaluacionUrl,
            @Value("${observabilidad.servicios.concursos:}") String concursosUrl,
            @Value("${observabilidad.servicios.marcador:}") String marcadorUrl,
            @Value("${observabilidad.servicios.directorio:}") String directorioUrl,
            @Value("${observabilidad.servicios.mensajeria:}") String mensajeriaUrl) {
        this(
                dataSource,
                HttpClient.newBuilder().connectTimeout(TIMEOUT_SONDEO).build(),
                Map.of(
                        "gateway", gatewayUrl,
                        "identidad", identidadUrl,
                        "envios", enviosUrl,
                        "evaluacion", evaluacionUrl,
                        "concursos", concursosUrl,
                        "marcador", marcadorUrl,
                        "directorio", directorioUrl,
                        "mensajeria", mensajeriaUrl));
    }

    EstadoServiciosController(DataSource dataSource, HttpClient httpClient, Map<String, String> urlsServicios) {
        this.dataSource = dataSource;
        this.httpClient = httpClient;
        this.urlsServicios = urlsServicios;
    }

    @GetMapping("/estado")
    public EstadoServiciosResponse obtenerEstado() {
        List<EstadoServiciosResponse.ServicioDetalle> servicios = new ArrayList<>();

        // 1. Base de datos local
        servicios.add(verificarBaseDatos());

        // 2. Servicios externos - consultas en paralelo
        List<CompletableFuture<EstadoServiciosResponse.ServicioDetalle>> futures = new ArrayList<>();
        String[] nombres = {"gateway","identidad","envios","evaluacion","concursos","marcador","directorio","mensajeria"};
        String[] tipos = {"infraestructura","servicio","servicio","servicio","servicio","servicio","infraestructura","infraestructura"};
        String[] rutas = {"/actuator/health","/actuator/health","/actuator/health","/actuator/health","/actuator/health","/actuator/health","/actuator/health",""};
        for (int i = 0; i < nombres.length; i++) {
            final String n = nombres[i];
            final String t = tipos[i];
            final String r = rutas[i];
            futures.add(CompletableFuture.supplyAsync(() -> consultarServicio(n, t, r)));
        }
        // Esperar resultados con un timeout global (7 s)
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(7, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Si se agota el tiempo, los futures que no terminaron reportarán excepción abajo
        }
        for (var f : futures) {
            try {
                servicios.add(f.get());
            } catch (Exception e) {
                // En caso de error inesperado, registrar como NO_DISPONIBLE
                servicios.add(new EstadoServiciosResponse.ServicioDetalle("unknown", "", "NO_DISPONIBLE", 0, 0, "Error al consultar servicio: " + e.getMessage()));
            }
        }

        int activos = 0;
        int caidos = 0;
        int noDisponibles = 0;
        for (var s : servicios) {
            switch (s.estado()) {
                case "UP" -> activos++;
                case "DOWN" -> caidos++;
                default -> noDisponibles++;
            }
        }

        String global = (caidos > 0 || noDisponibles > 0) ? "DEGRADADO" : (activos > 0 ? "UP" : "NO_DISPONIBLE");
        var resumen = new EstadoServiciosResponse.ResumenServicios(activos, caidos, noDisponibles);

        return new EstadoServiciosResponse(Instant.now(), global, servicios, resumen);
    }

    private EstadoServiciosResponse.ServicioDetalle verificarBaseDatos() {
        long inicio = System.currentTimeMillis();
        try (Connection conn = dataSource.getConnection()) {
            boolean valida = conn != null && conn.isValid(2);
            long latencia = System.currentTimeMillis() - inicio;
            return new EstadoServiciosResponse.ServicioDetalle(
                    "basedatos",
                    "datos",
                    valida ? "UP" : "DOWN",
                    1,
                    latencia,
                    valida ? "Conexión PostgreSQL activa" : "Conexión PostgreSQL inválida");
        } catch (Exception e) {
            long latencia = System.currentTimeMillis() - inicio;
            return new EstadoServiciosResponse.ServicioDetalle(
                    "basedatos",
                    "datos",
                    "DOWN",
                    0,
                    latencia,
                    "Sin conexión a PostgreSQL: " + e.getMessage());
        }
    }

    private EstadoServiciosResponse.ServicioDetalle consultarServicio(
            String nombre, String tipo, String rutaHealth) {
        String urlBase = urlsServicios.getOrDefault(nombre, "");
        if (urlBase == null || urlBase.isBlank()) {
            return new EstadoServiciosResponse.ServicioDetalle(
                    nombre,
                    tipo,
                    "NO_DISPONIBLE",
                    0,
                    0,
                    "Servicio separado no configurado o pendiente de despliegue");
        }

        String destino = urlBase.replaceAll("/+$", "") + rutaHealth;
        long inicio = System.currentTimeMillis();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(destino))
                    .timeout(TIMEOUT_SONDEO)
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            long latencia = System.currentTimeMillis() - inicio;
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return new EstadoServiciosResponse.ServicioDetalle(
                        nombre, tipo, "UP", 1, latencia, "Respondió HTTP " + resp.statusCode() + " en " + destino);
            }
            return new EstadoServiciosResponse.ServicioDetalle(
                    nombre, tipo, "DOWN", 0, latencia, "Respondió HTTP " + resp.statusCode() + " en " + destino);
        } catch (Exception e) {
            long latencia = System.currentTimeMillis() - inicio;
            return new EstadoServiciosResponse.ServicioDetalle(
                    nombre, tipo, "DOWN", 0, latencia, "No responde en " + destino + ": " + e.getMessage());
        }
    }
}
