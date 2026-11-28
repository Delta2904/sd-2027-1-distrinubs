package mx.distrinubs.envios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.sql.Timestamp;
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
        when(publicadorEnvios.publicar(anyLong())).thenReturn(true);
        barrido = new BarridoRepublicacionEnvios(jdbc, publicadorEnvios, 10, 300, 3, 3, 50);
    }

    private long insertarEnvio(int antiguedadSegundos, Integer publicadoHaceSeg, int vecesPublicado) {
        if (publicadoHaceSeg == null) {
            return jdbc.queryForObject(
                    "INSERT INTO envios (id_intento, cuenta_id, problema_id, lenguaje, codigo, llegada_en, "
                            + "publicado_en, veces_publicado, estado) "
                            + "VALUES (?, 1, 1, 'PYTHON3', 'print(5)', now() - (? * interval '1 second'), "
                            + "NULL, ?, 'EN_COLA') RETURNING id",
                    Long.class,
                    UUID.randomUUID(),
                    antiguedadSegundos,
                    vecesPublicado);
        }
        return jdbc.queryForObject(
                "INSERT INTO envios (id_intento, cuenta_id, problema_id, lenguaje, codigo, llegada_en, "
                        + "publicado_en, veces_publicado, estado) "
                        + "VALUES (?, 1, 1, 'PYTHON3', 'print(5)', now() - (? * interval '1 second'), "
                        + "now() - (? * interval '1 second'), ?, 'EN_COLA') RETURNING id",
                Long.class,
                UUID.randomUUID(),
                antiguedadSegundos,
                publicadoHaceSeg,
                vecesPublicado);
    }

    @Test
    @DisplayName("Republica solo envíos vencidos sin resultado, dentro del tope de publicaciones y tomas, sin duplicar")
    void republicaSoloPendientesElegiblesSinDuplicar() {
        // (a) nunca publicado de 30 s: se republica
        long nuncaPublicadoAntiguo = insertarEnvio(30, null, 0);
        // (b) de 1 s: dentro del margen de gracia de 10 s, no se republica
        insertarEnvio(1, null, 0);
        // (c) publicado hace 30 s (espera menor a 300 s): no se republica
        insertarEnvio(40, 30, 1);
        // (d) publicado hace 400 s: supera espera máxima, se republica
        long publicadoVencido = insertarEnvio(500, 400, 1);

        // (e) lease vigente: no se republica
        long conLeaseVigente = insertarEnvio(30, null, 1);
        jdbc.update("INSERT INTO leases_evaluacion (envio_id, worker, tomado_en, vence_en, veces_tomado) "
                + "VALUES (?, 'worker-1', now() - interval '5 seconds', now() + interval '30 seconds', 1)",
                conLeaseVigente);

        // (f) lease vencido con veces_tomado = 3: tomas agotadas, no se republica
        long conTomasAgotadas = insertarEnvio(30, null, 1);
        jdbc.update("INSERT INTO leases_evaluacion (envio_id, worker, tomado_en, vence_en, veces_tomado) "
                + "VALUES (?, 'worker-2', now() - interval '40 seconds', now() - interval '10 seconds', 3)",
                conTomasAgotadas);

        // (g) lease vencido con veces_tomado = 1 y nunca publicado: se republica
        long conLeaseVencido = insertarEnvio(30, null, 0);
        jdbc.update("INSERT INTO leases_evaluacion (envio_id, worker, tomado_en, vence_en, veces_tomado) "
                + "VALUES (?, 'worker-3', now() - interval '25 seconds', now() - interval '5 seconds', 1)",
                conLeaseVencido);

        // (h) con resultado: no se republica
        long evaluado = insertarEnvio(30, null, 1);
        jdbc.update("INSERT INTO resultados (envio_id, veredicto, tiempo_ms) VALUES (?, 'ACCEPTED', 12)",
                evaluado);

        // (i) veces_publicado = 3 y publicado hace 400 s: tope de publicaciones alcanzado, no se republica
        insertarEnvio(500, 400, 3);

        int republicados = barrido.republicarPendientes();

        assertEquals(3, republicados);
        verify(publicadorEnvios).publicar(nuncaPublicadoAntiguo);
        verify(publicadorEnvios).publicar(publicadoVencido);
        verify(publicadorEnvios).publicar(conLeaseVencido);
        verifyNoMoreInteractions(publicadorEnvios);

        assertEquals(1, jdbc.queryForObject(
                "SELECT veces_publicado FROM envios WHERE id = ?", Integer.class, nuncaPublicadoAntiguo));
        assertNotNull(jdbc.queryForObject(
                "SELECT publicado_en FROM envios WHERE id = ?", Timestamp.class, nuncaPublicadoAntiguo));
        assertEquals(2, jdbc.queryForObject(
                "SELECT veces_publicado FROM envios WHERE id = ?", Integer.class, publicadoVencido));
        assertEquals(1, jdbc.queryForObject(
                "SELECT veces_publicado FROM envios WHERE id = ?", Integer.class, conLeaseVencido));

        // Una segunda corrida inmediata no debe republicar ningún envío
        int segundaCorrida = barrido.republicarPendientes();
        assertEquals(0, segundaCorrida);
        verifyNoMoreInteractions(publicadorEnvios);
    }

    @Test
    @DisplayName("Si RabbitMQ rechaza la publicación, corta el lote y devuelve publicado_en a su valor anterior")
    void liberaReclamoSiBrokerFalla() {
        when(publicadorEnvios.publicar(anyLong())).thenReturn(false);

        long primero = insertarEnvio(30, null, 0);
        long segundo = insertarEnvio(500, 400, 1);
        Timestamp publicadoAntes = jdbc.queryForObject(
                "SELECT publicado_en FROM envios WHERE id = ?", Timestamp.class, segundo);

        int publicados = barrido.republicarPendientes();

        assertEquals(0, publicados);
        verify(publicadorEnvios, times(1)).publicar(anyLong());
        assertNull(jdbc.queryForObject(
                "SELECT publicado_en FROM envios WHERE id = ?", Timestamp.class, primero));
        assertEquals(publicadoAntes, jdbc.queryForObject(
                "SELECT publicado_en FROM envios WHERE id = ?", Timestamp.class, segundo));
        assertEquals(0, jdbc.queryForObject(
                "SELECT veces_publicado FROM envios WHERE id = ?", Integer.class, primero));
        assertEquals(1, jdbc.queryForObject(
                "SELECT veces_publicado FROM envios WHERE id = ?", Integer.class, segundo));
    }

    @Test
    @DisplayName("Si un envío lanza una excepción, se libera su reclamo y los demás del lote se publican")
    void excepcionEnUnEnvioNoDejaReclamadosALosDemas() {
        long nuncaPublicado = insertarEnvio(30, null, 0);
        long yaPublicado = insertarEnvio(500, 400, 1);
        long tercero = insertarEnvio(40, null, 0);
        Timestamp publicadoAntes = jdbc.queryForObject(
                "SELECT publicado_en FROM envios WHERE id = ?", Timestamp.class, yaPublicado);

        when(publicadorEnvios.publicar(nuncaPublicado)).thenThrow(new IllegalStateException("falla al publicar"));
        when(publicadorEnvios.publicar(yaPublicado)).thenThrow(new IllegalStateException("falla al publicar"));

        int publicados = barrido.republicarPendientes();

        // El tercero se publica aunque los dos primeros hayan fallado.
        assertEquals(1, publicados);
        verify(publicadorEnvios).publicar(tercero);
        assertNotNull(jdbc.queryForObject(
                "SELECT publicado_en FROM envios WHERE id = ?", Timestamp.class, tercero));
        assertEquals(1, jdbc.queryForObject(
                "SELECT veces_publicado FROM envios WHERE id = ?", Integer.class, tercero));

        // Los que fallaron no se quedan reclamados: vuelven a como estaban.
        assertNull(jdbc.queryForObject(
                "SELECT publicado_en FROM envios WHERE id = ?", Timestamp.class, nuncaPublicado));
        assertEquals(publicadoAntes, jdbc.queryForObject(
                "SELECT publicado_en FROM envios WHERE id = ?", Timestamp.class, yaPublicado));
        assertEquals(0, jdbc.queryForObject(
                "SELECT veces_publicado FROM envios WHERE id = ?", Integer.class, nuncaPublicado));
        assertEquals(1, jdbc.queryForObject(
                "SELECT veces_publicado FROM envios WHERE id = ?", Integer.class, yaPublicado));

        // Por eso el siguiente barrido los vuelve a tomar sin esperar la espera máxima en cola.
        when(publicadorEnvios.publicar(anyLong())).thenReturn(true);
        assertEquals(2, barrido.republicarPendientes());
    }

    @Test
    @DisplayName("La migración permite insertar envíos con los lenguajes ampliados (CPP, C, JAVA, JAVASCRIPT)")
    void permiteLenguajesAmpliadosTrasMigracion() {
        Long idCpp = jdbc.queryForObject(
                "INSERT INTO envios (id_intento, cuenta_id, problema_id, lenguaje, codigo, estado) "
                        + "VALUES (?, 1, 1, 'CPP', 'int main(){}', 'EN_COLA') RETURNING id",
                Long.class,
                UUID.randomUUID());
        assertNotNull(idCpp);
    }
}
