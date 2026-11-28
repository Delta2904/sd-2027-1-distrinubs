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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.PreDestroy;

/**
 * Controlador de observabilidad que entrega la vista del estado de los servicios (Paso 7).
 *
 * <p>No consulta tablas de otros módulos: verifica la conectividad propia y, para los
 * servicios separados, consulta su endpoint {@code /actuator/health} si está configurado,
 * o reporta {@code NO_DISPONIBLE} mientras el servicio no exista o no esté configurado.
 *
 * <p>Todas las verificaciones, incluida la de la base de datos, corren en paralelo y
 * comparten un presupuesto de tiempo menor que el que el front espera por petición. La
 * que no termina a tiempo se reporta con su nombre y sin bloquear la respuesta.
 */
@RestController
@RequestMapping("/api/observabilidad")
public class EstadoServiciosController {

    private static final Duration TIMEOUT_SONDEO = Duration.ofMillis(1500);

    /** El front corta cada petición a los 8 s: la vista debe responder antes. */
    private static final Duration PRESUPUESTO = Duration.ofSeconds(6);

    /**
     * Hilos propios para los sondeos. Son pocos a propósito: alcanzan para una
     * consulta completa y dejan margen si alguna verificación sigue bloqueada
     * después de agotar su presupuesto.
     */
    private static final int HILOS_SONDEO = 12;

    /** Nombre, tipo y ruta de salud de cada servicio separado, en el orden en que se muestran. */
    private static final String[][] SERVICIOS = {
        {"gateway", "infraestructura", "/actuator/health"},
        {"identidad", "servicio", "/actuator/health"},
        {"envios", "servicio", "/actuator/health"},
        {"evaluacion", "servicio", "/actuator/health"},
        {"concursos", "servicio", "/actuator/health"},
        {"marcador", "servicio", "/actuator/health"},
        {"directorio", "infraestructura", "/actuator/health"},
        {"mensajeria", "infraestructura", ""},
    };

    private final DataSource dataSource;
    private final HttpClient httpClient;
    private final Map<String, String> urlsServicios;
    private final Duration presupuesto;
    private final ExecutorService ejecutor;

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
                        "mensajeria", mensajeriaUrl),
                PRESUPUESTO);
    }

    /** Para las pruebas: permite un presupuesto corto, para no esperar los 6 s reales. */
    EstadoServiciosController(
            DataSource dataSource, HttpClient httpClient, Map<String, String> urlsServicios, Duration presupuesto) {
        this.dataSource = dataSource;
        this.httpClient = httpClient;
        this.urlsServicios = urlsServicios;
        this.presupuesto = presupuesto;
        AtomicInteger numeroHilo = new AtomicInteger();
        this.ejecutor = Executors.newFixedThreadPool(HILOS_SONDEO, tarea -> {
            Thread hilo = new Thread(tarea, "sondeo-estado-" + numeroHilo.incrementAndGet());
            hilo.setDaemon(true);
            return hilo;
        });
    }

    @PreDestroy
    void cerrar() {
        ejecutor.shutdownNow();
    }

    @GetMapping("/estado")
    public EstadoServiciosResponse obtenerEstado() {
        List<CompletableFuture<EstadoServiciosResponse.ServicioDetalle>> pendientes = new ArrayList<>();

        // 1. Base de datos local. Si no contesta dentro del presupuesto, está caída.
        pendientes.add(conPresupuesto("basedatos", "datos", "DOWN", this::verificarBaseDatos));

        // 2. Servicios separados. El que no está configurado no ocupa un hilo.
        for (String[] servicio : SERVICIOS) {
            String nombre = servicio[0];
            String tipo = servicio[1];
            String rutaHealth = servicio[2];
            String urlBase = urlsServicios.getOrDefault(nombre, "");
            if (urlBase == null || urlBase.isBlank()) {
                pendientes.add(CompletableFuture.completedFuture(new EstadoServiciosResponse.ServicioDetalle(
                        nombre,
                        tipo,
                        "NO_DISPONIBLE",
                        0,
                        0,
                        "Servicio separado no configurado o pendiente de despliegue")));
            } else {
                pendientes.add(conPresupuesto(
                        nombre, tipo, "NO_DISPONIBLE", () -> consultarServicio(nombre, tipo, urlBase, rutaHealth)));
            }
        }

        // Cada pendiente se completa solo al agotar el presupuesto, así que join() no se queda bloqueado.
        List<EstadoServiciosResponse.ServicioDetalle> servicios = new ArrayList<>();
        for (var pendiente : pendientes) {
            servicios.add(pendiente.join());
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

    /**
     * Corre una verificación en el pool propio. Si no termina dentro del presupuesto, o
     * si lanza, la tarjeta conserva el nombre del componente y queda con el estado dado.
     */
    private CompletableFuture<EstadoServiciosResponse.ServicioDetalle> conPresupuesto(
            String nombre,
            String tipo,
            String estadoSinRespuesta,
            Supplier<EstadoServiciosResponse.ServicioDetalle> verificacion) {
        var sinRespuesta = new EstadoServiciosResponse.ServicioDetalle(
                nombre, tipo, estadoSinRespuesta, 0, presupuesto.toMillis(), "Sin respuesta en " + textoPresupuesto());
        return CompletableFuture.supplyAsync(verificacion, ejecutor)
                .completeOnTimeout(sinRespuesta, presupuesto.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(e -> new EstadoServiciosResponse.ServicioDetalle(
                        nombre, tipo, estadoSinRespuesta, 0, 0, "Error al consultar " + nombre + ": " + e.getMessage()));
    }

    private String textoPresupuesto() {
        long ms = presupuesto.toMillis();
        return ms % 1000 == 0 ? (ms / 1000) + " s" : ms + " ms";
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
            String nombre, String tipo, String urlBase, String rutaHealth) {
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
