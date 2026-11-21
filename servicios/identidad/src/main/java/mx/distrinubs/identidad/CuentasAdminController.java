package mx.distrinubs.identidad;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * Endpoints de administración de cuentas, roles, bloqueos y revocación de tokens.
 * Requiere un token válido con rol {@code ADMINISTRADOR}.
 */
@RestController
@RequestMapping("/api/identidad/cuentas")
public class CuentasAdminController {

    private final IdentidadService identidadService;

    public CuentasAdminController(IdentidadService identidadService) {
        this.identidadService = identidadService;
    }

    @GetMapping
    public ResponseEntity<List<CuentaResumenDto>> listar(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        identidadService.exigirAdmin(authHeader);
        return ResponseEntity.ok(identidadService.listarCuentas());
    }

    @PostMapping
    public ResponseEntity<CuentaResumenDto> crear(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody CrearCuentaRequest request) {
        identidadService.exigirAdmin(authHeader);
        return ResponseEntity.status(HttpStatus.CREATED).body(identidadService.crearCuenta(request));
    }

    @PostMapping("/{cuentaId}/bloquear")
    public ResponseEntity<Map<String, Object>> bloquear(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable long cuentaId) {
        identidadService.exigirAdmin(authHeader);
        identidadService.bloquearCuenta(cuentaId);
        return ResponseEntity.ok(Map.of("cuentaId", cuentaId, "bloqueada", true));
    }

    @PostMapping("/{cuentaId}/desbloquear")
    public ResponseEntity<Map<String, Object>> desbloquear(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable long cuentaId) {
        identidadService.exigirAdmin(authHeader);
        identidadService.desbloquearCuenta(cuentaId);
        return ResponseEntity.ok(Map.of("cuentaId", cuentaId, "bloqueada", false));
    }

    @PostMapping("/{cuentaId}/revocar")
    public ResponseEntity<Map<String, Object>> revocar(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable long cuentaId) {
        identidadService.exigirAdmin(authHeader);
        identidadService.revocarTokens(cuentaId);
        return ResponseEntity.ok(Map.of("cuentaId", cuentaId, "estado", "tokens_revocados"));
    }

    @PatchMapping("/{cuentaId}/rol")
    public ResponseEntity<Map<String, Object>> cambiarRol(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable long cuentaId,
            @Valid @RequestBody CambiarRolRequest request) {
        identidadService.exigirAdmin(authHeader);
        identidadService.cambiarRol(cuentaId, request.rol());
        return ResponseEntity.ok(Map.of("cuentaId", cuentaId, "rol", request.rol().trim().toUpperCase()));
    }
}
