package mx.distrinubs.identidad;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * Endpoints públicos e internos de autenticación y validación de sesión.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final IdentidadService identidadService;

    public AuthController(IdentidadService identidadService) {
        this.identidadService = identidadService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(identidadService.autenticar(request));
    }

    @GetMapping("/yo")
    public ResponseEntity<SesionUsuario> yo(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        return ResponseEntity.ok(identidadService.validarToken(authHeader));
    }

    @GetMapping("/validar")
    public ResponseEntity<SesionUsuario> validar(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        return ResponseEntity.ok(identidadService.validarToken(authHeader));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        identidadService.cerrarSesion(authHeader);
        return ResponseEntity.ok(Map.of("estado", "sesion_cerrada"));
    }
}
