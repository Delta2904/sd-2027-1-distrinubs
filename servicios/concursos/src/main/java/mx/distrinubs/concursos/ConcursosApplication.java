package mx.distrinubs.concursos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "mx.distrinubs.concursos",
        "mx.distrinubs.tablaposiciones"
})
public class ConcursosApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConcursosApplication.class, args);
    }
}
