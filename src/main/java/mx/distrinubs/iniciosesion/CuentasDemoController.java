package mx.distrinubs.iniciosesion;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lista las cuentas de demo para que el front elija al alumno.
 *
 * <p>En 01 no hay inicio de sesión: la identidad de demo no tiene contraseña ni
 * token (riesgo declarado en el README). Este controlador solo lee {@code cuentas};
 * las escribe la siembra ({@code scripts/siembra-cuentas.sql}).
 */
@RestController
public class CuentasDemoController {

    /** Forma del JSON que ve el front. */
    public record CuentaDemo(long id, String usuario, String nombre) {
    }

    private final JdbcTemplate jdbc;

    public CuentasDemoController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/cuentas-demo")
    public List<CuentaDemo> listar() {
        return jdbc.query(
                "SELECT id, usuario, nombre FROM cuentas ORDER BY id",
                (rs, fila) -> new CuentaDemo(
                        rs.getLong("id"),
                        rs.getString("usuario"),
                        rs.getString("nombre")));
    }
}
