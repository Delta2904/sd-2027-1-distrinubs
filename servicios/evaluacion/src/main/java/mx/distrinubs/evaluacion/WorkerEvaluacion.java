package mx.distrinubs.evaluacion;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import mx.distrinubs.evaluacion.EjecutorPython.Caso;
import mx.distrinubs.evaluacion.EjecutorPython.Evaluacion;
import mx.distrinubs.evaluacion.EjecutorPython.FallaServidor;

/**
 * Evalúa un envío concreto, el que trae un mensaje de RabbitMQ, con las mismas
 * garantías de la etapa 1: un lease por envío, resultado único y tope de tomas.
 *
 * <p>El mensaje no decide quién evalúa: decide el lease. Si el envío ya tiene
 * resultado o lo está evaluando otro worker con un lease vigente, este worker no
 * lo toca y le dice al consumidor qué hacer con el mensaje.
 *
 * <p>Dueño de datos: escribe {@code leases_evaluacion} y {@code resultados}; solo
 * lee {@code envios}, {@code problemas} y {@code casos_prueba}.
 */
@Component
class WorkerEvaluacion {

    private static final Logger log = LoggerFactory.getLogger(WorkerEvaluacion.class);

    /**
     * Toma el lease del envío. Si ya tenía uno vencido, lo retoma en la misma fila
     * y suma {@code veces_tomado}; si sigue vigente o agotó sus tomas, no lo toca.
     * La duración es la salvaguarda de compilación + casos × límite de tiempo + margen.
     * Dos workers con el mismo envío chocan en la unicidad del lease: solo uno lo toma.
     */
    private static final String TOMAR_LEASE = """
            WITH candidato AS (
                SELECT e.id,
                       now() + (? + p.limite_tiempo_ms *
                           (SELECT count(*) FROM casos_prueba c WHERE c.problema_id = p.id))
                           * interval '1 millisecond' AS vence_en
                FROM envios e
                JOIN problemas p ON p.id = e.problema_id
                WHERE e.id = ?
                  AND e.estado = 'EN_COLA'
                  AND NOT EXISTS (SELECT 1 FROM resultados r WHERE r.envio_id = e.id)
            )
            INSERT INTO leases_evaluacion (envio_id, worker, tomado_en, vence_en)
            SELECT id, ?, now(), vence_en FROM candidato
            ON CONFLICT (envio_id) DO UPDATE
                SET worker = EXCLUDED.worker,
                    tomado_en = EXCLUDED.tomado_en,
                    vence_en = EXCLUDED.vence_en,
                    veces_tomado = leases_evaluacion.veces_tomado + 1
                WHERE leases_evaluacion.vence_en <= now()
                  AND leases_evaluacion.veces_tomado < ?
            RETURNING veces_tomado
            """;

