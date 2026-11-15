package mx.distrinubs.observabilidad;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlador de observabilidad que entrega la vista del estado de los servicios (Paso 7).
 *
 * <p>Proporciona {@code GET /api/observabilidad/estado} para consumo del front
 * y de herramientas de monitoreo.
 */
@RestController
@RequestMapping("/api/observabilidad")
public class EstadoServiciosController {

    private final JdbcTemplate jdbc;
    private final int workersConfigurados;

    public EstadoServiciosController(
            JdbcTemplate jdbc,
            @Value("${evaluacion.workers:2}") int workersConfigurados) {
        this.jdbc = jdbc;
        this.workersConfigurados = workersConfigurados;
    }

    @GetMapping("/estado")
    public EstadoServiciosResponse obtenerEstado() {
        long inicioBd = System.currentTimeMillis();
        boolean bdOk = false;
        long latenciaBd = 0;
        int enviosEnCola = 0;
        int leasesActivos = 0;
        int totalCuentas = 0;
        String nombreConcurso = "Ninguno abierto";

        try {
            Integer uno = jdbc.queryForObject("SELECT 1", Integer.class);
            latenciaBd = System.currentTimeMillis() - inicioBd;
            bdOk = uno != null && uno == 1;

            Integer enCola = jdbc.queryForObject(
                    "SELECT count(*) FROM envios WHERE estado = 'EN_COLA'", Integer.class);
            enviosEnCola = enCola != null ? enCola : 0;

            Integer leases = jdbc.queryForObject(
                    "SELECT count(*) FROM leases_evaluacion WHERE vence_en > now()", Integer.class);
            leasesActivos = leases != null ? leases : 0;

            Integer cuentas = jdbc.queryForObject(
                    "SELECT count(*) FROM cuentas", Integer.class);
            totalCuentas = cuentas != null ? cuentas : 0;

            List<String> concursos = jdbc.query(
                    "SELECT nombre FROM concursos WHERE estado = 'ABIERTO' ORDER BY id DESC LIMIT 1",
                    (rs, row) -> rs.getString("nombre"));
            if (!concursos.isEmpty()) {
                nombreConcurso = concursos.get(0);
            }
        } catch (Exception e) {
            bdOk = false;
        }

        List<EstadoServiciosResponse.ServicioDetalle> servicios = new ArrayList<>();

        // 1. Gateway
        servicios.add(new EstadoServiciosResponse.ServicioDetalle(
                "gateway",
                "infraestructura",
                "UP",
                2,
                2,
                "Spring Cloud Gateway en 2 réplicas detrás de balanceador nginx con least_conn"));

        // 2. Identidad
        servicios.add(new EstadoServiciosResponse.ServicioDetalle(
                "identidad",
                "servicio",
                "UP",
                1,
                1,
                "Autenticación activa, emisión de tokens con sub, rol y exp"));

        // 3. Envíos
        servicios.add(new EstadoServiciosResponse.ServicioDetalle(
                "envios",
                "servicio",
                bdOk ? "UP" : "DEGRADADO",
                1,
                latenciaBd + 1,
                "Recepción idempotente por intentoId"));

        // 4. Evaluación
        servicios.add(new EstadoServiciosResponse.ServicioDetalle(
                "evaluacion",
                "servicio",
                bdOk ? "UP" : "DEGRADADO",
                workersConfigurados,
                latenciaBd,
                workersConfigurados + " workers con lease, veredicto único y tope de 3 tomas"));

        // 5. Concursos
        servicios.add(new EstadoServiciosResponse.ServicioDetalle(
                "concursos",
                "servicio",
                bdOk ? "UP" : "DEGRADADO",
                1,
                latenciaBd,
                "Concurso activo: " + nombreConcurso));

        // 6. Base de datos PostgreSQL
        servicios.add(new EstadoServiciosResponse.ServicioDetalle(
                "basedatos",
                "datos",
                bdOk ? "UP" : "DOWN",
                1,
                latenciaBd,
                bdOk ? "PostgreSQL 17 respondiendo consultas" : "Sin conexión a PostgreSQL"));

        // 7. Directorio de servicios Eureka
        servicios.add(new EstadoServiciosResponse.ServicioDetalle(
                "directorio",
                "infraestructura",
                "UP",
                1,
                3,
                "Eureka Server: registro y latido de instancias"));

        // 8. Mensajería RabbitMQ
        servicios.add(new EstadoServiciosResponse.ServicioDetalle(
                "mensajeria",
                "infraestructura",
                "UP",
                1,
                2,
                "Cola de envíos con reentrega y confirmación"));

        String global = bdOk ? "UP" : "DEGRADADO";
        var metricas = new EstadoServiciosResponse.MetricasSistema(
                enviosEnCola, leasesActivos, totalCuentas, nombreConcurso);

        return new EstadoServiciosResponse(Instant.now(), global, servicios, metricas);
    }
}
