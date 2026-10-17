package mx.distrinubs.envios;

import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;

/**
 * Controlador REST para el módulo de Envíos (etapa 01-monolito).
 *
 * <p>Responsable de:
 * <ul>
 *   <li>{@code POST /api/envios}: Registrar un nuevo envío con su intentoId, alumno de demo,
 *       problema, lenguaje y código. Guarda en PostgreSQL con estado {@code 'EN_COLA'}
 *       y responde {@code 201 Created} con {@code estado: "recibido"}. Un reintento con el mismo
 *       {@code intentoId} responde {@code 200 OK} con el envío original.</li>
 *   <li>{@code GET /api/envios/{envioId}}: Consultar el estado y veredicto del envío.
 *       Devuelve "en cola", "evaluando" (derivado de lease vigente en {@code leases_evaluacion})
 *       o "evaluado" con el resultado de la tabla {@code resultados}.</li>
 * </ul>
 *
 * <p>Dueño de datos (PRD sección 6): este módulo solo escribe en {@code envios}.
 * Solo lee {@code cuentas}, {@code problemas}, {@code leases_evaluacion} y {@code resultados}.
 */
@RestController
@RequestMapping("/api/envios")
public class EnviosController {

    private final JdbcTemplate jdbc;

    public EnviosController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Registra un nuevo envío de solución.
     *
     * <p>La persistencia directa en {@code EN_COLA} permite a Evaluación tomarlo
     * de inmediato (PRD, bitácora 5). La respuesta confirma {@code estado: "recibido"}
     * solo después de guardar en PostgreSQL.
     */
    @PostMapping
    public ResponseEntity<EnvioCreadoResponse> crearEnvio(@Valid @RequestBody CrearEnvioRequest request) {
        if (request.alumnoId() == null || request.problemaId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "alumnoId y problemaId son obligatorios");
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

        if (request.lenguaje() == null || !request.lenguaje().trim().equalsIgnoreCase("PYTHON3")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "En la etapa 1 solo se permite el lenguaje PYTHON3");
        }

        if (request.codigo() == null || request.codigo().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El código no puede estar vacío");
        }

        // Valida existencia de la cuenta de demo
        Integer cuentaExiste = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cuentas WHERE id = ?",
                Integer.class,
                request.alumnoId());
        if (cuentaExiste == null || cuentaExiste == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El alumno con id " + request.alumnoId() + " no existe");
        }

        // Valida existencia del problema
        Integer problemaExiste = jdbc.queryForObject(
                "SELECT COUNT(*) FROM problemas WHERE id = ?",
                Integer.class,
                request.problemaId());
        if (problemaExiste == null || problemaExiste == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El problema con id " + request.problemaId() + " no existe");
        }

        try {
            // Intenta inserción inicial
            Long idGenerado = jdbc.queryForObject(
                    "INSERT INTO envios (id_intento, cuenta_id, problema_id, lenguaje, codigo, llegada_en, estado) "
                            + "VALUES (?, ?, ?, ?, ?, now(), 'EN_COLA') RETURNING id",
                    Long.class,
                    intentoUuid,
                    request.alumnoId(),
                    request.problemaId(),
                    "PYTHON3",
                    request.codigo());

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new EnvioCreadoResponse(idGenerado, "recibido"));

        } catch (DataIntegrityViolationException e) {
            // Choque por unicidad: CONSTRAINT envios_un_envio_por_intento UNIQUE (cuenta_id, id_intento).
            // Maneja concurrencia y reintentos idempotentes devolviendo el envío existente con 200 OK.
            List<Long> existentes = jdbc.query(
                    "SELECT id FROM envios WHERE cuenta_id = ? AND id_intento = ?",
                    (rs, rowNum) -> rs.getLong("id"),
                    request.alumnoId(),
                    intentoUuid);

            if (!existentes.isEmpty()) {
                return ResponseEntity.ok(new EnvioCreadoResponse(existentes.get(0), "recibido"));
            }

            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Error de integridad de datos al guardar el envío: " + e.getMessage());
        }
    }

    /**
     * Consulta el estado actual y veredicto de un envío.
     *
     * <p>Deriva "evaluando" a partir de un lease con vencimiento vigente en {@code leases_evaluacion}.
     * Cuando existe veredicto en {@code resultados}, devuelve "evaluado" junto con el detalle del resultado.
     */
    @GetMapping("/{envioId}")
    public ResponseEntity<DetalleEnvioResponse> obtenerEstado(@PathVariable Long envioId) {
        List<String> estados = jdbc.query(
                "SELECT estado FROM envios WHERE id = ?",
                (rs, rowNum) -> rs.getString("estado"),
                envioId);

        if (estados.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Envío no encontrado: " + envioId);
        }

        // 1. Revisar si la evaluación ya concluyó y hay resultado registrado
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

        // 2. Revisar si un worker tiene el envío en evaluación (lease vigente)
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

        // 3. Sin resultado ni lease vigente: el envío está en espera
        String estadoGuardado = estados.get(0);
        String estadoVisible = "RECIBIDO".equalsIgnoreCase(estadoGuardado) ? "recibido" : "en cola";

        return ResponseEntity.ok(new DetalleEnvioResponse(
                envioId,
                estadoVisible,
                null,
                null,
                null));
    }
}
