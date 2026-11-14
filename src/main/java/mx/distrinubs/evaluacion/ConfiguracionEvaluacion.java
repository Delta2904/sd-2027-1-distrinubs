package mx.distrinubs.evaluacion;

import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Arranca los workers de evaluación: {@code evaluacion.workers} hilos dentro de
 * la misma aplicación, cada uno con su propio {@link WorkerEvaluacion}. Siguen
 * siendo un solo proceso; no hay servicios ni réplicas.
 *
 * <p>Cada worker revisa la cola, la vacía y espera {@code intervalo-ms} antes
 * de volver a revisar. Los workers no se coordinan entre sí: solo comparten la
 * cola de PostgreSQL, igual que lo harían en procesos separados.
 */
@Configuration
@ConditionalOnProperty(name = "evaluacion.activa", havingValue = "true", matchIfMissing = true)
class ConfiguracionEvaluacion {

    private static final Logger log = LoggerFactory.getLogger(ConfiguracionEvaluacion.class);

    /** Al cerrar la aplicación se interrumpen; lo que quede a medias vuelve a la cola al vencer su lease. */
    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService workersEvaluacion(
            JdbcTemplate jdbc,
            EjecutorPython ejecutor,
            @Value("${evaluacion.workers:2}") int workers,
            @Value("${evaluacion.intervalo-ms:1000}") long intervaloMs,
            @Value("${evaluacion.max-tomas:3}") int maxTomas,
            @Value("${evaluacion.salvaguarda-compilacion-ms:10000}") long salvaguardaCompilacionMs,
            @Value("${evaluacion.margen-lease-ms:10000}") long margenLeaseMs) {
        if (workers < 1 || maxTomas < 1) {
            throw new IllegalArgumentException("evaluacion.workers y evaluacion.max-tomas deben ser al menos 1");
        }
        AtomicInteger numeroHilo = new AtomicInteger();
        ScheduledExecutorService hilos = Executors.newScheduledThreadPool(workers,
                tarea -> new Thread(tarea, "worker-evaluacion-" + numeroHilo.incrementAndGet()));
        String base = nombreBase();
        for (int n = 1; n <= workers; n++) {
            WorkerEvaluacion worker = new WorkerEvaluacion(jdbc, ejecutor,
                    salvaguardaCompilacionMs + margenLeaseMs, maxTomas, base + "-" + n);
            hilos.scheduleWithFixedDelay(() -> {
                try {
                    worker.revisarCola();
                } catch (RuntimeException e) {
                    // Si la tarea lanza, el planificador deja de repetirla: el
                    // worker se quedaría muerto sin avisar.
                    log.error("Falla inesperada en un worker de evaluación", e);
                }
            }, intervaloMs, intervaloMs, TimeUnit.MILLISECONDS);
        }
        return hilos;
    }

    private static String nombreBase() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "local";
        }
        return "worker-" + host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
