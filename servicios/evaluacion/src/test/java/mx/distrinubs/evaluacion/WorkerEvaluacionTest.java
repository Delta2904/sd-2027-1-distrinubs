package mx.distrinubs.evaluacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import mx.distrinubs.BaseDatosDePrueba;
import mx.distrinubs.evaluacion.WorkerEvaluacion.Tipo;

/**
 * El worker evalúa el envío que trae un mensaje, contra un PostgreSQL real:
 * lease por envío, resultado único y tope de tomas.
 */
class WorkerEvaluacionTest {

    private static final int MAX_TOMAS = 3;
    /** Límite de tiempo del problema; con un caso y sin margen, el lease dura esto. */
    private static final int LEASE_MS = 150;

    private JdbcTemplate jdbc;

    @BeforeEach
    void preparar() {
        jdbc = BaseDatosDePrueba.jdbc();
        BaseDatosDePrueba.vaciar();
        BaseDatosDePrueba.sembrarMinimo(LEASE_MS);
    }

    private WorkerEvaluacion worker(EjecutorPython ejecutor) {
        return new WorkerEvaluacion(jdbc, ejecutor, 0, MAX_TOMAS);
    }

    @Test
    @DisplayName("Evalúa el envío del mensaje y guarda su veredicto")
    void evaluaYGuarda() {
        long envio = BaseDatosDePrueba.encolarEnvio();

        assertEquals(Tipo.EVALUADO, worker(new EjecutoresFalsos.QueFalla(0)).procesar(envio, "w1").tipo());

        assertEquals("ACCEPTED", veredicto(envio));
        assertEquals(1, vecesTomado(envio));
    }

    @Test
    @DisplayName("Un mensaje duplicado de un envío ya evaluado no lo evalúa otra vez")
    void duplicadoNoReevalua() {
        long envio = BaseDatosDePrueba.encolarEnvio();
        EjecutoresFalsos.QueFalla ejecutor = new EjecutoresFalsos.QueFalla(0);
        worker(ejecutor).procesar(envio, "w1");

        assertEquals(Tipo.YA_RESUELTO, worker(ejecutor).procesar(envio, "w2").tipo());
        assertEquals(1, ejecutor.llamadas.get());
    }

    @Test
    @DisplayName("Con el lease vigente de otro worker no lo toca; al vencer, lo retoma como toma 2")
    void leaseAjenoSeRespetaYLuegoSeRetoma() throws Exception {
        long envio = BaseDatosDePrueba.encolarEnvio();
        tomarComoWorkerCaido(envio, 1, 300);
        EjecutoresFalsos.QueFalla ejecutor = new EjecutoresFalsos.QueFalla(0);

        WorkerEvaluacion.Desenlace desenlace = worker(ejecutor).procesar(envio, "w2");
        assertEquals(Tipo.LEASE_AJENO, desenlace.tipo());
        assertTrue(desenlace.esperaMs() > 0 && desenlace.esperaMs() <= 300, "espera: " + desenlace.esperaMs());
        assertEquals(0, ejecutor.llamadas.get());

        Thread.sleep(desenlace.esperaMs() + 50);
        assertEquals(Tipo.EVALUADO, worker(ejecutor).procesar(envio, "w2").tipo());
        assertEquals(2, vecesTomado(envio));
        assertEquals("ACCEPTED", veredicto(envio));
    }

