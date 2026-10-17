package mx.distrinubs.evaluacion;

import java.net.InetAddress;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import mx.distrinubs.evaluacion.EjecutorPython.Caso;
import mx.distrinubs.evaluacion.EjecutorPython.Evaluacion;
import mx.distrinubs.evaluacion.EjecutorPython.FallaServidor;

/**
 * El único worker de evaluación de la etapa 1 (PRD, sección 0, punto 4).
 *
 * <p>Corre dentro de la aplicación (PRD, bitácora 7). Toma de {@code envios} el
 * envío en cola más antiguo sin resultado ni lease vigente, lo toma con un lease
 * en {@code leases_evaluacion}, lo evalúa y escribe su fila en {@code resultados}.
 * No llama a Envíos: "evaluando" se deriva del lease vigente.
 *
 * <p>Dueño de datos (PRD, sección 6): escribe {@code leases_evaluacion} y
 * {@code resultados}; solo lee {@code envios}, {@code problemas} y
 * {@code casos_prueba}.
 */
@Component
@ConditionalOnProperty(name = "evaluacion.activa", havingValue = "true", matchIfMissing = true)
class WorkerEvaluacion {

    private static final Logger log = LoggerFactory.getLogger(WorkerEvaluacion.class);

    /**
     * Toma un lease sobre el envío en cola más antiguo. Si el envío ya tenía un
     * lease vencido, lo retoma en la misma fila y suma {@code veces_tomado}; si el
     * lease sigue vigente, no lo toca. La duración es la salvaguarda de
     * compilación + casos × límite de tiempo + margen (PRD, bitácora 11).
     */
    private static final String TOMAR_LEASE = """
            INSERT INTO leases_evaluacion (envio_id, worker, tomado_en, vence_en)
            SELECT e.id, ?, now(),
                   now() + (? + p.limite_tiempo_ms *
                       (SELECT count(*) FROM casos_prueba c WHERE c.problema_id = p.id))
                       * interval '1 millisecond'
            FROM envios e
            JOIN problemas p ON p.id = e.problema_id
            WHERE e.estado = 'EN_COLA'
              AND NOT EXISTS (SELECT 1 FROM resultados r WHERE r.envio_id = e.id)
              AND NOT EXISTS (SELECT 1 FROM leases_evaluacion l
                              WHERE l.envio_id = e.id AND l.vence_en > now())
            ORDER BY e.llegada_en, e.id
            LIMIT 1
            ON CONFLICT (envio_id) DO UPDATE
                SET worker = EXCLUDED.worker,
                    tomado_en = EXCLUDED.tomado_en,
                    vence_en = EXCLUDED.vence_en,
                    veces_tomado = leases_evaluacion.veces_tomado + 1
                WHERE leases_evaluacion.vence_en <= now()
            RETURNING envio_id, veces_tomado
            """;

    /**
     * Guarda el resultado solo si este worker todavía tiene el lease que tomó
     * (mismo worker y misma {@code veces_tomado}). La unicidad por envío evita
     * que cuente dos veces.
     */
    private static final String GUARDAR_RESULTADO = """
            INSERT INTO resultados (envio_id, veredicto, tiempo_ms, detalle)
            SELECT ?::bigint, ?::varchar, ?::integer, ?::text
            WHERE EXISTS (SELECT 1 FROM leases_evaluacion
                          WHERE envio_id = ? AND worker = ? AND veces_tomado = ?)
            ON CONFLICT (envio_id) DO NOTHING
            """;

    private record Lease(long envioId, int vecesTomado) {}

    private record EnvioPorEvaluar(long problemaId, String codigo, int limiteTiempoMs) {}

    private final JdbcTemplate jdbc;
    private final EjecutorPython ejecutor;
    private final long duracionExtraLeaseMs;
    private final String worker;

