package mx.distrinubs;

import java.io.IOException;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * PostgreSQL real, embebido, para las pruebas que dependen del comportamiento
 * de la base: unicidad, locks y concurrencia. Arranca una sola vez por corrida
 * y aplica las mismas migraciones de Flyway que la aplicación.
 */
public final class BaseDatosDePrueba {

    public static final long CUENTA = 1;
    public static final long PROBLEMA = 1;

    private static JdbcTemplate jdbc;

    private BaseDatosDePrueba() {
    }

    public static synchronized JdbcTemplate jdbc() {
        if (jdbc == null) {
            try {
                DataSource fuente = EmbeddedPostgres.builder().start().getPostgresDatabase();
                Flyway.configure().dataSource(fuente).locations("classpath:db/migration")
                        .outOfOrder(true).load().migrate();
                jdbc = new JdbcTemplate(fuente);
            } catch (IOException e) {
                throw new IllegalStateException("No arrancó el PostgreSQL embebido", e);
            }
        }
        return jdbc;
    }

    /** Deja todas las tablas vacías. */
    public static void vaciar() {
        jdbc().execute("TRUNCATE resultados, leases_evaluacion, envios, casos_prueba, problemas, "
                + "concursos, cuentas RESTART IDENTITY CASCADE");
    }

    /** Una cuenta, un concurso abierto y un problema con un caso de prueba. */
    public static void sembrarMinimo(int limiteTiempoMs) {
        JdbcTemplate j = jdbc();
        j.update("INSERT INTO cuentas (id, usuario, nombre) VALUES (?, 'prueba', 'Cuenta de prueba')", CUENTA);
        j.update("INSERT INTO concursos (id, nombre, inicio, fin, estado) "
                + "VALUES (1, 'Concurso de prueba', now() - interval '1 hour', now() + interval '1 hour', 'ABIERTO')");
        j.update("INSERT INTO problemas (id, concurso_id, letra, titulo, enunciado, limite_tiempo_ms, "
                + "limite_memoria_mb, puntos_base) VALUES (?, 1, 'A', 'Suma', 'Suma dos enteros', ?, 256, 100)",
                PROBLEMA, limiteTiempoMs);
        j.update("INSERT INTO casos_prueba (id, problema_id, orden, entrada, salida_esperada, "
                + "en_marcador_provisional) VALUES (1, ?, 1, '2 3', '5', TRUE)", PROBLEMA);
    }

    /** Inserta un envío en cola y devuelve su id. */
    public static long encolarEnvio() {
        return jdbc().queryForObject(
                "INSERT INTO envios (id_intento, cuenta_id, problema_id, lenguaje, codigo, estado) "
                        + "VALUES (?, ?, ?, 'PYTHON3', 'print(5)', 'EN_COLA') RETURNING id",
                Long.class, UUID.randomUUID(), CUENTA, PROBLEMA);
    }
}
