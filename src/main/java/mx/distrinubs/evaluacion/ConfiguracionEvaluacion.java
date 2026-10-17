package mx.distrinubs.evaluacion;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Activa el {@code @Scheduled} del worker. El planificador usa un solo hilo: un solo worker. */
@Configuration
@EnableScheduling
class ConfiguracionEvaluacion {
}
