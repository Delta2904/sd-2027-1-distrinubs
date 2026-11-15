package mx.distrinubs.observabilidad;

import java.time.Instant;
import java.util.List;

/**
 * Representa la vista de estado de los servicios y componentes de la plataforma (Paso 7).
 */
public record EstadoServiciosResponse(
        Instant timestamp,
        String estadoGlobal,
        List<ServicioDetalle> servicios,
        MetricasSistema metricas
) {
    public record ServicioDetalle(
            String nombre,
            String tipo,
            String estado,
            int instancias,
            long latenciaMs,
            String detalle
    ) {
    }

    public record MetricasSistema(
            int enviosEnCola,
            int leasesActivos,
            int totalCuentas,
            String concursoActivo
    ) {
    }
}
