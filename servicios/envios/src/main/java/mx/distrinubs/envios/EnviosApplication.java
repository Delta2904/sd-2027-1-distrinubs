package mx.distrinubs.envios;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Punto de entrada del servicio de envíos (recepción, idempotencia,
 * publicación hacia RabbitMQ y consulta de estado).
 */
@SpringBootApplication
@EnableScheduling
public class EnviosApplication {

    public static void main(String[] args) {
        SpringApplication.run(EnviosApplication.class, args);
    }
}
