package mx.distrinubs.evaluacion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EvaluacionApplication {

    public static void main(String[] args) {
        SpringApplication.run(EvaluacionApplication.class, args);
    }
}
