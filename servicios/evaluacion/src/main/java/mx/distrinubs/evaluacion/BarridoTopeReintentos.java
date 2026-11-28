package mx.distrinubs.evaluacion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Respaldo del tope de reintentos. El consumidor cierra con ERROR_SERVIDOR los
 * envíos que agotan sus tomas mientras tiene su mensaje; este barrido cierra los
 * que se quedaron sin mensaje, por ejemplo si el último worker murió en la última
 * toma y el mensaje se fue a la cola de mensajes muertos. Varias réplicas lo
 * corren a la vez sin riesgo: la unicidad del resultado deja uno solo.
 */
@Component
@ConditionalOnProperty(name = "evaluacion.activa", havingValue = "true", matchIfMissing = true)
class BarridoTopeReintentos {

    private static final Logger log = LoggerFactory.getLogger(BarridoTopeReintentos.class);

    private final WorkerEvaluacion worker;

    BarridoTopeReintentos(WorkerEvaluacion worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${evaluacion.barrido-tope-ms:30000}")
    void revisar() {
        try {
            worker.agotarReintentos();
        } catch (DataAccessException e) {
            log.warn("No se pudo revisar el tope de reintentos: {}", e.getMessage());
        }
    }
}
