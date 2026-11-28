package mx.distrinubs.tablaposiciones;

import java.util.List;
import java.util.Map;

/**
 * Respuesta del endpoint GET /api/concursos/activo/tabla.
 *
 * <p>Contiene los metadatos del concurso, el estado del retraso con aviso a organizadores
 * si supera los 5 minutos, y la lista ordenada de filas del marcador provisional.
 */
public record TablaPosicionesResponse(
        Long concursoId,
        String concursoNombre,
        long retrasoSegundos,
        boolean avisoOrganizadores,
        List<Map<String, Object>> filas) {
}
