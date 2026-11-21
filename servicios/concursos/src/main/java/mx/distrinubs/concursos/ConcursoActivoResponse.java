package mx.distrinubs.concursos;

import java.time.Instant;
import java.util.List;

/**
 * Respuesta para el endpoint GET /api/concursos/activo con la información del concurso abierto
 * y su lista ordenada de problemas.
 */
public record ConcursoActivoResponse(
        Long id,
        String nombre,
        Instant inicio,
        Instant fin,
        String estado,
        List<ProblemaResumenDto> problemas) {
}
