package mx.distrinubs.directorio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Directorio de servicios: un servidor Eureka en su propio contenedor.
 *
 * <p>Los servicios se registran aquí al arrancar y renuevan su registro cada
 * pocos segundos. El gateway pide la lista para encontrar a cada servicio por
 * su nombre. Si una instancia muere sin avisar, deja de renovar y el
 * directorio la saca cuando vence su registro.
 *
 * <p>No guarda datos del concurso ni tiene lógica de negocio: la lista vive
 * en memoria y se reconstruye sola con los registros de los servicios.
 */
@SpringBootApplication
@EnableEurekaServer
public class DirectorioApplication {

    public static void main(String[] args) {
        SpringApplication.run(DirectorioApplication.class, args);
    }
}
