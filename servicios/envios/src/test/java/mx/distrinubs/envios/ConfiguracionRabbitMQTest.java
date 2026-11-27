package mx.distrinubs.envios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class ConfiguracionRabbitMQTest {

    @Test
    @DisplayName("La cola principal es quorum durable con límite de entregas y DLX/DLQ")
    void colaConfiguradaConDlq() {
        ConfiguracionRabbitMQ config = new ConfiguracionRabbitMQ(
                "distrinubs.envios",
                "envios.evaluacion",
                "envios.evaluacion",
                "distrinubs.envios.dlx",
                "envios.evaluacion.dlq",
                "envios.evaluacion.dlq",
                3);

        Queue colaPrincipal = config.colaEnviosEvaluacion();
        Queue colaDlq = config.colaEnviosDeadLetter();
        DirectExchange exchange = config.enviosExchange();
        DirectExchange dlx = config.enviosDeadLetterExchange();

        assertEquals("envios.evaluacion", colaPrincipal.getName());
        assertTrue(colaPrincipal.isDurable());
        assertEquals("quorum", colaPrincipal.getArguments().get("x-queue-type"));
        assertEquals(3, ((Number) colaPrincipal.getArguments().get("x-delivery-limit")).intValue());
        assertEquals("distrinubs.envios.dlx", colaPrincipal.getArguments().get("x-dead-letter-exchange"));
        assertEquals("envios.evaluacion.dlq", colaPrincipal.getArguments().get("x-dead-letter-routing-key"));

        assertEquals("envios.evaluacion.dlq", colaDlq.getName());
        assertTrue(colaDlq.isDurable());
        assertTrue(exchange.isDurable());
        assertTrue(dlx.isDurable());
    }

    @Test
    @DisplayName("PublicadorEnviosRabbit envía el mensaje y devuelve false si RabbitMQ está caído")
    void publicadorToleraFallaDeBroker() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        PublicadorEnviosRabbit publicador = new PublicadorEnviosRabbit(
                rabbitTemplate, "distrinubs.envios", "envios.evaluacion");

        assertTrue(publicador.publicar(42L));
        verify(rabbitTemplate).convertAndSend(
                org.mockito.ArgumentMatchers.eq("distrinubs.envios"),
                org.mockito.ArgumentMatchers.eq("envios.evaluacion"),
                org.mockito.ArgumentMatchers.eq(new MensajeEnvioEvaluacion(42L)),
                any(MessagePostProcessor.class));

        doThrow(new AmqpConnectException(new RuntimeException("broker caído")))
                .when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class));

        assertFalse(publicador.publicar(43L));
    }
}
