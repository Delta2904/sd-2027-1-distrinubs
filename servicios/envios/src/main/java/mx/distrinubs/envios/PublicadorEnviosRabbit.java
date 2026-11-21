package mx.distrinubs.envios;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publica mensajes persistentes con el {@code envioId} en RabbitMQ y adjunta
 * encabezados de control de reintentos para el consumidor.
 */
@Component
public class PublicadorEnviosRabbit implements PublicadorEnvios {

    private static final Logger log = LoggerFactory.getLogger(PublicadorEnviosRabbit.class);

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final String routingKey;
    private final int maxReintentos;

    public PublicadorEnviosRabbit(
            RabbitTemplate rabbitTemplate,
            @Value("${envios.mensajeria.exchange:distrinubs.envios}") String exchange,
            @Value("${envios.mensajeria.routing-key:envios.evaluacion}") String routingKey,
            @Value("${envios.mensajeria.max-reintentos:3}") int maxReintentos) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.maxReintentos = maxReintentos;
    }

    @Override
    public void publicar(long envioId) {
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, new MensajeEnvioEvaluacion(envioId), mensaje -> {
                mensaje.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                mensaje.getMessageProperties().setHeader("x-max-reintentos", maxReintentos);
                mensaje.getMessageProperties().setHeader("x-envio-id", envioId);
                return mensaje;
            });
        } catch (AmqpException ex) {
            // El envío ya quedó guardado en disco en PostgreSQL; el barrido periódico lo republicará.
            log.warn("No se pudo publicar el envío {} en RabbitMQ; quedará pendiente para el barrido", envioId, ex);
        }
    }
}
