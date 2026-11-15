package mx.distrinubs.iniciosesion;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Servicio para generar y verificar tokens de autenticación según el contrato acordado:
 * Token firmado con `sub` (la cuenta), `rol` y `exp`, transportado en el encabezado
 * {@code Authorization: Bearer <token>}.
 *
 * <p>Produce y valida tokens con formato JWT (HMAC-SHA256) sin requerir dependencias
 * externas adicionales.
 */
@Service
public class TokenService {

    private static final String CLAVE_SECRETA = "distrinubs-secreto-compartido-etapa-02-separacion-2027";
    private static final String ALGORITMO_HMAC = "HmacSHA256";
    private static final long DURACION_SEGUNDOS = 24 * 60 * 60; // 24 horas

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Genera un token firmado con sub, rol, cuentaId, nombre y exp.
     */
    public String generarToken(long cuentaId, String usuario, String nombre, String rol) {
        try {
            long ahora = Instant.now().getEpochSecond();
            long expiracion = ahora + DURACION_SEGUNDOS;

            // Encabezado JWT estándar
            ObjectNode header = mapper.createObjectNode();
            header.put("alg", "HS256");
            header.put("typ", "JWT");

            // Payload con sub, rol y exp según el contrato
            ObjectNode payload = mapper.createObjectNode();
            payload.put("sub", usuario);
            payload.put("cuentaId", cuentaId);
            payload.put("nombre", nombre);
            payload.put("rol", rol != null ? rol : "ALUMNO");
            payload.put("iat", ahora);
            payload.put("exp", expiracion);

            String headerB64 = base64Url(mapper.writeValueAsBytes(header));
            String payloadB64 = base64Url(mapper.writeValueAsBytes(payload));
            String datosFirma = headerB64 + "." + payloadB64;
            String firmaB64 = firmarHmac(datosFirma, CLAVE_SECRETA);

            return datosFirma + "." + firmaB64;
        } catch (Exception e) {
            throw new IllegalStateException("Error al generar token", e);
        }
    }

    /**
     * Valida la firma y expiración del token y devuelve la sesión decodificada.
     * Retorna null si el token es inválido o ha expirado.
     */
    public SesionUsuario validarToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        if (token.startsWith("Bearer ")) {
            token = token.substring(7).trim();
        }

        String[] partes = token.split("\\.");
        if (partes.length != 3) {
            return null;
        }

        String headerB64 = partes[0];
        String payloadB64 = partes[1];
        String firmaB64 = partes[2];

        try {
            String datosFirma = headerB64 + "." + payloadB64;
            String firmaEsperada = firmarHmac(datosFirma, CLAVE_SECRETA);
            if (!firmaEsperada.equals(firmaB64)) {
                return null; // Firma inválida
            }

            byte[] payloadBytes = Base64.getUrlDecoder().decode(payloadB64);
            JsonNode json = mapper.readTree(payloadBytes);

            long exp = json.path("exp").asLong(0);
            if (Instant.now().getEpochSecond() > exp) {
                return null; // Token expirado
            }

            String sub = json.path("sub").asText(null);
            long cuentaId = json.path("cuentaId").asLong(0);
            String nombre = json.path("nombre").asText(sub);
            String rol = json.path("rol").asText("ALUMNO");

            return new SesionUsuario(cuentaId, sub, nombre, rol, exp);
        } catch (Exception e) {
            return null;
        }
    }

    private String firmarHmac(String datos, String secreto)
            throws NoSuchAlgorithmException, InvalidKeyException {
        Mac hmac = Mac.getInstance(ALGORITMO_HMAC);
        SecretKeySpec keySpec = new SecretKeySpec(secreto.getBytes(StandardCharsets.UTF_8), ALGORITMO_HMAC);
        hmac.init(keySpec);
        byte[] hash = hmac.doFinal(datos.getBytes(StandardCharsets.UTF_8));
        return base64Url(hash);
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
