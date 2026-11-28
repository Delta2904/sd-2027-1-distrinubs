package mx.distrinubs.envios;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
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

    /**
     * Reclama los pendientes poniendo {@code publicado_en = now()} y devuelve, junto con
     * cada id, el valor que tenía antes: hace falta para deshacer el reclamo de los que
     * al final no se publiquen.
     */
    private static final String SQL_RECLAMAR_PENDIENTES = """
            WITH candidatos AS (
              SELECT e.id, e.publicado_en AS publicado_antes FROM envios e
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
            UPDATE envios SET publicado_en = now()
            FROM candidatos c
            WHERE envios.id = c.id
            RETURNING envios.id, c.publicado_antes
            """;

    private record Reclamo(long envioId, Timestamp publicadoAntes) {}

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
        List<Reclamo> reclamados = new ArrayList<>(jdbc.query(
                SQL_RECLAMAR_PENDIENTES,
                (rs, rowNum) -> new Reclamo(rs.getLong("id"), rs.getTimestamp("publicado_antes")),
                maxTomas,
                maxPublicaciones,
                graciaSegundos,
                esperaMaximaColaSegundos,
                tamanoLote));

        reclamados.sort(Comparator.comparingLong(Reclamo::envioId));

        // Sale de aquí cada envío cuyo mensaje ya está en la cola. Los que queden al
        // terminar, por la razón que sea, pierden el reclamo en el finally.
        List<Reclamo> sinPublicar = new ArrayList<>(reclamados);
        int publicados = 0;
        try {
            for (Reclamo reclamo : reclamados) {
                long envioId = reclamo.envioId();
                try {
                    if (!publicadorEnvios.publicar(envioId)) {
                        log.warn("RabbitMQ no aceptó el envío {} durante el barrido; se corta el lote", envioId);
                        break;
                    }
                    sinPublicar.remove(reclamo);
                    publicados++;
                    jdbc.update("UPDATE envios SET veces_publicado = veces_publicado + 1 WHERE id = ?", envioId);
                } catch (RuntimeException e) {
                    // La falla de un envío no debe dejar a los demás sin publicar.
                    log.warn("Falló el barrido del envío {}: {}", envioId, e.toString());
                }
            }
        } finally {
            liberarReclamos(sinPublicar);
        }

        if (publicados > 0) {
            log.info("Barrido de republicación reencoló {} envíos pendientes", publicados);
        }
        return publicados;
    }

    /**
     * Devuelve {@code publicado_en} a su valor anterior ({@code NULL} si el envío nunca se
     * había publicado). Sin esto, un envío reclamado y sin mensaje no volvería a calificar
     * hasta agotar la espera máxima en cola.
     */
    private void liberarReclamos(List<Reclamo> sinPublicar) {
        for (Reclamo reclamo : sinPublicar) {
            try {
                if (reclamo.publicadoAntes() == null) {
                    jdbc.update("UPDATE envios SET publicado_en = NULL WHERE id = ?", reclamo.envioId());
                } else {
                    jdbc.update("UPDATE envios SET publicado_en = ? WHERE id = ?",
                            reclamo.publicadoAntes(), reclamo.envioId());
                }
            } catch (RuntimeException e) {
                log.warn("No se pudo liberar el reclamo del envío {}: {}", reclamo.envioId(), e.toString());
            }
        }
        if (!sinPublicar.isEmpty()) {
            log.warn("El barrido liberó el reclamo de {} envíos que no se publicaron", sinPublicar.size());
        }
    }
}