    /** Por qué no se tomó el lease. La espera se mide con el reloj de PostgreSQL. */
    private static final String ESTADO_ENVIO = """
            SELECT EXISTS (SELECT 1 FROM envios WHERE id = ? AND estado = 'EN_COLA') AS en_cola,
                   EXISTS (SELECT 1 FROM resultados WHERE envio_id = ?) AS resuelto,
                   l.veces_tomado,
                   GREATEST(0, CEIL(EXTRACT(EPOCH FROM (l.vence_en - now())) * 1000))::bigint AS espera_ms
            FROM (SELECT 1) uno
            LEFT JOIN leases_evaluacion l ON l.envio_id = ?
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
     * Deja vencido el lease propio después de una falla del servidor, para que
     * otro worker lo retome sin esperar. El vencimiento debe quedar después de la
     * toma.
     */
    private static final String LIBERAR_LEASE = """
            UPDATE leases_evaluacion
            SET vence_en = GREATEST(now(), tomado_en + interval '1 millisecond')
            WHERE envio_id = ? AND worker = ? AND veces_tomado = ?
            """;

    /**
     * Tope de reintentos: un envío cuya última toma permitida venció sin
     * resultado queda con ERROR_SERVIDOR. Es una falla del servidor, no del
     * alumno: la tabla de posiciones no lo cuenta como fallo. El filtro opcional
     * por envío lo usa el consumidor; el barrido revisa todos.
     */
    private static final String AGOTAR_REINTENTOS = """
            INSERT INTO resultados (envio_id, veredicto, detalle)
            SELECT l.envio_id, 'ERROR_SERVIDOR', ?
            FROM leases_evaluacion l
            WHERE l.vence_en <= now()
              AND l.veces_tomado >= ?
              AND (?::bigint IS NULL OR l.envio_id = ?::bigint)
              AND NOT EXISTS (SELECT 1 FROM resultados r WHERE r.envio_id = l.envio_id)
            ON CONFLICT (envio_id) DO NOTHING
            """;

    /** Qué pasó con el envío, para que el consumidor decida qué hacer con su mensaje. */
    enum Tipo {
        /** Se guardó su veredicto. */
        EVALUADO,
        /** Ya tenía resultado: el mensaje era un duplicado. */
        YA_RESUELTO,
        /** No existe o no está en cola. */
        SIN_ENVIO,
        /** Otro worker lo tiene con un lease vigente: hay que esperar {@code esperaMs}. */
        LEASE_AJENO,
        /** El servidor no pudo evaluarlo y quedan tomas: otro worker debe reintentarlo. */
        FALLA_SERVIDOR,
        /** Agotó sus tomas: quedó con ERROR_SERVIDOR. */
        AGOTADO
    }

    record Desenlace(Tipo tipo, long esperaMs) {
        static Desenlace de(Tipo tipo) {
            return new Desenlace(tipo, 0);
        }
    }

    private record EnvioPorEvaluar(long problemaId, String codigo, int limiteTiempoMs) {}

    private final JdbcTemplate jdbc;
    private final EjecutorPython ejecutor;
    private final long duracionExtraLeaseMs;
    private final int maxTomas;

    @Autowired
    WorkerEvaluacion(JdbcTemplate jdbc, EjecutorPython ejecutor,
            @Value("${evaluacion.salvaguarda-compilacion-ms:10000}") long salvaguardaCompilacionMs,
            @Value("${evaluacion.margen-lease-ms:10000}") long margenLeaseMs,
            @Value("${evaluacion.max-tomas:3}") int maxTomas) {
        this(jdbc, ejecutor, salvaguardaCompilacionMs + margenLeaseMs, maxTomas);
    }

    /**
     * @param duracionExtraLeaseMs salvaguarda de compilación más margen del lease
     * @param maxTomas veces que se puede tomar un envío antes de darlo por no evaluable
     */
    WorkerEvaluacion(JdbcTemplate jdbc, EjecutorPython ejecutor, long duracionExtraLeaseMs, int maxTomas) {
        if (maxTomas < 1) {
            throw new IllegalArgumentException("evaluacion.max-tomas debe ser al menos 1");
        }
        this.jdbc = jdbc;
        this.ejecutor = ejecutor;
        this.duracionExtraLeaseMs = duracionExtraLeaseMs;
        this.maxTomas = maxTomas;
    }

    /**
     * Intenta evaluar el envío como {@code worker}, el nombre con el que firma su
     * lease y que debe ser distinto en cada consumidor.
     */
    Desenlace procesar(long envioId, String worker) {
        List<Integer> toma = jdbc.queryForList(TOMAR_LEASE, Integer.class,
                duracionExtraLeaseMs, envioId, worker, maxTomas);
        if (toma.isEmpty()) {
            return sinLease(envioId);
        }
        int vecesTomado = toma.get(0);
        if (vecesTomado > 1) {
            log.warn("Envío {}: {} retoma su lease vencido (toma {} de {})", envioId, worker, vecesTomado, maxTomas);
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
            // Error de configuración del problema, no del alumno: no penaliza.
            log.error("Envío {}: el problema {} no tiene casos de prueba", envioId, envio.problemaId());
            return fallaDelServidor(envioId, worker, vecesTomado);
        }

        Evaluacion evaluacion;
        try {
            evaluacion = ejecutor.evaluar(envio.codigo(), casos, envio.limiteTiempoMs());
        } catch (FallaServidor e) {
            log.error("Envío {}: falla del servidor en la toma {} de {}: {}",
                    envioId, vecesTomado, maxTomas, e.getMessage());
            return fallaDelServidor(envioId, worker, vecesTomado);
        }

        int guardados = jdbc.update(GUARDAR_RESULTADO,
                envioId, evaluacion.veredicto(), evaluacion.tiempoMs(), evaluacion.detalle(),
                envioId, worker, vecesTomado);
        if (guardados == 1) {
            log.info("Envío {}: {} ({} ms) por {}", envioId, evaluacion.veredicto(), evaluacion.tiempoMs(), worker);
            return Desenlace.de(Tipo.EVALUADO);
        }
        log.warn("Envío {}: no se guardó el resultado; ya tenía uno o el lease ya no es de {}", envioId, worker);
        return Desenlace.de(Tipo.YA_RESUELTO);
    }

    /** Cierra con ERROR_SERVIDOR todos los envíos que agotaron sus tomas. */
    int agotarReintentos() {
        int agotados = jdbc.update(AGOTAR_REINTENTOS, mensajeAgotado(), maxTomas, null, null);
        if (agotados > 0) {
            log.error("{} envío(s) agotaron sus {} tomas sin resultado: quedan con ERROR_SERVIDOR",
                    agotados, maxTomas);
        }
        return agotados;
    }

    private Desenlace sinLease(long envioId) {
        Map<String, Object> estado = jdbc.queryForMap(ESTADO_ENVIO, envioId, envioId, envioId);
        if (Boolean.TRUE.equals(estado.get("resuelto"))) {
            return Desenlace.de(Tipo.YA_RESUELTO);
        }
        if (!Boolean.TRUE.equals(estado.get("en_cola"))) {
            return Desenlace.de(Tipo.SIN_ENVIO);
        }
        Number veces = (Number) estado.get("veces_tomado");
        long esperaMs = estado.get("espera_ms") == null ? 0 : ((Number) estado.get("espera_ms")).longValue();
        if (veces != null && esperaMs > 0) {
            return new Desenlace(Tipo.LEASE_AJENO, esperaMs);
        }
        if (veces != null && veces.intValue() >= maxTomas) {
            int agotados = jdbc.update(AGOTAR_REINTENTOS, mensajeAgotado(), maxTomas, envioId, envioId);
            if (agotados > 0) {
                log.error("Envío {}: agotó sus {} tomas sin resultado: queda con ERROR_SERVIDOR", envioId, maxTomas);
            }
            return Desenlace.de(Tipo.AGOTADO);
        }
        // El lease venció entre la toma y la consulta: se intenta de nuevo sin esperar.
        return new Desenlace(Tipo.LEASE_AJENO, 0);
    }

    /**
     * Falla del servidor: no penaliza. Si quedan tomas, libera el lease para que
     * otro worker lo retome; en la última toma deja el veredicto ERROR_SERVIDOR.
     */
    private Desenlace fallaDelServidor(long envioId, String worker, int vecesTomado) {
        if (vecesTomado >= maxTomas) {
            int guardados = jdbc.update(GUARDAR_RESULTADO, envioId, "ERROR_SERVIDOR", null, mensajeAgotado(),
                    envioId, worker, vecesTomado);
            if (guardados == 1) {
                log.error("Envío {}: agotó sus {} tomas sin resultado: queda con ERROR_SERVIDOR", envioId, maxTomas);
            }
            return Desenlace.de(Tipo.AGOTADO);
        }
        jdbc.update(LIBERAR_LEASE, envioId, worker, vecesTomado);
        return Desenlace.de(Tipo.FALLA_SERVIDOR);
    }

    private String mensajeAgotado() {
        return "El servidor no pudo evaluar el envío después de " + maxTomas
                + " tomas. No cuenta como intento: vuelve a enviarlo.";
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
