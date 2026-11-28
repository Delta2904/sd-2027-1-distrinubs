package mx.distrinubs.envios;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Valida tokens en dos pasos: verificación criptográfica local rápida y
 * confirmación de no revocación/bloqueo contra {@code identidad} con falla cerrada.
 */
@Component
public class ValidadorTokenEnvios {

    private final VerificadorTokenLocal verificadorLocal;
    private final ClienteIdentidad clienteIdentidad;

    public ValidadorTokenEnvios(VerificadorTokenLocal verificadorLocal, ClienteIdentidad clienteIdentidad) {
        this.verificadorLocal = verificadorLocal;
        this.clienteIdentidad = clienteIdentidad;
    }

    public SesionAutenticada validar(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Encabezado Authorization requerido");
        }

        SesionAutenticada sesionLocal = verificadorLocal.verificar(authHeader);
        if (sesionLocal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token inválido o expirado");
        }

        String headerNormalizado = authHeader.trim().regionMatches(true, 0, "Bearer ", 0, 7)
                ? authHeader.trim()
                : "Bearer " + authHeader.trim();

        return clienteIdentidad.validar(headerNormalizado);
    }
}
