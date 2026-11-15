package mx.distrinubs.iniciosesion;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;

/**
 * Controlador de autenticación e inicio de sesión (Contrato Paso 3 de la etapa 2).
 *
 * <p>Proporciona:
 * <ul>
 *   <li>{@code POST /api/auth/login}: Recibe credenciales y emite un token firmado con
 *       sub (la cuenta), rol y exp, en cumplimiento del contrato acordado.</li>
 *   <li>{@code GET /api/auth/yo}: Valida el token pasado en {@code Authorization: Bearer <token>}
 *       y devuelve los datos de la sesión actual.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JdbcTemplate jdbc;
    private final TokenService tokenService;

    public AuthController(JdbcTemplate jdbc, TokenService tokenService) {
        this.jdbc = jdbc;
        this.tokenService = tokenService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest peticion) {
        String usuario = peticion.usuario().trim();
        String contrasena = peticion.contrasena();

        // Si es cuenta de administrador especial
        if ("admin".equalsIgnoreCase(usuario) && ("admin".equals(contrasena) || "distrinubs".equals(contrasena))) {
            String token = tokenService.generarToken(9999L, "admin", "Administrador Distrinubs", "ADMINISTRADOR");
            SesionUsuario sesion = tokenService.validarToken(token);
            return ResponseEntity.ok(new LoginResponse(
                    token,
                    "Bearer",
                    9999L,
                    "admin",
                    "Administrador Distrinubs",
                    "ADMINISTRADOR",
                    sesion != null ? sesion.exp() : 0));
        }

        // Búsqueda en la tabla de cuentas sembradas
        List<CuentasDemoController.CuentaDemo> encontradas = jdbc.query(
                "SELECT id, usuario, nombre FROM cuentas WHERE LOWER(usuario) = LOWER(?)",
                (rs, fila) -> new CuentasDemoController.CuentaDemo(
                        rs.getLong("id"),
                        rs.getString("usuario"),
                        rs.getString("nombre")),
                usuario);

        if (encontradas.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario o contraseña incorrectos");
        }

        CuentasDemoController.CuentaDemo cuenta = encontradas.get(0);
        // En la demo/paso 3 inicial, cualquier contraseña no vacía es aceptada para las cuentas de prueba
        if (contrasena == null || contrasena.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "La contraseña no puede estar vacía");
        }

        String rol = "ALUMNO";
        String token = tokenService.generarToken(cuenta.id(), cuenta.usuario(), cuenta.nombre(), rol);
        SesionUsuario sesion = tokenService.validarToken(token);

        return ResponseEntity.ok(new LoginResponse(
                token,
                "Bearer",
                cuenta.id(),
                cuenta.usuario(),
                cuenta.nombre(),
                rol,
                sesion != null ? sesion.exp() : 0));
    }

    @GetMapping("/yo")
    public ResponseEntity<SesionUsuario> obtenerSesion(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Encabezado Authorization requerido");
        }

        SesionUsuario sesion = tokenService.validarToken(authHeader);
        if (sesion == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token inválido o expirado");
        }

        return ResponseEntity.ok(sesion);
    }
}
