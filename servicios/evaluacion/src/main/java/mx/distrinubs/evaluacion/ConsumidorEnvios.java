package mx.distrinubs.evaluacion;

import java.io.IOException;
import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;

import mx.distrinubs.evaluacion.WorkerEvaluacion.Desenlace;

/**
 * Consume de RabbitMQ los envíos por evaluar. El mensaje solo trae el
 * {@code envioId}; el estado vive en PostgreSQL y manda el lease.
 *
 * <p>Qué hace con cada mensaje:
 * <ul>
 * <li>Lo confirma solo cuando el envío quedó con resultado: evaluado, duplicado
 * o cerrado con ERROR_SERVIDOR al agotar sus tomas.</li>
 * <li>Si otro worker tiene el lease vigente, espera a que venza y lo intenta de
 * nuevo. Así, cuando un worker muere a media evaluación, el mensaje que RabbitMQ
 * entrega a otro worker no produce dos evaluaciones a la vez.</li>
 * <li>Ante una falla del servidor lo devuelve a la cola para que lo tome otro
 * worker. El tope de tomas cierra el envío antes de que el límite de entregas lo
 * mande a la cola de mensajes muertos.</li>
 * <li>Si la base no responde, retiene el mensaje y reintenta: devolverlo gastaría
 * entregas por una falla que no es del envío.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "evaluacion.activa", havingValue = "true", matchIfMissing = true)
class ConsumidorEnvios {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorEnvios.class);

    /** Margen después del vencimiento de un lease ajeno antes de volver a intentar. */
    private static final long MARGEN_LEASE_AJENO_MS = 200;
    /** La espera a un lease ajeno se parte en tramos de este tamaño para volver a revisar. */
    private static final long TRAMO_ESPERA_MAXIMO_MS = 1000;
    /** Un mensaje puede llegar antes de que su envío sea visible: se reintenta unas veces. */
    private static final int INTENTOS_SIN_ENVIO = 3;
    private static final long ESPERA_SIN_ENVIO_MS = 500;
    private static final long ESPERA_BASE_MINIMA_MS = 1000;
    private static final long ESPERA_BASE_MAXIMA_MS = 10_000;

    /** Qué hacer con el mensaje. */
    enum Decision { CONFIRMAR, DEVOLVER, SOLTAR }

    private final WorkerEvaluacion worker;
    private final ObjectMapper json;
    private final String nombreBase;
    private final AtomicInteger consumidores = new AtomicInteger();
    /** Nombre del worker de cada hilo consumidor; firma sus leases. */
    private final ThreadLocal<String> nombreWorker;

    ConsumidorEnvios(WorkerEvaluacion worker, ObjectMapper json) {
        this.worker = worker;
        this.json = json;
        this.nombreBase = nombreBase();
        this.nombreWorker = ThreadLocal.withInitial(() -> {
            String nombre = nombreBase + "-" + consumidores.incrementAndGet();
            log.info("Worker de evaluación activo: {}", nombre);
            return nombre;
        });
    }

    @RabbitListener(queues = "${evaluacion.mensajeria.cola}", containerFactory = ConfiguracionRabbitMQ.FABRICA)
    void recibir(Message mensaje, Channel canal) throws IOException {
        long etiqueta = mensaje.getMessageProperties().getDeliveryTag();
        Long envioId = leerEnvioId(mensaje);
        if (envioId == null) {
            // Un mensaje que no se entiende no se puede evaluar nunca: va a la cola de mensajes muertos.
            log.error("Mensaje sin envioId válido; se manda a la cola de mensajes muertos");
            canal.basicReject(etiqueta, false);
            return;
        }
        switch (atender(envioId, nombreWorker.get())) {
            case CONFIRMAR -> canal.basicAck(etiqueta, false);
            case DEVOLVER -> canal.basicReject(etiqueta, true);
            // Se interrumpió al cerrar el servicio: el mensaje vuelve a la cola al cerrarse el canal.
            case SOLTAR -> log.info("Envío {}: se suelta sin confirmar al cerrar el servicio", envioId);
        }
    }

    /** Decide qué hacer con el mensaje del envío. */
    Decision atender(long envioId, String nombre) {
        int sinEnvio = 0;
        long esperaBase = ESPERA_BASE_MINIMA_MS;
        boolean avisoLeaseAjeno = false;
        while (!Thread.currentThread().isInterrupted()) {
            Desenlace desenlace;
            try {
                desenlace = worker.procesar(envioId, nombre);
            } catch (DataAccessException e) {
                log.warn("Envío {}: la base no responde ({}); se reintenta en {} ms",
                        envioId, e.getMessage(), esperaBase);
                if (!dormir(esperaBase)) {
                    break;
                }
                esperaBase = Math.min(esperaBase * 2, ESPERA_BASE_MAXIMA_MS);
                continue;
            }
            esperaBase = ESPERA_BASE_MINIMA_MS;
            switch (desenlace.tipo()) {
                case EVALUADO, YA_RESUELTO, AGOTADO:
                    return Decision.CONFIRMAR;
                case FALLA_SERVIDOR:
                    return Decision.DEVOLVER;
                case LEASE_AJENO:
                    if (!avisoLeaseAjeno) {
                        log.info("Envío {}: otro worker tiene su lease; {} espera {} ms a que venza",
                                envioId, nombre, desenlace.esperaMs());
                        avisoLeaseAjeno = true;
                    }
                    // En tramos cortos: si el otro worker termina antes, el duplicado se confirma pronto.
                    if (!dormir(Math.min(desenlace.esperaMs() + MARGEN_LEASE_AJENO_MS, TRAMO_ESPERA_MAXIMO_MS))) {
                        return Decision.SOLTAR;
                    }
                    break;
                case SIN_ENVIO:
                    if (++sinEnvio >= INTENTOS_SIN_ENVIO) {
                        log.warn("Envío {}: no existe o no está en cola; se descarta el mensaje", envioId);
                        return Decision.CONFIRMAR;
                    }
                    if (!dormir(ESPERA_SIN_ENVIO_MS)) {
                        return Decision.SOLTAR;
                    }
                    break;
            }
        }
        return Decision.SOLTAR;
    }

    /** Lee el {@code envioId} del cuerpo JSON; si no está, del encabezado {@code x-envio-id}. */
    Long leerEnvioId(Message mensaje) {
        try {
            JsonNode cuerpo = json.readTree(mensaje.getBody());
            if (cuerpo != null && cuerpo.path("envioId").canConvertToLong()) {
                return cuerpo.path("envioId").asLong();
            }
        } catch (IOException e) {
            log.warn("El cuerpo del mensaje no es JSON: {}", e.getMessage());
        }
        Object encabezado = mensaje.getMessageProperties().getHeader("x-envio-id");
        if (encabezado instanceof Number numero) {
            return numero.longValue();
        }
        return null;
    }

    private static boolean dormir(long ms) {
        try {
            Thread.sleep(Math.max(ms, 0));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
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
