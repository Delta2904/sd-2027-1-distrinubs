package mx.distrinubs.observabilidad;

import java.sql.Connection;

import javax.sql.DataSource;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * HealthIndicator personalizado para Spring Boot Actuator (Paso 7).
 *
 * <p>No lee tablas de otros módulos; solo verifica la conectividad de la fuente de datos
 * y reporta el estado del módulo de observabilidad.
 */
@Component
public class DistrinubsHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    public DistrinubsHealthIndicator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Health health() {
        try (Connection conn = dataSource.getConnection()) {
            if (conn != null && conn.isValid(2)) {
                return Health.up()
                        .withDetail("correlacion", "activa")
                        .withDetail("etapa", "02-separacion")
                        .build();
            }
            return Health.down().withDetail("motivo", "Conexión de base de datos inválida").build();
        } catch (Exception e) {
            return Health.down(e).withDetail("motivo", "Falla de acceso a base de datos").build();
        }
    }
}
