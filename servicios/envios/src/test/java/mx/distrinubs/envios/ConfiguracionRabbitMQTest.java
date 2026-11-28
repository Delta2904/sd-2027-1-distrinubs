package mx.distrinubs.envios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
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
    @DisplayName("PublicadorEnviosRabbit solo devuelve true con ack sin returned y devuelve false en nack, returned o caída")
    void publicadorRequiereAckYManejaNackReturnedYCaida() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        PublicadorEnviosRabbit publicador = new PublicadorEnviosRabbit(
                rabbitTemplate, "distrinubs.envios", "envios.evaluacion", 500L);
        verify(rabbitTemplate).setMandatory(true);

        // (a) Confirm(true, null) sin returned -> true
        doAnswer(inv -> {
            CorrelationData cd = inv.getArgument(4);
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class), any(CorrelationData.class));

        assertTrue(publicador.publicar(42L));
        verify(rabbitTemplate).convertAndSend(
                org.mockito.ArgumentMatchers.eq("distrinubs.envios"),
                org.mockito.ArgumentMatchers.eq("envios.evaluacion"),
                org.mockito.ArgumentMatchers.eq(new MensajeEnvioEvaluacion(42L)),
                any(MessagePostProcessor.class),
                any(CorrelationData.class));

        // (b) Confirm(false, "nack") -> false
        doAnswer(inv -> {
            CorrelationData cd = inv.getArgument(4);
            cd.getFuture().complete(new CorrelationData.Confirm(false, "nack"));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class), any(CorrelationData.class));

        assertFalse(publicador.publicar(43L));

        // (c) Returned + Confirm(true, null) -> false
        doAnswer(inv -> {
            CorrelationData cd = inv.getArgument(4);
            cd.setReturned(new ReturnedMessage(
                    new Message(new byte[0], new MessageProperties()),
                    312,
                    "NO_ROUTE",
                    "distrinubs.envios",
                    "envios.evaluacion"));
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class), any(CorrelationData.class));

        assertFalse(publicador.publicar(44L));

        // (d) AmqpConnectException -> false
        doThrow(new AmqpConnectException(new RuntimeException("broker caído")))
                .when(rabbitTemplate)
                .convertAndSend(
                        anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class), any(CorrelationData.class));

        assertFalse(publicador.publicar(45L));
    }
}
