package mx.distrinubs.evaluacion;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topología de RabbitMQ del lado del consumidor y fábrica de consumidores.
 *
 * <p>Declara la misma cola que {@code envios} (quorum, con límite de entregas y
 * cola de mensajes muertos), así el servicio arranca aunque {@code envios} todavía
 * no la haya creado. Si los argumentos difieren, RabbitMQ rechaza la declaración:
 * por eso los valores salen de la misma configuración.
 */
@Configuration
class ConfiguracionRabbitMQ {

    static final String FABRICA = "fabricaConsumidoresEvaluacion";

    @Bean
    DirectExchange enviosExchange(@Value("${evaluacion.mensajeria.exchange}") String exchange) {
        return new DirectExchange(exchange, true, false);
    }

    @Bean
    DirectExchange enviosDeadLetterExchange(@Value("${evaluacion.mensajeria.dlx}") String dlx) {
        return new DirectExchange(dlx, true, false);
    }

    @Bean
    Queue colaEnviosEvaluacion(
            @Value("${evaluacion.mensajeria.cola}") String cola,
            @Value("${evaluacion.mensajeria.dlx}") String dlx,
            @Value("${evaluacion.mensajeria.dlq-routing-key}") String dlqRoutingKey,
            @Value("${evaluacion.mensajeria.limite-entregas}") int limiteEntregas) {
        return QueueBuilder.durable(cola)
                .quorum()
                .deliveryLimit(limiteEntregas)
                .deadLetterExchange(dlx)
                .deadLetterRoutingKey(dlqRoutingKey)
                .build();
    }

    @Bean
    Queue colaEnviosDeadLetter(@Value("${evaluacion.mensajeria.dlq}") String dlq) {
        return QueueBuilder.durable(dlq).build();
    }

    @Bean
    Binding bindingEnviosEvaluacion(Queue colaEnviosEvaluacion, DirectExchange enviosExchange,
            @Value("${evaluacion.mensajeria.routing-key}") String routingKey) {
        return BindingBuilder.bind(colaEnviosEvaluacion).to(enviosExchange).with(routingKey);
    }

    @Bean
    Binding bindingEnviosDeadLetter(Queue colaEnviosDeadLetter, DirectExchange enviosDeadLetterExchange,
            @Value("${evaluacion.mensajeria.dlq-routing-key}") String dlqRoutingKey) {
        return BindingBuilder.bind(colaEnviosDeadLetter).to(enviosDeadLetterExchange).with(dlqRoutingKey);
    }

    /**
     * Cada consumidor recibe un mensaje a la vez ({@code prefetch} 1) y lo confirma
     * a mano, solo después de guardar el resultado. Un worker que muere sin
     * confirmar devuelve el mensaje a la cola al cerrarse su conexión.
     */
    @Bean(FABRICA)
    SimpleRabbitListenerContainerFactory fabricaConsumidoresEvaluacion(
            ConnectionFactory conexiones,
            SimpleRabbitListenerContainerFactoryConfigurer configurador,
            @Value("${evaluacion.workers:2}") int workers) {
        if (workers < 1) {
            throw new IllegalArgumentException("evaluacion.workers debe ser al menos 1");
        }
        SimpleRabbitListenerContainerFactory fabrica = new SimpleRabbitListenerContainerFactory();
        configurador.configure(fabrica, conexiones);
        fabrica.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        fabrica.setPrefetchCount(1);
        fabrica.setConcurrentConsumers(workers);
        fabrica.setMaxConcurrentConsumers(workers);
        fabrica.setDefaultRequeueRejected(false);
        // Si RabbitMQ todavía no arranca o la cola no existe, reintenta en lugar de morir.
        fabrica.setMissingQueuesFatal(false);
        return fabrica;
    }
}
