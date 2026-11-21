package mx.distrinubs.envios;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Verificación criptográfica local de firma HMAC-SHA256 y expiración de tokens JWT.
 */
@Component
public class VerificadorTokenLocal {

    private static final String ALGORITMO_HMAC = "HmacSHA256";

    private final ObjectMapper mapper;
    private final String secreto;

    public VerificadorTokenLocal(
            ObjectMapper mapper,
            @Value("${envios.autenticacion.jwt-secreto}") String secreto) {
        if (secreto == null || secreto.isBlank()) {
            throw new IllegalArgumentException("El secreto JWT no puede estar vacío");
        }
        this.mapper = mapper;
        this.secreto = secreto;
    }

    public SesionAutenticada verificar(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return null;
        }
        String token = authHeader.trim();
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

            long exp = json.path("exp").asLong(0);
            if (Instant.now().getEpochSecond() >= exp) {
                return null;
            }

            String sub = json.path("sub").asText(null);
            long cuentaId = json.path("cuentaId").asLong(0);
            if (sub == null || sub.isBlank() || cuentaId <= 0) {
                return null;
            }

            return new SesionAutenticada(
                    cuentaId,
                    sub,
                    json.path("nombre").asText(sub),
                    json.path("rol").asText("ALUMNO"),
                    exp,
                    json.path("jti").asText(""));
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
}
