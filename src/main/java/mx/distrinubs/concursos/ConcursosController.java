package mx.distrinubs.concursos;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Controlador REST para el módulo de Concursos y problemas (etapa 01-monolito).
 *
 * <p>Responsable de:
 * <ul>
 *   <li>{@code GET /api/concursos/activo}: Devuelve el concurso abierto con sus problemas:
 *       letra, título, enunciado y límites de tiempo y memoria.</li>
 * </ul>
 *
 * <p>Dueño de datos (PRD sección 6): este módulo es dueño de {@code concursos},
 * {@code problemas} y {@code casos_prueba}.
 */
@RestController
@RequestMapping("/api/concursos")
public class ConcursosController {

    private final JdbcTemplate jdbc;

    public ConcursosController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Consulta el concurso actualmente abierto y la lista de sus problemas ordenados por letra.
     */
    @GetMapping("/activo")
    public ResponseEntity<ConcursoActivoResponse> obtenerConcursoActivo() {
        record ConcursoFila(Long id, String nombre, Instant inicio, Instant fin, String estado) {}

        List<ConcursoFila> concursos = jdbc.query(
                "SELECT id, nombre, inicio, fin, estado FROM concursos WHERE estado = 'ABIERTO' ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> {
                    Timestamp tsInicio = rs.getTimestamp("inicio");
                    Timestamp tsFin = rs.getTimestamp("fin");
                    return new ConcursoFila(
                            rs.getLong("id"),
                            rs.getString("nombre"),
                            tsInicio != null ? tsInicio.toInstant() : null,
                            tsFin != null ? tsFin.toInstant() : null,
                            rs.getString("estado"));
                });

        if (concursos.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No hay ningún concurso abierto");
        }

        ConcursoFila activo = concursos.get(0);

        List<ProblemaResumenDto> problemas = jdbc.query(
                "SELECT id, letra, titulo, enunciado, limite_tiempo_ms, limite_memoria_mb, puntos_base "
                        + "FROM problemas WHERE concurso_id = ? ORDER BY letra ASC",
                (rs, rowNum) -> new ProblemaResumenDto(
                        rs.getLong("id"),
                        rs.getString("letra"),
                        rs.getString("titulo"),
                        rs.getString("enunciado"),
                        rs.getInt("limite_tiempo_ms"),
                        rs.getInt("limite_memoria_mb"),
                        rs.getInt("puntos_base")),
                activo.id());

        return ResponseEntity.ok(new ConcursoActivoResponse(
                activo.id(),
                activo.nombre(),
                activo.inicio(),
                activo.fin(),
                activo.estado(),
                problemas));
    }
}
