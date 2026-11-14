package mx.distrinubs.evaluacion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import mx.distrinubs.BaseDatosDePrueba;

/**
 * Tope de reintentos, contra un PostgreSQL real: un envío que el servidor no
 * logra evaluar se toma como máximo {@code max-tomas} veces y queda con
 * ERROR_SERVIDOR.
 */
class TopeReintentosTest {

    private static final int MAX_TOMAS = 3;
    /** Límite de tiempo del problema; con un caso y sin margen, el lease dura esto. */
    private static final int LEASE_MS = 150;

    /** Ejecutor falso: falla como servidor las primeras {@code fallas} veces y después acepta. */
    static class EjecutorQueFalla extends EjecutorPython {
        final AtomicInteger llamadas = new AtomicInteger();
        private final int fallas;

        EjecutorQueFalla(int fallas) {
            super("python3", 10_000);
            this.fallas = fallas;
        }

        @Override
        Evaluacion evaluar(String codigo, List<Caso> casos, int limiteTiempoMs) throws FallaServidor {
            if (llamadas.incrementAndGet() <= fallas) {
                throw new FallaServidor("falla simulada", null);
            }
            return new Evaluacion("ACCEPTED", 1, null);
        }
    }

    private JdbcTemplate jdbc;

    @BeforeEach
    void preparar() {
        jdbc = BaseDatosDePrueba.jdbc();
        BaseDatosDePrueba.vaciar();
        BaseDatosDePrueba.sembrarMinimo(LEASE_MS);
    }

    @Test
    @DisplayName("Un envío que siempre falla por el servidor se toma 3 veces y queda con ERROR_SERVIDOR")
    void alAgotarLasTomasQuedaConErrorServidor() throws Exception {
        long envio = BaseDatosDePrueba.encolarEnvio();
        EjecutorQueFalla ejecutor = new EjecutorQueFalla(Integer.MAX_VALUE);
        WorkerEvaluacion worker = new WorkerEvaluacion(jdbc, ejecutor, 0, MAX_TOMAS, "worker-prueba");

        // Más pasadas de las necesarias: después del tope no debe pasar nada más.
        for (int pasada = 0; pasada < MAX_TOMAS + 3; pasada++) {
            worker.revisarCola();
            Thread.sleep(LEASE_MS + 100);
        }

        assertEquals(MAX_TOMAS, ejecutor.llamadas.get(), "se evaluó más veces que el tope");
        assertEquals(MAX_TOMAS, jdbc.queryForObject(
                "SELECT veces_tomado FROM leases_evaluacion WHERE envio_id = ?", Integer.class, envio));
        assertEquals(List.of("ERROR_SERVIDOR"), jdbc.queryForList(
                "SELECT veredicto FROM resultados WHERE envio_id = ?", String.class, envio));
    }

    @Test
    @DisplayName("Si una toma dentro del tope sí evalúa, el envío recibe su veredicto normal")
    void siUnaTomaFuncionaGuardaElVeredicto() throws Exception {
        long envio = BaseDatosDePrueba.encolarEnvio();
        EjecutorQueFalla ejecutor = new EjecutorQueFalla(MAX_TOMAS - 1);
        WorkerEvaluacion worker = new WorkerEvaluacion(jdbc, ejecutor, 0, MAX_TOMAS, "worker-prueba");

        for (int pasada = 0; pasada < MAX_TOMAS + 3; pasada++) {
            worker.revisarCola();
            Thread.sleep(LEASE_MS + 100);
        }

        assertEquals(MAX_TOMAS, ejecutor.llamadas.get());
        assertEquals(List.of("ACCEPTED"), jdbc.queryForList(
                "SELECT veredicto FROM resultados WHERE envio_id = ?", String.class, envio));
    }

    @Test
    @DisplayName("El tope no toca a los demás envíos: se siguen evaluando")
    void elRestoDeLaColaSigue() throws Exception {
        long atorado = BaseDatosDePrueba.encolarEnvio();
        // Deja al primero con sus tomas agotadas y el lease vencido.
        jdbc.update("INSERT INTO leases_evaluacion (envio_id, worker, tomado_en, vence_en, veces_tomado) "
                + "VALUES (?, 'worker-caido', now() - interval '2 minutes', now() - interval '1 minute', ?)",
                atorado, MAX_TOMAS);
        long sano = BaseDatosDePrueba.encolarEnvio();
        EjecutorQueFalla ejecutor = new EjecutorQueFalla(0);

        new WorkerEvaluacion(jdbc, ejecutor, 0, MAX_TOMAS, "worker-prueba").revisarCola();

        assertEquals(1, ejecutor.llamadas.get(), "el envío agotado no debe volver a evaluarse");
        assertEquals("ERROR_SERVIDOR", jdbc.queryForObject(
                "SELECT veredicto FROM resultados WHERE envio_id = ?", String.class, atorado));
        assertEquals("ACCEPTED", jdbc.queryForObject(
                "SELECT veredicto FROM resultados WHERE envio_id = ?", String.class, sano));
    }
}