    WorkerEvaluacion(
            JdbcTemplate jdbc,
            EjecutorPython ejecutor,
            @Value("${evaluacion.salvaguarda-compilacion-ms:10000}") long salvaguardaCompilacionMs,
            @Value("${evaluacion.margen-lease-ms:10000}") long margenLeaseMs) {
        this.jdbc = jdbc;
        this.ejecutor = ejecutor;
        this.duracionExtraLeaseMs = salvaguardaCompilacionMs + margenLeaseMs;
        this.worker = nombreWorker();
        log.info("Worker de evaluación activo: {}", worker);
    }

    @Scheduled(fixedDelayString = "${evaluacion.intervalo-ms:1000}")
    void revisarCola() {
        try {
            while (evaluarSiguiente()) {
                // Vacía la cola antes de volver a esperar.
            }
        } catch (DataAccessException e) {
            log.warn("No se pudo leer la cola de envíos: {}", e.getMessage());
        }
    }

    private boolean evaluarSiguiente() {
        List<Lease> tomados = jdbc.query(TOMAR_LEASE,
                (rs, i) -> new Lease(rs.getLong("envio_id"), rs.getInt("veces_tomado")),
                worker, duracionExtraLeaseMs);
        if (tomados.isEmpty()) {
            return false;
        }
        Lease lease = tomados.get(0);
        if (lease.vecesTomado() > 1) {
            log.warn("Envío {}: se retoma su lease vencido (vez {})", lease.envioId(), lease.vecesTomado());
        }

        EnvioPorEvaluar envio = jdbc.queryForObject("""
                SELECT e.problema_id, e.codigo, p.limite_tiempo_ms
                FROM envios e JOIN problemas p ON p.id = e.problema_id
                WHERE e.id = ?
                """,
                (rs, i) -> new EnvioPorEvaluar(rs.getLong("problema_id"), rs.getString("codigo"),
                        rs.getInt("limite_tiempo_ms")),
                lease.envioId());

        List<Caso> casos = casosDelMarcadorProvisional(envio.problemaId());
        if (casos.isEmpty()) {
            // Error de configuración del problema, no del alumno: no se guarda resultado.
            log.error("Envío {}: el problema {} no tiene casos de prueba", lease.envioId(), envio.problemaId());
            return true;
        }

        Evaluacion evaluacion;
        try {
            evaluacion = ejecutor.evaluar(envio.codigo(), casos, envio.limiteTiempoMs());
        } catch (FallaServidor e) {
            // Falla del servidor: no penaliza. El envío vuelve a la cola cuando
            // vence el lease (PRD, sección 9).
            log.error("Envío {}: falla del servidor, se reintentará al vencer el lease: {}",
                    lease.envioId(), e.getMessage());
            return true;
        }

        int guardados = jdbc.update(GUARDAR_RESULTADO,
                lease.envioId(), evaluacion.veredicto(), evaluacion.tiempoMs(), evaluacion.detalle(),
                lease.envioId(), worker, lease.vecesTomado());
        if (guardados == 1) {
            log.info("Envío {}: {} ({} ms)", lease.envioId(), evaluacion.veredicto(), evaluacion.tiempoMs());
        } else {
            log.warn("Envío {}: no se guardó el resultado; ya tenía uno o el lease ya no es de este worker",
                    lease.envioId());
        }
        return true;
    }

    /**
     * En vivo solo corre el subconjunto del marcador provisional; si el problema
     * no marcó ninguno, corre todos (PRD, bitácora 8).
     */
    private List<Caso> casosDelMarcadorProvisional(long problemaId) {
        List<Caso> marcados = casos(problemaId, "AND en_marcador_provisional");
        return marcados.isEmpty() ? casos(problemaId, "") : marcados;
    }

    private List<Caso> casos(long problemaId, String filtro) {
        return jdbc.query(
                "SELECT orden, entrada, salida_esperada FROM casos_prueba WHERE problema_id = ? "
                        + filtro + " ORDER BY orden",
                (rs, i) -> new Caso(rs.getInt("orden"), rs.getString("entrada"), rs.getString("salida_esperada")),
                problemaId);
    }

    private static String nombreWorker() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "local";
        }
        return "worker-" + host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
