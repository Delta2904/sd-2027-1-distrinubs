package mx.distrinubs.identidad;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Punto de entrada del servicio de identidad (autenticación, tokens,
 * roles, bloqueo y revocación de cuentas).
 */
@SpringBootApplication
public class IdentidadApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentidadApplication.class, args);
    }
}
