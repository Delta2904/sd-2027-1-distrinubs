package mx.distrinubs.evaluacion;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import mx.distrinubs.evaluacion.EjecutorPython.Caso;
import mx.distrinubs.evaluacion.EjecutorPython.Evaluacion;
import mx.distrinubs.evaluacion.EjecutorPython.FallaServidor;

/**
 * Un worker de evaluación. La aplicación crea {@code evaluacion.workers} de
 * ellos, cada uno en su hilo y con su nombre; todos leen la misma cola.
 *
 * <p>Corre dentro de la aplicación, no en un contenedor aparte. Toma de {@code envios} el
 * envío en cola más antiguo sin resultado ni lease vigente, lo toma con un lease
 * en {@code leases_evaluacion}, lo evalúa y escribe su fila en {@code resultados}.
 * No llama a Envíos: "evaluando" se deriva del lease vigente.
 *
 * <p>Dueño de datos: escribe {@code leases_evaluacion} y
 * {@code resultados}; solo lee {@code envios}, {@code problemas} y
 * {@code casos_prueba}.
 */
class WorkerEvaluacion {

    private static final Logger log = LoggerFactory.getLogger(WorkerEvaluacion.class);

    /**
     * Toma un lease sobre el envío en cola más antiguo. Si el envío ya tenía un
     * lease vencido, lo retoma en la misma fila y suma {@code veces_tomado}; si el
     * lease sigue vigente o ya agotó sus tomas, no lo toca. La duración es la
     * salvaguarda de compilación + casos × límite de tiempo + margen.
     *
     * <p>Con varios workers: {@code SKIP LOCKED} bloquea un instante la fila del
     * envío candidato (no la escribe) para que cada worker elija uno distinto.
     * Aun así dos workers pueden elegir el mismo, porque lo que marca la toma es
     * la fila del lease: el que llega segundo choca con la unicidad y no toma
     * nada. Por eso la consulta devuelve también cuántos candidatos vio:
     * candidato sin lease tomado es una carrera perdida, no una cola vacía.
     */
    private static final String TOMAR_LEASE = """
            WITH candidato AS (
                SELECT e.id,
                       now() + (? + p.limite_tiempo_ms *
                           (SELECT count(*) FROM casos_prueba c WHERE c.problema_id = p.id))
                           * interval '1 millisecond' AS vence_en
                FROM envios e
                JOIN problemas p ON p.id = e.problema_id
                WHERE e.estado = 'EN_COLA'
                  AND NOT EXISTS (SELECT 1 FROM resultados r WHERE r.envio_id = e.id)
                  AND NOT EXISTS (SELECT 1 FROM leases_evaluacion l
                                  WHERE l.envio_id = e.id
                                    AND (l.vence_en > now() OR l.veces_tomado >= ?))
                ORDER BY e.llegada_en, e.id
                LIMIT 1
                FOR NO KEY UPDATE OF e SKIP LOCKED
            ), tomado AS (
                INSERT INTO leases_evaluacion (envio_id, worker, tomado_en, vence_en)
                SELECT id, ?, now(), vence_en FROM candidato
                ON CONFLICT (envio_id) DO UPDATE
                    SET worker = EXCLUDED.worker,
                        tomado_en = EXCLUDED.tomado_en,
                        vence_en = EXCLUDED.vence_en,
                        veces_tomado = leases_evaluacion.veces_tomado + 1
                    WHERE leases_evaluacion.vence_en <= now()
                      AND leases_evaluacion.veces_tomado < ?
                RETURNING envio_id, veces_tomado
            )
            SELECT (SELECT count(*) FROM candidato) AS candidatos, t.envio_id, t.veces_tomado
            FROM (SELECT 1) uno LEFT JOIN tomado t ON TRUE
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

    /**
     * Tope de reintentos: un envío cuya última toma permitida venció sin
     * resultado queda con el veredicto ERROR_SERVIDOR. Es una falla del
     * servidor, no del alumno: la tabla de posiciones no lo cuenta como fallo.
     * Si la última toma todavía alcanza a guardar su veredicto, la unicidad por
     * envío deja en pie al primero que llegue.
     */
    private static final String AGOTAR_REINTENTOS = """
            INSERT INTO resultados (envio_id, veredicto, detalle)
            SELECT l.envio_id, 'ERROR_SERVIDOR', ?
            FROM leases_evaluacion l
            WHERE l.vence_en <= now()
              AND l.veces_tomado >= ?
              AND NOT EXISTS (SELECT 1 FROM resultados r WHERE r.envio_id = l.envio_id)
            ON CONFLICT (envio_id) DO NOTHING
            """;

    /** Qué pasó al intentar tomar un envío. */
    private enum Paso { EVALUADO, CARRERA_PERDIDA, COLA_VACIA }

    private record Toma(int candidatos, Long envioId, int vecesTomado) {}

    private record EnvioPorEvaluar(long problemaId, String codigo, int limiteTiempoMs) {}

    private final JdbcTemplate jdbc;
    private final EjecutorPython ejecutor;
    private final long duracionExtraLeaseMs;
    private final int maxTomas;
    private final String worker;

    /**
     * @param duracionExtraLeaseMs salvaguarda de compilación más margen del lease
     * @param maxTomas veces que se puede tomar un envío antes de darlo por no evaluable
     * @param worker nombre con el que firma sus leases; distinto en cada worker
     */
    WorkerEvaluacion(JdbcTemplate jdbc, EjecutorPython ejecutor, long duracionExtraLeaseMs,
            int maxTomas, String worker) {
        this.jdbc = jdbc;
        this.ejecutor = ejecutor;
        this.duracionExtraLeaseMs = duracionExtraLeaseMs;
        this.maxTomas = maxTomas;
        this.worker = worker;
        log.info("Worker de evaluación activo: {}", worker);
    }

    /** Una pasada: cierra los envíos que agotaron sus tomas y vacía la cola. */
    void revisarCola() {
        try {
            agotarReintentos();
            // Vacía la cola antes de volver a esperar. Una carrera perdida no
            // es cola vacía: se intenta de nuevo sin dormir.
            while (evaluarSiguiente() != Paso.COLA_VACIA) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
            }
        } catch (DataAccessException e) {
            log.warn("No se pudo leer la cola de envíos: {}", e.getMessage());
        }
    }

    private void agotarReintentos() {
        int agotados = jdbc.update(AGOTAR_REINTENTOS,
                "El servidor no pudo evaluar el envío después de " + maxTomas
                        + " tomas. No cuenta como intento: vuelve a enviarlo.",
                maxTomas);
        if (agotados > 0) {
            log.error("{} envío(s) agotaron sus {} tomas sin resultado: quedan con ERROR_SERVIDOR",
                    agotados, maxTomas);
        }
    }

    private Paso evaluarSiguiente() {
        Toma toma = jdbc.queryForObject(TOMAR_LEASE,
                (rs, i) -> new Toma(rs.getInt("candidatos"), rs.getObject("envio_id", Long.class),
                        rs.getInt("veces_tomado")),
                duracionExtraLeaseMs, maxTomas, worker, maxTomas);
        if (toma.envioId() == null) {
            return toma.candidatos() == 0 ? Paso.COLA_VACIA : Paso.CARRERA_PERDIDA;
        }
        long envioId = toma.envioId();
        if (toma.vecesTomado() > 1) {
            log.warn("Envío {}: se retoma su lease vencido (toma {} de {})", envioId, toma.vecesTomado(), maxTomas);
        }

        EnvioPorEvaluar envio = jdbc.queryForObject("""
                SELECT e.problema_id, e.codigo, p.limite_tiempo_ms
                FROM envios e JOIN problemas p ON p.id = e.problema_id
                WHERE e.id = ?
                """,
                (rs, i) -> new EnvioPorEvaluar(rs.getLong("problema_id"), rs.getString("codigo"),
                        rs.getInt("limite_tiempo_ms")),
                envioId);

        List<Caso> casos = casosDelMarcadorProvisional(envio.problemaId());
        if (casos.isEmpty()) {
            // Error de configuración del problema, no del alumno: no se guarda resultado.
            log.error("Envío {}: el problema {} no tiene casos de prueba", envioId, envio.problemaId());
            return Paso.EVALUADO;
        }

        Evaluacion evaluacion;
        try {
            evaluacion = ejecutor.evaluar(envio.codigo(), casos, envio.limiteTiempoMs());
        } catch (FallaServidor e) {
            // Falla del servidor: no penaliza. El envío vuelve a la cola cuando
            // vence el lease, hasta agotar sus tomas.
            log.error("Envío {}: falla del servidor en la toma {} de {}: {}",
                    envioId, toma.vecesTomado(), maxTomas, e.getMessage());
            return Paso.EVALUADO;
        }

        int guardados = jdbc.update(GUARDAR_RESULTADO,
                envioId, evaluacion.veredicto(), evaluacion.tiempoMs(), evaluacion.detalle(),
                envioId, worker, toma.vecesTomado());
        if (guardados == 1) {
            log.info("Envío {}: {} ({} ms)", envioId, evaluacion.veredicto(), evaluacion.tiempoMs());
        } else {
            log.warn("Envío {}: no se guardó el resultado; ya tenía uno o el lease ya no es de este worker",
                    envioId);
        }
        return Paso.EVALUADO;
    }

    /**
     * En vivo solo corre el subconjunto del marcador provisional; si el problema
     * no marcó ninguno, corre todos.
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
}
