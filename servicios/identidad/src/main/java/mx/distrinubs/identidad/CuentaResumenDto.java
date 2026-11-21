package mx.distrinubs.identidad;

public record CuentaResumenDto(
        long id,
        String usuario,
        String nombre,
        String rol,
        boolean bloqueada
) {}
