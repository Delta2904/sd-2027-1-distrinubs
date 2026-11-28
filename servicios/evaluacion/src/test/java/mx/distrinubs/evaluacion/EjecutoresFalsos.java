package mx.distrinubs.evaluacion;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Ejecutores falsos para probar los leases sin correr Python. */
final class EjecutoresFalsos {

    private EjecutoresFalsos() {
    }

    /** Falla como servidor las primeras {@code fallas} veces y después acepta. */
    static class QueFalla extends EjecutorPython {
        final AtomicInteger llamadas = new AtomicInteger();
        private final int fallas;

        QueFalla(int fallas) {
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

    /** Cuenta cuántas veces se evaluó cada código y acepta. */
    static class Contador extends EjecutorPython {
        final Map<String, Integer> evaluaciones = new ConcurrentHashMap<>();

        Contador() {
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
}
