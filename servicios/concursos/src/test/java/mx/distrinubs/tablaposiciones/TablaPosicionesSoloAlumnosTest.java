package mx.distrinubs.tablaposiciones;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import mx.distrinubs.BaseDatosDePrueba;

/**
 * La tabla de posiciones contra un PostgreSQL real: solo entran las cuentas de alumno.
 */
class TablaPosicionesSoloAlumnosTest {

    @Test
    @DisplayName("La tabla lista solo a los alumnos: las cuentas de organizador y de administración no aparecen")
    void soloAlumnosEnLaTabla() {
        JdbcTemplate jdbc = BaseDatosDePrueba.jdbc();
        BaseDatosDePrueba.vaciar();
        // El rol lo agrega la migración de `identidad`, que es la dueña de `cuentas`.
        // Aquí se agrega igual para que la consulta corra contra el mismo esquema.
        jdbc.execute("ALTER TABLE cuentas ADD COLUMN IF NOT EXISTS rol VARCHAR(20) NOT NULL DEFAULT 'ALUMNO'");
        BaseDatosDePrueba.sembrarMinimo(1000);
        jdbc.update("INSERT INTO cuentas (id, usuario, nombre, rol) VALUES "
                + "(2, 'alumno2', 'Alumno Dos', 'ALUMNO'), "
                + "(3, 'organizador1', 'Organizador Uno', 'ORGANIZADOR'), "
                + "(4, 'admin', 'Administración', 'ADMINISTRADOR')");

        TablaPosicionesResponse tabla = new TablaPosicionesController(jdbc).obtenerTablaProvisional().getBody();

        List<Object> usuarios = tabla.filas().stream().map(fila -> fila.get("usuario")).toList();
        assertEquals(List.of("prueba", "alumno2"), usuarios);
    }
}
