package mx.distrinubs.observabilidad;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * HealthIndicator personalizado para Spring Boot Actuator (Paso 7).
 *
 * <p>Enriquece {@code /actuator/health} con métricas y estado específico de Distrinubs:
 * workers de evaluación, leases activos y envíos en cola.
 */
@Component
public class DistrinubsHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbc;
    private final int workers;

    public DistrinubsHealthIndicator(
            JdbcTemplate jdbc,
            @Value("${evaluacion.workers:2}") int workers) {
        this.jdbc = jdbc;
        this.workers = workers;
    }

    @Override
    public Health health() {
        try {
            Integer uno = jdbc.queryForObject("SELECT 1", Integer.class);
            if (uno == null || uno != 1) {
                return Health.down().withDetail("motivo", "Respuesta inesperada de PostgreSQL").build();
            }

            Integer enCola = jdbc.queryForObject(
                    "SELECT count(*) FROM envios WHERE estado = 'EN_COLA'", Integer.class);
            Integer leases = jdbc.queryForObject(
                    "SELECT count(*) FROM leases_evaluacion WHERE vence_en > now()", Integer.class);

            return Health.up()
                    .withDetail("workersEvaluacion", workers)
                    .withDetail("enviosEnCola", enCola != null ? enCola : 0)
                    .withDetail("leasesActivos", leases != null ? leases : 0)
                    .withDetail("modo", "02-separacion")
                    .build();
        } catch (Exception e) {
            return Health.down(e).withDetail("motivo", "Falla de acceso a base de datos").build();
        }
    }
}
