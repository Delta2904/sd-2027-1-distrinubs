package mx.distrinubs.tablaposiciones;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Controlador REST para el módulo de Tabla de posiciones (etapa 01-monolito).
 *
 * <p>Responsable de:
 * <ul>
 *   <li>{@code GET /api/concursos/activo/tabla}: Calcula y devuelve el marcador provisional
 *       a partir de consultas sobre envíos, resultados, cuentas y problemas.</li>
 * </ul>
 *
 * <p>Reglas de la tabla provisional (PRD secciones 0, 5, 9 y bitácora 6):
 * <ul>
 *   <li>Penalización por problema: minuto de la primera aceptación respecto al inicio del concurso,
 *       más 5 minutos por cada fallo previo a esa aceptación.</li>
 *   <li>Los envíos posteriores a la primera aceptación no penalizan.</li>
 *   <li>Problemas no resueltos no suman penalización.</li>
 *   <li>Orden mínimo: (1) problemas resueltos DESC, (2) penalización total ASC,
 *       (3) desempate por tiempo con la hora del último envío aceptado ASC.
 *       Empates idénticos en todo comparten posición.</li>
 *   <li>Aviso a organizadores si el retraso de envíos sin evaluar supera 5 minutos (300 s).</li>
 * </ul>
 *
 * <p>Dueño de datos (PRD sección 6): en la etapa 1 la tabla de posiciones no escribe
 * ninguna tabla, solo lee {@code envios}, {@code resultados}, {@code problemas},
 * {@code cuentas} y {@code concursos}.
 */
@RestController
@RequestMapping("/api/concursos")
public class TablaPosicionesController {

    private static final Logger log = LoggerFactory.getLogger(TablaPosicionesController.class);
    private static final long LIMITE_RETRASO_SEGUNDOS = 300; // 5 minutos

    private final JdbcTemplate jdbc;

    public TablaPosicionesController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record ConcursoInfo(Long id, String nombre, Instant inicio, Instant fin) {}
    record ProblemaInfo(Long id, String letra, String titulo, int puntosBase) {}
    record CuentaInfo(Long id, String usuario, String nombre) {}
    record EnvioEvaluado(Long id, Long cuentaId, Long problemaId, Instant llegadaEn, String veredicto) {}

    static class EstadoParticipante {
        final CuentaInfo cuenta;
        int resueltos = 0;
        long penalizacionTotal = 0;
        Instant tiempoUltimoAceptado = null;
        int posicion = 1;
        final Map<String, String> detallePorProblema = new LinkedHashMap<>();

        EstadoParticipante(CuentaInfo cuenta) {
            this.cuenta = cuenta;
        }
    }

    @GetMapping("/activo/tabla")
    public ResponseEntity<TablaPosicionesResponse> obtenerTablaProvisional() {
        // 1. Obtener el concurso activo
        List<ConcursoInfo> concursos = jdbc.query(
                "SELECT id, nombre, inicio, fin FROM concursos WHERE estado = 'ABIERTO' ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> {
                    Timestamp tsInicio = rs.getTimestamp("inicio");
                    Timestamp tsFin = rs.getTimestamp("fin");
                    return new ConcursoInfo(
                            rs.getLong("id"),
                            rs.getString("nombre"),
                            tsInicio != null ? tsInicio.toInstant() : null,
                            tsFin != null ? tsFin.toInstant() : null);
                });

        if (concursos.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No hay ningún concurso abierto");
        }

        ConcursoInfo concurso = concursos.get(0);
        Instant inicioConcurso = concurso.inicio() != null ? concurso.inicio() : Instant.now();

        // 2. Revisar retraso de envíos sin evaluar para aviso a organizadores (> 5 min)
        List<Timestamp> enviosPendientes = jdbc.query(
                "SELECT MIN(e.llegada_en) AS min_llegada "
                        + "FROM envios e "
                        + "JOIN problemas p ON e.problema_id = p.id "
                        + "WHERE p.concurso_id = ? "
                        + "  AND NOT EXISTS (SELECT 1 FROM resultados r WHERE r.envio_id = e.id)",
                (rs, rowNum) -> rs.getTimestamp("min_llegada"),
                concurso.id());

        long retrasoSegundos = 0;
        boolean avisoOrganizadores = false;
        if (!enviosPendientes.isEmpty() && enviosPendientes.get(0) != null) {
            Instant llegadaMasAntigua = enviosPendientes.get(0).toInstant();
            retrasoSegundos = Math.max(0, Duration.between(llegadaMasAntigua, Instant.now()).toSeconds());
            if (retrasoSegundos > LIMITE_RETRASO_SEGUNDOS) {
                avisoOrganizadores = true;
                log.warn("AVISO A ORGANIZADORES: El marcador provisional tiene un retraso de {} segundos (> 5 min) en el concurso {}",
                        retrasoSegundos, concurso.id());
            }
        }

        // 3. Problemas del concurso
        List<ProblemaInfo> problemas = jdbc.query(
                "SELECT id, letra, titulo, puntos_base FROM problemas WHERE concurso_id = ? ORDER BY letra ASC",
                (rs, rowNum) -> new ProblemaInfo(
                        rs.getLong("id"),
                        rs.getString("letra"),
                        rs.getString("titulo"),
                        rs.getInt("puntos_base")),
                concurso.id());

        // 4. Cuentas registradas (todos los participantes)
        List<CuentaInfo> cuentas = jdbc.query(
                "SELECT id, usuario, nombre FROM cuentas ORDER BY id ASC",
                (rs, rowNum) -> new CuentaInfo(
                        rs.getLong("id"),
                        rs.getString("usuario"),
                        rs.getString("nombre")));

