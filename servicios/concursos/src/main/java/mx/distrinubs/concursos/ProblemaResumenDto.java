package mx.distrinubs.concursos;

/**
 * Resumen de un problema dentro de un concurso para consumo de clientes y front.
 */
public record ProblemaResumenDto(
        Long id,
        String letra,
        String titulo,
        String enunciado,
        Integer limiteTiempoMs,
        Integer limiteMemoriaMb,
        Integer puntosBase) {
}
