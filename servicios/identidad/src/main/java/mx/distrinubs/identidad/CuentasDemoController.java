package mx.distrinubs.identidad;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Expone la lista de cuentas de alumno no bloqueadas para selección rápida en demostraciones.
 */
@RestController
public class CuentasDemoController {

    private final IdentidadService identidadService;

    public CuentasDemoController(IdentidadService identidadService) {
        this.identidadService = identidadService;
    }

    @GetMapping("/api/cuentas-demo")
    public List<CuentaDemoDto> listar() {
        return identidadService.listarCuentasDemo();
    }
}
