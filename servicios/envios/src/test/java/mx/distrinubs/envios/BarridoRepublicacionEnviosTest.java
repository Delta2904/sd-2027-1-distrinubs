package mx.distrinubs.envios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.IOException;
import java.util.UUID;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

class BarridoRepublicacionEnviosTest {

    private static JdbcTemplate jdbcCompartido;

    private JdbcTemplate jdbc;
    private PublicadorEnvios publicadorEnvios;
    private BarridoRepublicacionEnvios barrido;

    private static synchronized JdbcTemplate obtenerJdbc() {
        if (jdbcCompartido == null) {
            try {
                DataSource fuente = EmbeddedPostgres.builder().start().getPostgresDatabase();
                Flyway.configure()
                        .dataSource(fuente)
                        .locations("classpath:db/migration")
                        .outOfOrder(true)
                        .load()
                        .migrate();
                jdbcCompartido = new JdbcTemplate(fuente);
            } catch (IOException e) {
                throw new IllegalStateException("No arrancó PostgreSQL embebido", e);
            }
        }
        return jdbcCompartido;
    }

    @BeforeEach
    void preparar() {
        jdbc = obtenerJdbc();
        jdbc.execute("TRUNCATE tokens_revocados, resultados, leases_evaluacion, envios, "
                + "casos_prueba, problemas, concursos, cuentas RESTART IDENTITY CASCADE");

        jdbc.update("INSERT INTO cuentas (id, usuario, nombre, rol) VALUES (1, 'alumno1', 'Alumno Uno', 'ALUMNO')");
        jdbc.update("INSERT INTO concursos (id, nombre, inicio, fin, estado) "
                + "VALUES (1, 'Distrinubs Round 1', now() - interval '1 hour', now() + interval '1 hour', 'ABIERTO')");
        jdbc.update("INSERT INTO problemas (id, concurso_id, letra, titulo, enunciado, limite_tiempo_ms, "
                + "limite_memoria_mb, puntos_base) VALUES (1, 1, 'A', 'Suma', 'Suma dos enteros', 1000, 256, 100)");

        publicadorEnvios = mock(PublicadorEnvios.class);
        barrido = new BarridoRepublicacionEnvios(jdbc, publicadorEnvios, 10, 50);
    }

    private long insertarEnvio(int antiguedadSegundos) {
        return jdbc.queryForObject(
                "INSERT INTO envios (id_intento, cuenta_id, problema_id, lenguaje, codigo, llegada_en, estado) "
                        + "VALUES (?, 1, 1, 'PYTHON3', 'print(5)', now() - (? * interval '1 second'), 'EN_COLA') "
                        + "RETURNING id",
                Long.class,
                UUID.randomUUID(),
                antiguedadSegundos);
    }

    @Test
    @DisplayName("Republica envíos EN_COLA sin resultado ni lease vigente después del margen de gracia")
    void republicaSoloPendientesSinLeaseNiResultado() {
        long pendienteAntiguo = insertarEnvio(30);
        insertarEnvio(1); // dentro del margen de gracia de 10 s

        long conLeaseVigente = insertarEnvio(30);
        jdbc.update("INSERT INTO leases_evaluacion (envio_id, worker, tomado_en, vence_en, veces_tomado) "
                + "VALUES (?, 'worker-1', now() - interval '5 seconds', now() + interval '30 seconds', 1)",
                conLeaseVigente);

        long conLeaseVencido = insertarEnvio(30);
        jdbc.update("INSERT INTO leases_evaluacion (envio_id, worker, tomado_en, vence_en, veces_tomado) "
                + "VALUES (?, 'worker-2', now() - interval '25 seconds', now() - interval '5 seconds', 1)",
                conLeaseVencido);

        long evaluado = insertarEnvio(30);
        jdbc.update("INSERT INTO resultados (envio_id, veredicto, tiempo_ms) VALUES (?, 'ACCEPTED', 12)",
                evaluado);

        int republicados = barrido.republicarPendientes();

        assertEquals(2, republicados);
        verify(publicadorEnvios).publicar(pendienteAntiguo);
        verify(publicadorEnvios).publicar(conLeaseVencido);
        verifyNoMoreInteractions(publicadorEnvios);
    }
}