        // 5. Envíos evaluados del concurso
        List<EnvioEvaluado> envios = jdbc.query(
                "SELECT e.id, e.cuenta_id, e.problema_id, e.llegada_en, r.veredicto "
                        + "FROM envios e "
                        + "JOIN resultados r ON r.envio_id = e.id "
                        + "JOIN problemas p ON p.id = e.problema_id "
                        + "WHERE p.concurso_id = ? "
                        + "ORDER BY e.llegada_en ASC, e.id ASC",
                (rs, rowNum) -> {
                    Timestamp tsLlegada = rs.getTimestamp("llegada_en");
                    return new EnvioEvaluado(
                            rs.getLong("id"),
                            rs.getLong("cuenta_id"),
                            rs.getLong("problema_id"),
                            tsLlegada != null ? tsLlegada.toInstant() : Instant.now(),
                            rs.getString("veredicto"));
                },
                concurso.id());

        // Agrupar envíos por cuentaId y problemaId
        Map<Long, Map<Long, List<EnvioEvaluado>>> enviosPorCuentaYProblema = new HashMap<>();
        for (EnvioEvaluado e : envios) {
            enviosPorCuentaYProblema
                    .computeIfAbsent(e.cuentaId(), k -> new HashMap<>())
                    .computeIfAbsent(e.problemaId(), k -> new ArrayList<>())
                    .add(e);
        }

        // 6. Calcular estadísticas para cada participante
        List<EstadoParticipante> participantes = new ArrayList<>();
        for (CuentaInfo cuenta : cuentas) {
            EstadoParticipante estado = new EstadoParticipante(cuenta);
            Map<Long, List<EnvioEvaluado>> enviosDeCuenta = enviosPorCuentaYProblema.getOrDefault(cuenta.id(), Map.of());

            for (ProblemaInfo problema : problemas) {
                List<EnvioEvaluado> enviosProblema = enviosDeCuenta.getOrDefault(problema.id(), List.of());

                int fallosPrevios = 0;
                boolean resuelto = false;
                long minutoAceptacion = 0;
                long penalizacionProblema = 0;

                for (EnvioEvaluado envio : enviosProblema) {
                    if (resuelto) {
                        // Los envíos posteriores a la primera aceptación no penalizan
                        break;
                    }

                    if ("ACCEPTED".equalsIgnoreCase(envio.veredicto())) {
                        resuelto = true;
                        minutoAceptacion = Math.max(0, Duration.between(inicioConcurso, envio.llegadaEn()).toMinutes());
                        penalizacionProblema = minutoAceptacion + (fallosPrevios * 5L);

                        if (estado.tiempoUltimoAceptado == null || envio.llegadaEn().isAfter(estado.tiempoUltimoAceptado)) {
                            estado.tiempoUltimoAceptado = envio.llegadaEn();
                        }
                        break;
                    } else {
                        fallosPrevios++;
                    }
                }

                if (resuelto) {
                    estado.resueltos++;
                    estado.penalizacionTotal += penalizacionProblema;
                    estado.detallePorProblema.put(problema.letra(), String.format("✔ (+%d, %dm)", fallosPrevios, minutoAceptacion));
                } else if (fallosPrevios > 0) {
                    estado.detallePorProblema.put(problema.letra(), String.format("-%d", fallosPrevios));
                } else {
                    estado.detallePorProblema.put(problema.letra(), "-");
                }
            }

            participantes.add(estado);
        }

        // 7. Ordenar participantes según la regla de la bitácora 6
        Comparator<EstadoParticipante> comparador = (a, b) -> {
            // (1) Mayor número de resueltos
            int c = Integer.compare(b.resueltos, a.resueltos);
            if (c != 0) return c;

            // (2) Menor penalización total
            c = Long.compare(a.penalizacionTotal, b.penalizacionTotal);
            if (c != 0) return c;

            // (3) Desempate por tiempo: tiempo del último envío aceptado más temprano
            if (a.tiempoUltimoAceptado != null && b.tiempoUltimoAceptado != null) {
                c = a.tiempoUltimoAceptado.compareTo(b.tiempoUltimoAceptado);
                if (c != 0) return c;
            } else if (a.tiempoUltimoAceptado != null) {
                return -1;
            } else if (b.tiempoUltimoAceptado != null) {
                return 1;
            }

            // Empate absoluto
            return 0;
        };

        participantes.sort(comparador);

        // 8. Asignar posiciones con empates compartidos
        for (int i = 0; i < participantes.size(); i++) {
            EstadoParticipante actual = participantes.get(i);
            if (i > 0) {
                EstadoParticipante anterior = participantes.get(i - 1);
                if (comparador.compare(anterior, actual) == 0) {
                    actual.posicion = anterior.posicion;
                } else {
                    actual.posicion = i + 1;
                }
            } else {
                actual.posicion = 1;
            }
        }

        // 9. Construir las filas para la respuesta (orden e inserción consistentes)
        List<Map<String, Object>> filas = new ArrayList<>();
        for (EstadoParticipante p : participantes) {
            Map<String, Object> fila = new LinkedHashMap<>();
            fila.put("posicion", p.posicion);
            fila.put("usuario", p.cuenta.usuario());
            fila.put("nombre", p.cuenta.nombre());
            fila.put("resueltos", p.resueltos);
            fila.put("penalizacion", p.penalizacionTotal);
            for (ProblemaInfo prob : problemas) {
                fila.put(prob.letra(), p.detallePorProblema.getOrDefault(prob.letra(), "-"));
            }
            filas.add(fila);
        }

        return ResponseEntity.ok(new TablaPosicionesResponse(
                concurso.id(),
                concurso.nombre(),
                retrasoSegundos,
                avisoOrganizadores,
                filas));
    }
}
