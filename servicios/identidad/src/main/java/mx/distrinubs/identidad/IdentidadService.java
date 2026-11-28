package mx.distrinubs.identidad;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Lógica de autenticación, validación y revocación de tokens, y gestión de cuentas.
 * Dueño exclusivo de escritura sobre {@code cuentas} y {@code tokens_revocados}.
 */
@Service
public class IdentidadService {

    private static final Set<String> ROLES_VALIDOS = Set.of("ALUMNO", "ORGANIZADOR", "ADMINISTRADOR");

    private final JdbcTemplate jdbc;
    private final TokenJwtService tokenJwtService;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final String hashRelleno = passwordEncoder.encode("contrasena-relleno-tiempo-constante-distrinubs");

    public IdentidadService(JdbcTemplate jdbc, TokenJwtService tokenJwtService) {
        this.jdbc = jdbc;
        this.tokenJwtService = tokenJwtService;
    }

    private record CuentaAutenticacion(
            long id,
            String usuario,
            String nombre,
            String contrasenaHash,
            String rol,
            boolean bloqueada,
            Instant revocadaDesde,
            long ahoraMs) {}

    private record EstadoCuenta(
            long id,
            String usuario,
            String nombre,
            String rol,
            boolean bloqueada,
            Instant revocadaDesde) {}

    public LoginResponse autenticar(LoginRequest request) {
        String usuario = request.usuario().trim();
        String contrasena = request.contrasena();

        List<CuentaAutenticacion> cuentas = jdbc.query(
                // floor y no redondeo: un token emitido justo antes de una revocación o un
                // bloqueo no debe quedar fechado después de ella.
                "SELECT id, usuario, nombre, contrasena_hash, rol, bloqueada, revocada_desde, "
                        + "floor(EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::bigint AS ahora_ms "
                        + "FROM cuentas WHERE LOWER(usuario) = LOWER(?)",
                (rs, rowNum) -> {
                    Timestamp rev = rs.getTimestamp("revocada_desde");
                    return new CuentaAutenticacion(
                            rs.getLong("id"),
                            rs.getString("usuario"),
                            rs.getString("nombre"),
                            rs.getString("contrasena_hash"),
                            rs.getString("rol"),
                            rs.getBoolean("bloqueada"),
                            rev != null ? rev.toInstant() : null,
                            rs.getLong("ahora_ms"));
                },
                usuario);

        if (cuentas.isEmpty()) {
            passwordEncoder.matches(contrasena, hashRelleno);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas");
        }

        CuentaAutenticacion cuenta = cuentas.get(0);
        if (cuenta.contrasenaHash() == null) {
            passwordEncoder.matches(contrasena, hashRelleno);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas");
        }
        if (!passwordEncoder.matches(contrasena, cuenta.contrasenaHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas");
        }

        if (cuenta.bloqueada()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "La cuenta está bloqueada");
        }

        long ahoraMs = cuenta.ahoraMs();
        if (cuenta.revocadaDesde() != null && ahoraMs <= cuenta.revocadaDesde().toEpochMilli()) {
            ahoraMs = cuenta.revocadaDesde().toEpochMilli() + 1;
        }
        Instant emitidoEn = Instant.ofEpochMilli(ahoraMs);
        String token = tokenJwtService.generarToken(
                cuenta.id(), cuenta.usuario(), cuenta.nombre(), cuenta.rol(), emitidoEn);
        long exp = emitidoEn.getEpochSecond() + tokenJwtService.duracionSegundos();

        return new LoginResponse(token, "Bearer", cuenta.id(), cuenta.usuario(), cuenta.nombre(), cuenta.rol(), exp);
    }

    public SesionUsuario validarToken(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Encabezado Authorization requerido");
        }

        TokenJwtService.DatosToken datos = tokenJwtService.validarFirmaYExpiracion(authHeader);
        if (datos == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token inválido o expirado");
        }

        List<EstadoCuenta> cuentas = jdbc.query(
                "SELECT id, usuario, nombre, rol, bloqueada, revocada_desde FROM cuentas WHERE id = ?",
                (rs, rowNum) -> {
                    Timestamp rev = rs.getTimestamp("revocada_desde");
                    return new EstadoCuenta(
                            rs.getLong("id"),
                            rs.getString("usuario"),
                            rs.getString("nombre"),
                            rs.getString("rol"),
                            rs.getBoolean("bloqueada"),
                            rev != null ? rev.toInstant() : null);
                },
                datos.cuentaId());

        if (cuentas.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cuenta asociada no encontrada");
        }

