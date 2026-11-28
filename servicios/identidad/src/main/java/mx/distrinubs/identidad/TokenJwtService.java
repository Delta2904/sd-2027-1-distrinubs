package mx.distrinubs.identidad;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Genera y verifica tokens JWT firmados con HMAC-SHA256.
 */
@Service
public class TokenJwtService {

    private static final String ALGORITMO_HMAC = "HmacSHA256";

    private final ObjectMapper mapper;
    private final String secreto;
    private final long duracionSegundos;

    public TokenJwtService(
            ObjectMapper mapper,
            @Value("${identidad.jwt.secreto}") String secreto,
            @Value("${identidad.jwt.duracion-segundos:86400}") long duracionSegundos) {
        if (secreto == null || secreto.isBlank()) {
            throw new IllegalArgumentException("El secreto JWT no puede estar vacío");
        }
        this.mapper = mapper;
        this.secreto = secreto;
        this.duracionSegundos = duracionSegundos;
    }

    public record DatosToken(
            long cuentaId,
            String usuario,
            String nombre,
            String rol,
            String jti,
            Instant emitidoEn,
            Instant expiraEn) {}

    public String generarToken(long cuentaId, String usuario, String nombre, String rol) {
        return generarToken(cuentaId, usuario, nombre, rol, Instant.now());
    }

    public long duracionSegundos() {
        return duracionSegundos;
    }

    public String generarToken(long cuentaId, String usuario, String nombre, String rol, Instant emitidoEn) {
        try {
            long iatSegundos = emitidoEn.getEpochSecond();
            long iatMs = emitidoEn.toEpochMilli();
            long expSegundos = iatSegundos + duracionSegundos;

            ObjectNode header = mapper.createObjectNode();
            header.put("alg", "HS256");
            header.put("typ", "JWT");

            ObjectNode payload = mapper.createObjectNode();
            payload.put("sub", String.valueOf(cuentaId));
            payload.put("usuario", usuario);
            payload.put("nombre", nombre);
            payload.put("rol", rol != null ? rol : "ALUMNO");
            payload.put("jti", UUID.randomUUID().toString());
            payload.put("iat", iatSegundos);
            payload.put("iatMs", iatMs);
            payload.put("exp", expSegundos);

            String headerB64 = base64Url(mapper.writeValueAsBytes(header));
            String payloadB64 = base64Url(mapper.writeValueAsBytes(payload));
            String datosFirma = headerB64 + "." + payloadB64;
            String firmaB64 = base64Url(firmarHmac(datosFirma));

            return datosFirma + "." + firmaB64;
        } catch (Exception e) {
            throw new IllegalStateException("Error al generar el token JWT", e);
        }
    }

    /**
     * Valida la firma HMAC-SHA256 y la expiración del token.
     * Devuelve {@code null} si el formato, la firma o la expiración no son válidos.
     */
    public DatosToken validarFirmaYExpiracion(String authHeaderOToken) {
        if (authHeaderOToken == null || authHeaderOToken.isBlank()) {
            return null;
        }
        String token = authHeaderOToken.trim();
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = token.substring(7).trim();
        }
        if (token.isEmpty()) {
            return null;
        }

        String[] partes = token.split("\\.");
        if (partes.length != 3) {
            return null;
        }

        try {
            String datosFirma = partes[0] + "." + partes[1];
            byte[] firmaEsperada = firmarHmac(datosFirma);
            byte[] firmaRecibida = Base64.getUrlDecoder().decode(partes[2]);
            if (!MessageDigest.isEqual(firmaEsperada, firmaRecibida)) {
                return null;
            }

            byte[] payloadBytes = Base64.getUrlDecoder().decode(partes[1]);
            JsonNode json = mapper.readTree(payloadBytes);

            long expSegundos = json.path("exp").asLong(0);
            Instant ahora = Instant.now();
            if (ahora.getEpochSecond() >= expSegundos) {
                return null;
            }

            String sub = json.path("sub").asText(null);
            if (sub == null || sub.isBlank()) {
                return null;
            }
            long cuentaId;
            try {
                cuentaId = Long.parseLong(sub.trim());
            } catch (NumberFormatException e) {
                return null;
            }

            String usuario = json.path("usuario").asText("");
            if (cuentaId <= 0 || usuario.isBlank()) {
                return null;
            }

            String nombre = json.path("nombre").asText(usuario);
            String rol = json.path("rol").asText("ALUMNO");
            String jti = json.path("jti").asText("");
            long iatSegundos = json.path("iat").asLong(0);
            long iatMs = json.path("iatMs").asLong(iatSegundos * 1000L);

            return new DatosToken(
                    cuentaId,
                    usuario,
                    nombre,
                    rol,
                    jti,
                    Instant.ofEpochMilli(iatMs),
                    Instant.ofEpochSecond(expSegundos));
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] firmarHmac(String datos) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac hmac = Mac.getInstance(ALGORITMO_HMAC);
        SecretKeySpec keySpec = new SecretKeySpec(secreto.getBytes(StandardCharsets.UTF_8), ALGORITMO_HMAC);
        hmac.init(keySpec);
        return hmac.doFinal(datos.getBytes(StandardCharsets.UTF_8));
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
