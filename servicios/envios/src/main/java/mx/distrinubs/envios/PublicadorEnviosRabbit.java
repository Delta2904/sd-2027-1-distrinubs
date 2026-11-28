package mx.distrinubs.envios;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publica mensajes persistentes con el {@code envioId} en RabbitMQ y espera
 * confirmación del broker (publisher confirms y returns) antes de dar por
 * aceptada la publicación.
 */
@Component
public class PublicadorEnviosRabbit implements PublicadorEnvios {

    private static final Logger log = LoggerFactory.getLogger(PublicadorEnviosRabbit.class);

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final String routingKey;
    private final long confirmTimeoutMs;

    public PublicadorEnviosRabbit(
            RabbitTemplate rabbitTemplate,
            @Value("${envios.mensajeria.exchange:distrinubs.envios}") String exchange,
            @Value("${envios.mensajeria.routing-key:envios.evaluacion}") String routingKey,
            @Value("${envios.mensajeria.confirm-timeout-ms:1000}") long confirmTimeoutMs) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.confirmTimeoutMs = confirmTimeoutMs;
        this.rabbitTemplate.setMandatory(true);
    }

    @Override
    public boolean publicar(long envioId) {
        CorrelationData correlacion = new CorrelationData(String.valueOf(envioId));
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, new MensajeEnvioEvaluacion(envioId), mensaje -> {
                mensaje.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                mensaje.getMessageProperties().setHeader("x-envio-id", envioId);
                return mensaje;
            }, correlacion);

            CorrelationData.Confirm confirmacion = correlacion.getFuture()
                    .get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
            if (correlacion.getReturned() != null) {
                log.warn("RabbitMQ devolvió sin enrutar el envío {} ({}); quedará pendiente para el barrido",
                        envioId, correlacion.getReturned().getReplyText());
                return false;
            }
            if (confirmacion == null || !confirmacion.isAck()) {
                log.warn("RabbitMQ rechazó (nack) la publicación del envío {}: {}; quedará pendiente para el barrido",
                        envioId, confirmacion != null ? confirmacion.getReason() : "sin confirmación");
                return false;
            }
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Interrumpido esperando confirmación de RabbitMQ para el envío {}; quedará pendiente para el barrido",
                    envioId, ex);
            return false;
        } catch (AmqpException | ExecutionException | TimeoutException ex) {
            // El envío ya quedó guardado en disco en PostgreSQL; el barrido periódico lo republicará.
            log.warn("No se confirmó la publicación del envío {} en RabbitMQ; quedará pendiente para el barrido",
                    envioId, ex);
            return false;
        }
    }
}
