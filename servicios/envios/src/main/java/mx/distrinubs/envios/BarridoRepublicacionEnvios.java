package mx.distrinubs.envios;

import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Barrido periódico que reclama y republica envíos en {@code EN_COLA} sin resultado:
 * el envío nunca publicado se republica tras el margen de gracia; el ya publicado,
 * solo cuando supera el tiempo de espera máxima en cola. Limita el número de publicaciones,
 * excluye los envíos que agotaron sus tomas de evaluación y usa {@code SKIP LOCKED} para
 * evitar que dos réplicas tomen el mismo envío.
 */
@Component
@ConditionalOnProperty(name = "envios.barrido.activo", havingValue = "true", matchIfMissing = true)
public class BarridoRepublicacionEnvios {

    private static final Logger log = LoggerFactory.getLogger(BarridoRepublicacionEnvios.class);

    private static final String SQL_RECLAMAR_PENDIENTES = """
            UPDATE envios SET publicado_en = now()
            WHERE id IN (
              SELECT e.id FROM envios e
              LEFT JOIN resultados r ON r.envio_id = e.id
              LEFT JOIN leases_evaluacion l ON l.envio_id = e.id
              WHERE e.estado = 'EN_COLA'
                AND r.id IS NULL
                AND (l.envio_id IS NULL OR (l.vence_en <= now() AND l.veces_tomado < ?))
                AND e.veces_publicado < ?
                AND ((e.publicado_en IS NULL AND e.llegada_en < now() - (? * interval '1 second'))
                     OR e.publicado_en < now() - (? * interval '1 second'))
              ORDER BY e.llegada_en ASC, e.id ASC
              LIMIT ?
              FOR NO KEY UPDATE OF e SKIP LOCKED)
            RETURNING id
            """;

    private final JdbcTemplate jdbc;
    private final PublicadorEnvios publicadorEnvios;
    private final int graciaSegundos;
    private final int esperaMaximaColaSegundos;
    private final int maxPublicaciones;
    private final int maxTomas;
    private final int tamanoLote;

    public BarridoRepublicacionEnvios(
            JdbcTemplate jdbc,
            PublicadorEnvios publicadorEnvios,
            @Value("${envios.barrido.gracia-segundos:10}") int graciaSegundos,
            @Value("${envios.barrido.espera-maxima-cola-segundos:300}") int esperaMaximaColaSegundos,
            @Value("${envios.barrido.max-publicaciones:3}") int maxPublicaciones,
            @Value("${envios.barrido.max-tomas:3}") int maxTomas,
            @Value("${envios.barrido.lote:50}") int tamanoLote) {
        this.jdbc = jdbc;
        this.publicadorEnvios = publicadorEnvios;
        this.graciaSegundos = graciaSegundos;
        this.esperaMaximaColaSegundos = esperaMaximaColaSegundos;
        this.maxPublicaciones = maxPublicaciones;
        this.maxTomas = maxTomas;
        this.tamanoLote = tamanoLote;
    }

    @Scheduled(fixedDelayString = "${envios.barrido.intervalo-ms:15000}")
    public int republicarPendientes() {
        List<Long> pendientes = jdbc.query(
                SQL_RECLAMAR_PENDIENTES,
                (rs, rowNum) -> rs.getLong("id"),
                maxTomas,
                maxPublicaciones,
                graciaSegundos,
                esperaMaximaColaSegundos,
                tamanoLote);

        Collections.sort(pendientes);

        int publicados = 0;
        for (int i = 0; i < pendientes.size(); i++) {
            long envioId = pendientes.get(i);
            if (publicadorEnvios.publicar(envioId)) {
                jdbc.update("UPDATE envios SET veces_publicado = veces_publicado + 1 WHERE id = ?", envioId);
                publicados++;
            } else {
                for (int j = i; j < pendientes.size(); j++) {
                    jdbc.update("UPDATE envios SET publicado_en = NULL WHERE id = ?", pendientes.get(j));
                }
                log.warn("RabbitMQ no aceptó el envío {} durante el barrido; se libera el reclamo de {} envíos",
                        envioId, pendientes.size() - i);
                break;
            }
        }

        if (publicados > 0) {
            log.info("Barrido de republicación reencoló {} envíos pendientes", publicados);
        }
        return publicados;
    }
}
