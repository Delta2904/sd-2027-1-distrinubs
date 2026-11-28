package mx.distrinubs.gateway.circuito;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Respuesta del gateway cuando no pudo llegar a un servicio o su circuito está abierto.
 *
 * <p>Cada ruta manda aquí su petición fallida, con el nombre del servicio. Atiende
 * cualquier método HTTP: la petición original puede ser un GET o un POST.
 */
@RestController
public class ServicioNoDisponibleController {

    @RequestMapping("/no-disponible/{servicio}")
    public ResponseEntity<Map<String, Object>> noDisponible(@PathVariable String servicio) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
        cuerpo.put("error", "SERVICIO_NO_DISPONIBLE");
        cuerpo.put("servicio", servicio);
        cuerpo.put("mensaje", "El servicio " + servicio + " no está disponible. Intenta de nuevo en unos segundos.");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(cuerpo);
    }
}
