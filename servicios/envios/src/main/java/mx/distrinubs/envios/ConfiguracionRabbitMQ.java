package mx.distrinubs.envios;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declara la topología de RabbitMQ del lado del productor: exchange principal,
 * cola durable de evaluación con enrutamiento de mensajes muertos (DLX/DLQ).
 */
@Configuration
public class ConfiguracionRabbitMQ {

    private final String exchange;
    private final String cola;
    private final String routingKey;
    private final String dlx;
    private final String dlq;
    private final String dlqRoutingKey;

    public ConfiguracionRabbitMQ(
            @Value("${envios.mensajeria.exchange:distrinubs.envios}") String exchange,
            @Value("${envios.mensajeria.cola:envios.evaluacion}") String cola,
            @Value("${envios.mensajeria.routing-key:envios.evaluacion}") String routingKey,
            @Value("${envios.mensajeria.dlx:distrinubs.envios.dlx}") String dlx,
            @Value("${envios.mensajeria.dlq:envios.evaluacion.dlq}") String dlq,
            @Value("${envios.mensajeria.dlq-routing-key:envios.evaluacion.dlq}") String dlqRoutingKey) {
        this.exchange = exchange;
        this.cola = cola;
        this.routingKey = routingKey;
        this.dlx = dlx;
        this.dlq = dlq;
        this.dlqRoutingKey = dlqRoutingKey;
    }

    @Bean
    public DirectExchange enviosExchange() {
        return new DirectExchange(exchange, true, false);
    }

    @Bean
    public DirectExchange enviosDeadLetterExchange() {
        return new DirectExchange(dlx, true, false);
    }

    @Bean
    public Queue colaEnviosEvaluacion() {
        return QueueBuilder.durable(cola)
                .withArgument("x-dead-letter-exchange", dlx)
                .withArgument("x-dead-letter-routing-key", dlqRoutingKey)
                .build();
    }

    @Bean
    public Queue colaEnviosDeadLetter() {
        return QueueBuilder.durable(dlq).build();
    }

    @Bean
    public Binding bindingEnviosEvaluacion(Queue colaEnviosEvaluacion, DirectExchange enviosExchange) {
        return BindingBuilder.bind(colaEnviosEvaluacion).to(enviosExchange).with(routingKey);
    }

    @Bean
    public Binding bindingEnviosDeadLetter(Queue colaEnviosDeadLetter, DirectExchange enviosDeadLetterExchange) {
        return BindingBuilder.bind(colaEnviosDeadLetter).to(enviosDeadLetterExchange).with(dlqRoutingKey);
    }

    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
