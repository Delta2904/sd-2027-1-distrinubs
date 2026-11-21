package mx.distrinubs.envios;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;

/**
 * Controlador REST del servicio {@code envios}.
 *
 * <p>Exige un token válido y confirmado contra {@code identidad}, impide la suplantación
 * por {@code alumnoId}, garantiza idempotencia por {@code (cuenta_id, id_intento)},
 * persiste en PostgreSQL antes de responder {@code "recibido"} y publica el
 * identificador del envío en RabbitMQ.
 */
@RestController
@RequestMapping("/api/envios")
public class EnviosController {

    private final JdbcTemplate jdbc;
    private final PublicadorEnvios publicadorEnvios;
    private final ValidadorTokenEnvios validadorToken;
    private final Set<String> lenguajesPermitidos;

    public EnviosController(
            JdbcTemplate jdbc,
            PublicadorEnvios publicadorEnvios,
            ValidadorTokenEnvios validadorToken,
            @Value("${envios.lenguajes-permitidos:PYTHON3}") String lenguajesPermitidos) {
        this.jdbc = jdbc;
        this.publicadorEnvios = publicadorEnvios;
        this.validadorToken = validadorToken;
        this.lenguajesPermitidos = Arrays.stream(lenguajesPermitidos.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private record EnvioBasico(long cuentaId, String estado) {}

    @PostMapping
    public ResponseEntity<EnvioCreadoResponse> crearEnvio(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody CrearEnvioRequest request) {
        SesionAutenticada sesion = validadorToken.validar(authHeader);

        if (request.alumnoId() != null
                && "ALUMNO".equalsIgnoreCase(sesion.rol())
                && !request.alumnoId().equals(sesion.cuentaId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "No puedes enviar soluciones en nombre de otra cuenta");
        }

        long cuentaId = sesion.cuentaId();

        if (request.problemaId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "problemaId es obligatorio");
        }
        if (request.intentoId() == null || request.intentoId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "intentoId es obligatorio");
        }

        UUID intentoUuid;
        try {
            intentoUuid = UUID.fromString(request.intentoId().trim());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "intentoId debe ser un UUID válido");
        }

        if (request.lenguaje() == null || request.lenguaje().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lenguaje es obligatorio");
        }
        String lenguajeNormalizado = request.lenguaje().trim().toUpperCase(Locale.ROOT);
        if (!lenguajesPermitidos.contains(lenguajeNormalizado)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Lenguaje no permitido: " + lenguajeNormalizado);
        }

        if (request.codigo() == null || request.codigo().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El código no puede estar vacío");
        }

        Integer cuentaExiste = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cuentas WHERE id = ?",
                Integer.class,
                cuentaId);
        if (cuentaExiste == null || cuentaExiste == 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "La cuenta con id " + cuentaId + " no existe");
        }

        Integer problemaExiste = jdbc.queryForObject(
                "SELECT COUNT(*) FROM problemas WHERE id = ?",
                Integer.class,
                request.problemaId());
        if (problemaExiste == null || problemaExiste == 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "El problema con id " + request.problemaId() + " no existe");
        }

        try {
            Long idGenerado = jdbc.queryForObject(
                    "INSERT INTO envios (id_intento, cuenta_id, problema_id, lenguaje, codigo, llegada_en, estado) "
                            + "VALUES (?, ?, ?, ?, ?, now(), 'EN_COLA') RETURNING id",
                    Long.class,
                    intentoUuid,
                    cuentaId,
                    request.problemaId(),
                    lenguajeNormalizado,
                    request.codigo());

            if (idGenerado != null) {
                publicadorEnvios.publicar(idGenerado);
            }

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new EnvioCreadoResponse(idGenerado != null ? idGenerado : 0L, "recibido"));

        } catch (DataIntegrityViolationException e) {
            List<Long> existentes = jdbc.query(
                    "SELECT id FROM envios WHERE cuenta_id = ? AND id_intento = ?",
                    (rs, rowNum) -> rs.getLong("id"),
                    cuentaId,
                    intentoUuid);

            if (!existentes.isEmpty()) {
                return ResponseEntity.ok(new EnvioCreadoResponse(existentes.get(0), "recibido"));
            }

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Error de integridad de datos al guardar el envío");
        }
    }

    @GetMapping("/{envioId}")
    public ResponseEntity<DetalleEnvioResponse> obtenerEstado(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long envioId) {
        SesionAutenticada sesion = validadorToken.validar(authHeader);

        List<EnvioBasico> envios = jdbc.query(
                "SELECT cuenta_id, estado FROM envios WHERE id = ?",
                (rs, rowNum) -> new EnvioBasico(rs.getLong("cuenta_id"), rs.getString("estado")),
                envioId);

        if (envios.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Envío no encontrado: " + envioId);
        }

        EnvioBasico envio = envios.get(0);
        if ("ALUMNO".equalsIgnoreCase(sesion.rol()) && envio.cuentaId() != sesion.cuentaId()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "No tienes permiso para consultar un envío de otra cuenta");
        }

        List<ResultadoEnvioDto> resultados = jdbc.query(
                "SELECT veredicto, tiempo_ms, memoria_kb, detalle FROM resultados WHERE envio_id = ?",
                (rs, rowNum) -> new ResultadoEnvioDto(
                        rs.getString("veredicto"),
                        rs.getObject("tiempo_ms", Integer.class),
                        rs.getObject("memoria_kb", Integer.class),
                        rs.getString("detalle")),
                envioId);

        if (!resultados.isEmpty()) {
            ResultadoEnvioDto res = resultados.get(0);
            return ResponseEntity.ok(new DetalleEnvioResponse(
                    envioId,
                    "evaluado",
                    res.veredicto(),
                    res.tiempoMs(),
                    res));
        }

        Integer leaseVigente = jdbc.queryForObject(
                "SELECT COUNT(*) FROM leases_evaluacion WHERE envio_id = ? AND vence_en > now()",
                Integer.class,
                envioId);

        if (leaseVigente != null && leaseVigente > 0) {
            return ResponseEntity.ok(new DetalleEnvioResponse(
                    envioId,
                    "evaluando",
                    null,
                    null,
                    null));
        }

        String estadoVisible = "RECIBIDO".equalsIgnoreCase(envio.estado()) ? "recibido" : "en cola";
        return ResponseEntity.ok(new DetalleEnvioResponse(
                envioId,
                estadoVisible,
                null,
                null,
                null));
    }
}