        EstadoCuenta cuenta = cuentas.get(0);
        if (cuenta.bloqueada()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "La cuenta está bloqueada");
        }

        if (cuenta.revocadaDesde() != null && !datos.emitidoEn().isAfter(cuenta.revocadaDesde())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token revocado");
        }

        if (datos.jti() != null && !datos.jti().isBlank()) {
            Integer revocado = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM tokens_revocados WHERE jti = ?",
                    Integer.class,
                    datos.jti());
            if (revocado != null && revocado > 0) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token revocado");
            }
        }

        return new SesionUsuario(
                cuenta.id(),
                cuenta.usuario(),
                cuenta.nombre(),
                cuenta.rol(),
                datos.expiraEn().getEpochSecond(),
                datos.jti());
    }

    public void cerrarSesion(String authHeader) {
        SesionUsuario sesion = validarToken(authHeader);
        if (sesion.jti() != null && !sesion.jti().isBlank()) {
            jdbc.update(
                    "INSERT INTO tokens_revocados (jti, cuenta_id, revocado_en, expira_en) "
                            + "VALUES (?, ?, clock_timestamp(), ?) ON CONFLICT (jti) DO NOTHING",
                    sesion.jti(),
                    sesion.cuentaId(),
                    Timestamp.from(Instant.ofEpochSecond(sesion.exp())));
        } else {
            jdbc.update("UPDATE cuentas SET revocada_desde = clock_timestamp() WHERE id = ?", sesion.cuentaId());
        }
    }

    public SesionUsuario exigirAdmin(String authHeader) {
        SesionUsuario sesion = validarToken(authHeader);
        if (!"ADMINISTRADOR".equalsIgnoreCase(sesion.rol())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Se requiere rol ADMINISTRADOR");
        }
        return sesion;
    }

    public CuentaResumenDto crearCuenta(CrearCuentaRequest request) {
        String usuario = request.usuario().trim().toLowerCase(Locale.ROOT);
        String nombre = request.nombre().trim();
        String rol = (request.rol() == null || request.rol().isBlank())
                ? "ALUMNO"
                : request.rol().trim().toUpperCase(Locale.ROOT);

        if (!ROLES_VALIDOS.contains(rol)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Rol inválido: debe ser ALUMNO, ORGANIZADOR o ADMINISTRADOR");
        }

        String hash = passwordEncoder.encode(request.contrasena());
        try {
            return jdbc.queryForObject(
                    "INSERT INTO cuentas (usuario, nombre, contrasena_hash, rol, bloqueada) "
                            + "VALUES (?, ?, ?, ?, FALSE) "
                            + "RETURNING id, usuario, nombre, rol, bloqueada",
                    (rs, rowNum) -> new CuentaResumenDto(
                            rs.getLong("id"),
                            rs.getString("usuario"),
                            rs.getString("nombre"),
                            rs.getString("rol"),
                            rs.getBoolean("bloqueada")),
                    usuario,
                    nombre,
                    hash,
                    rol);
        } catch (DataIntegrityViolationException e) {
            if (esViolacionUnicidad(e)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "El usuario ya existe");
            }
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Los datos de la cuenta no son válidos");
        }
    }

    /** Solo la violación de unicidad significa que el usuario ya existe. */
    private boolean esViolacionUnicidad(DataIntegrityViolationException e) {
        for (Throwable causa = e; causa != null; causa = causa.getCause()) {
            if (causa instanceof SQLException sqlEx) {
                return "23505".equals(sqlEx.getSQLState());
            }
        }
        return e instanceof DuplicateKeyException;
    }

    public void bloquearCuenta(long cuentaId) {
        int filas = jdbc.update(
                "UPDATE cuentas SET bloqueada = TRUE, revocada_desde = clock_timestamp() WHERE id = ?",
                cuentaId);
        if (filas == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cuenta no encontrada: " + cuentaId);
        }
    }

    public void desbloquearCuenta(long cuentaId) {
        int filas = jdbc.update("UPDATE cuentas SET bloqueada = FALSE WHERE id = ?", cuentaId);
        if (filas == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cuenta no encontrada: " + cuentaId);
        }
    }

    public void revocarTokens(long cuentaId) {
        int filas = jdbc.update("UPDATE cuentas SET revocada_desde = clock_timestamp() WHERE id = ?", cuentaId);
        if (filas == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cuenta no encontrada: " + cuentaId);
        }
    }

    public void cambiarRol(long cuentaId, String rolRaw) {
        String rol = rolRaw == null ? "" : rolRaw.trim().toUpperCase(Locale.ROOT);
        if (!ROLES_VALIDOS.contains(rol)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Rol inválido: debe ser ALUMNO, ORGANIZADOR o ADMINISTRADOR");
        }
        int filas = jdbc.update("UPDATE cuentas SET rol = ? WHERE id = ?", rol, cuentaId);
        if (filas == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cuenta no encontrada: " + cuentaId);
        }
    }

    public List<CuentaResumenDto> listarCuentas() {
        return jdbc.query(
                "SELECT id, usuario, nombre, rol, bloqueada FROM cuentas ORDER BY id",
                (rs, rowNum) -> new CuentaResumenDto(
                        rs.getLong("id"),
                        rs.getString("usuario"),
                        rs.getString("nombre"),
                        rs.getString("rol"),
                        rs.getBoolean("bloqueada")));
    }

    public List<CuentaDemoDto> listarCuentasDemo() {
        return jdbc.query(
                "SELECT id, usuario, nombre FROM cuentas WHERE rol = 'ALUMNO' AND bloqueada = FALSE ORDER BY id",
                (rs, rowNum) -> new CuentaDemoDto(
                        rs.getLong("id"),
                        rs.getString("usuario"),
                        rs.getString("nombre")));
    }
}
