package mx.distrinubs.envios;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Barrido periódico que detecta envíos persistidos en {@code EN_COLA} que llevan
 * más tiempo del margen de gracia sin resultado ni lease vigente (por ejemplo, si el
 * servicio cayó entre guardar en PostgreSQL y publicar en RabbitMQ) y los republica.
 */
@Component
@ConditionalOnProperty(name = "envios.barrido.activo", havingValue = "true", matchIfMissing = true)
public class BarridoRepublicacionEnvios {

    private static final Logger log = LoggerFactory.getLogger(BarridoRepublicacionEnvios.class);

    private static final String SQL_PENDIENTES = """
            SELECT e.id
            FROM envios e
            LEFT JOIN resultados r ON r.envio_id = e.id
            LEFT JOIN leases_evaluacion l ON l.envio_id = e.id AND l.vence_en > now()
            WHERE e.estado = 'EN_COLA'
              AND e.llegada_en < now() - (? * interval '1 second')
              AND r.id IS NULL
              AND l.id IS NULL
            ORDER BY e.llegada_en ASC, e.id ASC
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;
    private final PublicadorEnvios publicadorEnvios;
    private final int graciaSegundos;
    private final int tamanoLote;

    public BarridoRepublicacionEnvios(
            JdbcTemplate jdbc,
            PublicadorEnvios publicadorEnvios,
            @Value("${envios.barrido.gracia-segundos:10}") int graciaSegundos,
            @Value("${envios.barrido.lote:50}") int tamanoLote) {
        this.jdbc = jdbc;
        this.publicadorEnvios = publicadorEnvios;
        this.graciaSegundos = graciaSegundos;
        this.tamanoLote = tamanoLote;
    }

    @Scheduled(fixedDelayString = "${envios.barrido.intervalo-ms:15000}")
    public int republicarPendientes() {
        List<Long> pendientes = jdbc.query(
                SQL_PENDIENTES,
                (rs, rowNum) -> rs.getLong("id"),
                graciaSegundos,
                tamanoLote);

        for (Long envioId : pendientes) {
            publicadorEnvios.publicar(envioId);
        }

        if (!pendientes.isEmpty()) {
            log.info("Barrido de republicación reencoló {} envíos pendientes", pendientes.size());
        }
        return pendientes.size();
    }
}
