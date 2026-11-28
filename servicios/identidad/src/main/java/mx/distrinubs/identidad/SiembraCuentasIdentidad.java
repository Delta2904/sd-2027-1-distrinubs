package mx.distrinubs.identidad;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Siembra idempotente de cuentas iniciales al arrancar el servicio {@code identidad},
 * con contraseñas almacenadas como hash BCrypt y roles asignados. Rota los hashes
 * de las cuentas sembradas al cambiar las variables de contraseña sin sobrescribir roles
 * ni bloqueos existentes.
 */
@Component
@DependsOnDatabaseInitialization
@ConditionalOnProperty(name = "siembra.activa", havingValue = "true", matchIfMissing = true)
class SiembraCuentasIdentidad implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SiembraCuentasIdentidad.class);
    private static final int MAXIMO_CUENTAS_CARGA = 899;

    private static final String UPSERT_CUENTA = """
            INSERT INTO cuentas (id, usuario, nombre, contrasena_hash, rol, bloqueada)
            VALUES (?, ?, ?, ?, ?, FALSE)
            ON CONFLICT (id) DO UPDATE
                SET usuario         = EXCLUDED.usuario,
                    nombre          = EXCLUDED.nombre,
                    contrasena_hash = EXCLUDED.contrasena_hash
            """;

    private static final String SEMBRAR_CUENTAS_CARGA = """
            INSERT INTO cuentas (id, usuario, nombre, contrasena_hash, rol, bloqueada)
            SELECT 100 + n, 'carga' || lpad(n::text, 3, '0'), 'Alumno de carga ' || n, ?, 'ALUMNO', FALSE
            FROM generate_series(1, ?) AS n
            ON CONFLICT (id) DO UPDATE
                SET usuario         = EXCLUDED.usuario,
                    nombre          = EXCLUDED.nombre,
                    contrasena_hash = EXCLUDED.contrasena_hash
            """;

    private final JdbcTemplate jdbc;
    private final int cuentasCarga;
    private final String contrasenaDemo;
    private final String contrasenaAdmin;

    SiembraCuentasIdentidad(
            JdbcTemplate jdbc,
            @Value("${siembra.cuentas-carga:0}") int cuentasCarga,
            @Value("${siembra.contrasena-demo}") String contrasenaDemo,
            @Value("${siembra.contrasena-admin}") String contrasenaAdmin) {
        if (cuentasCarga < 0 || cuentasCarga > MAXIMO_CUENTAS_CARGA) {
            throw new IllegalArgumentException(
                    "siembra.cuentas-carga debe estar entre 0 y " + MAXIMO_CUENTAS_CARGA);
        }
        if (contrasenaDemo == null || contrasenaDemo.isBlank()) {
            throw new IllegalArgumentException("Falta SIEMBRA_CONTRASENA_DEMO para sembrar las cuentas de alumnos");
        }
        if (contrasenaAdmin == null || contrasenaAdmin.isBlank()) {
            throw new IllegalArgumentException(
                    "Falta SIEMBRA_CONTRASENA_ADMIN para sembrar las cuentas de administración y organización");
        }
        if (contrasenaAdmin.equals(contrasenaDemo)) {
            throw new IllegalArgumentException(
                    "SIEMBRA_CONTRASENA_ADMIN debe ser distinta de SIEMBRA_CONTRASENA_DEMO");
        }
        this.jdbc = jdbc;
        this.cuentasCarga = cuentasCarga;
        this.contrasenaDemo = contrasenaDemo;
        this.contrasenaAdmin = contrasenaAdmin;
    }

    @Override
    public void afterPropertiesSet() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String hashDemo = encoder.encode(contrasenaDemo);
        String hashAdmin = encoder.encode(contrasenaAdmin);

        jdbc.update(UPSERT_CUENTA, 1L, "alumno1", "Alumno Uno", hashDemo, "ALUMNO");
        jdbc.update(UPSERT_CUENTA, 2L, "alumno2", "Alumno Dos", hashDemo, "ALUMNO");
        jdbc.update(UPSERT_CUENTA, 3L, "alumno3", "Alumno Tres", hashDemo, "ALUMNO");
        jdbc.update(UPSERT_CUENTA, 4L, "alumno4", "Alumno Cuatro", hashDemo, "ALUMNO");
        jdbc.update(UPSERT_CUENTA, 5L, "alumno5", "Alumno Cinco", hashDemo, "ALUMNO");
        jdbc.update(UPSERT_CUENTA, 10L, "organizador1", "Organizador Distrinubs", hashAdmin, "ORGANIZADOR");
        jdbc.update(UPSERT_CUENTA, 11L, "admin", "Administrador Distrinubs", hashAdmin, "ADMINISTRADOR");

        if (cuentasCarga > 0) {
            jdbc.update(SEMBRAR_CUENTAS_CARGA, hashDemo, cuentasCarga);
        }

        log.info("Siembra de cuentas de identidad aplicada ({} cuentas de carga)", cuentasCarga);
    }
}
