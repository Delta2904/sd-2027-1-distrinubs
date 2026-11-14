package mx.distrinubs.evaluacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import mx.distrinubs.BaseDatosDePrueba;

/**
 * Varios workers sobre la misma cola, contra un PostgreSQL real: ningún envío
 * se evalúa dos veces y ningún worker deja de trabajar mientras quede cola.
 */
class VariosWorkersTest {

    private static final int ENVIOS = 200;
    private static final int WORKERS = 4;

    /** Ejecutor falso: cuenta cuántas veces se evaluó cada código y acepta. */
    static class EjecutorContador extends EjecutorPython {
        final Map<String, Integer> evaluaciones = new ConcurrentHashMap<>();

        EjecutorContador() {
            super("python3", 10_000);
        }

        @Override
        Evaluacion evaluar(String codigo, List<Caso> casos, int limiteTiempoMs) {
            evaluaciones.merge(codigo, 1, Integer::sum);
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Evaluacion("ACCEPTED", 2, null);
        }
    }

    private JdbcTemplate jdbc;

    @BeforeEach
    void preparar() {
        jdbc = BaseDatosDePrueba.jdbc();
        BaseDatosDePrueba.vaciar();
        BaseDatosDePrueba.sembrarMinimo(1000);
    }

    @Test
    @DisplayName("4 workers, 200 envíos: una sola pasada por worker vacía la cola y cada envío tiene un resultado")
    void variosWorkersVacianLaColaSinDuplicar() throws Exception {
        for (int i = 0; i < ENVIOS; i++) {
            long id = BaseDatosDePrueba.encolarEnvio();
            // El código distingue a cada envío para contar sus evaluaciones.
            jdbc.update("UPDATE envios SET codigo = ? WHERE id = ?", "envio-" + id, id);
        }

        EjecutorContador ejecutor = new EjecutorContador();
        CountDownLatch salida = new CountDownLatch(1);
        ExecutorService hilos = Executors.newFixedThreadPool(WORKERS);
        List<Future<?>> pasadas = new ArrayList<>();
        for (int w = 0; w < WORKERS; w++) {
            WorkerEvaluacion worker = nuevoWorker(ejecutor, w + 1);
            pasadas.add(hilos.submit(() -> {
                salida.await();
                // Una sola pasada: el worker solo debe volver cuando ya no hay
                // trabajo que tomar, aunque pierda carreras con los demás.
                worker.revisarCola();
                return null;
            }));
        }
        salida.countDown();
        for (Future<?> pasada : pasadas) {
            pasada.get(120, TimeUnit.SECONDS);
        }
        hilos.shutdown();

        assertEquals(ENVIOS, contar("SELECT count(*) FROM resultados"),
                "quedó trabajo sin tomar: algún worker leyó una carrera perdida como cola vacía");
        assertEquals(ENVIOS, contar("SELECT count(DISTINCT envio_id) FROM resultados"));
        assertEquals(ENVIOS, ejecutor.evaluaciones.size());
        assertTrue(ejecutor.evaluaciones.values().stream().allMatch(veces -> veces == 1),
                "algún envío se evaluó más de una vez");
        assertEquals(0, contar("SELECT count(*) FROM leases_evaluacion WHERE veces_tomado <> 1"));
        assertTrue(contar("SELECT count(DISTINCT worker) FROM leases_evaluacion") > 1,
                "todo el trabajo lo hizo un solo worker");
    }

    private WorkerEvaluacion nuevoWorker(EjecutorPython ejecutor, int numero) {
        return new WorkerEvaluacion(jdbc, ejecutor, 20_000, 3, "worker-prueba-" + numero);
    }

    private int contar(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