    @Test
    @DisplayName("Una falla del servidor libera el lease al momento; a la tercera queda ERROR_SERVIDOR")
    void fallaLiberaYAgotaEnLaTercera() {
        long envio = BaseDatosDePrueba.encolarEnvio();
        EjecutoresFalsos.QueFalla ejecutor = new EjecutoresFalsos.QueFalla(Integer.MAX_VALUE);
        WorkerEvaluacion w = worker(ejecutor);

        // Sin esperar al lease: cada falla lo deja vencido para el siguiente worker.
        assertEquals(Tipo.FALLA_SERVIDOR, w.procesar(envio, "w1").tipo());
        assertEquals(Tipo.FALLA_SERVIDOR, w.procesar(envio, "w2").tipo());
        assertEquals(Tipo.AGOTADO, w.procesar(envio, "w3").tipo());
        assertEquals(Tipo.YA_RESUELTO, w.procesar(envio, "w4").tipo());

        assertEquals(MAX_TOMAS, ejecutor.llamadas.get(), "se evaluó más veces que el tope");
        assertEquals(MAX_TOMAS, vecesTomado(envio));
        assertEquals(List.of("ERROR_SERVIDOR"), jdbc.queryForList(
                "SELECT veredicto FROM resultados WHERE envio_id = ?", String.class, envio));
    }

    @Test
    @DisplayName("Si una toma dentro del tope sí evalúa, el envío recibe su veredicto normal")
    void unaTomaQueFuncionaGuardaElVeredicto() {
        long envio = BaseDatosDePrueba.encolarEnvio();
        WorkerEvaluacion w = worker(new EjecutoresFalsos.QueFalla(MAX_TOMAS - 1));

        w.procesar(envio, "w1");
        w.procesar(envio, "w2");
        assertEquals(Tipo.EVALUADO, w.procesar(envio, "w3").tipo());
        assertEquals("ACCEPTED", veredicto(envio));
    }

    @Test
    @DisplayName("Si el worker murió en la última toma, el siguiente mensaje cierra con ERROR_SERVIDOR sin evaluar")
    void ultimaTomaVencidaCierraSinEvaluar() {
        long envio = BaseDatosDePrueba.encolarEnvio();
        tomarComoWorkerCaido(envio, MAX_TOMAS, -1000);
        EjecutoresFalsos.QueFalla ejecutor = new EjecutoresFalsos.QueFalla(0);

        assertEquals(Tipo.AGOTADO, worker(ejecutor).procesar(envio, "w2").tipo());
        assertEquals(0, ejecutor.llamadas.get());
        assertEquals("ERROR_SERVIDOR", veredicto(envio));
    }

    @Test
    @DisplayName("Un envío que no existe no se evalúa")
    void sinEnvio() {
        assertEquals(Tipo.SIN_ENVIO, worker(new EjecutoresFalsos.QueFalla(0)).procesar(999_999, "w1").tipo());
    }

    @Test
    @DisplayName("El barrido cierra los envíos sin mensaje que agotaron sus tomas y no toca a los demás")
    void barridoCierraHuerfanos() {
        long huerfano = BaseDatosDePrueba.encolarEnvio();
        tomarComoWorkerCaido(huerfano, MAX_TOMAS, -1000);
        long vigente = BaseDatosDePrueba.encolarEnvio();
        tomarComoWorkerCaido(vigente, MAX_TOMAS, 60_000);

        assertEquals(1, worker(new EjecutoresFalsos.QueFalla(0)).agotarReintentos());
        assertEquals("ERROR_SERVIDOR", veredicto(huerfano));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM resultados WHERE envio_id = ?", Integer.class, vigente));
    }

    /** Deja un lease de otro worker que vence en {@code venceEnMs} (negativo: ya vencido). */
    private void tomarComoWorkerCaido(long envio, int vecesTomado, int venceEnMs) {
        jdbc.update("INSERT INTO leases_evaluacion (envio_id, worker, tomado_en, vence_en, veces_tomado) "
                + "VALUES (?, 'worker-caido', now() - interval '1 hour', now() + ? * interval '1 millisecond', ?)",
                envio, venceEnMs, vecesTomado);
    }

    private String veredicto(long envio) {
        return jdbc.queryForObject("SELECT veredicto FROM resultados WHERE envio_id = ?", String.class, envio);
    }

    private int vecesTomado(long envio) {
        return jdbc.queryForObject("SELECT veces_tomado FROM leases_evaluacion WHERE envio_id = ?",
                Integer.class, envio);
    }
}
