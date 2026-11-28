package mx.distrinubs.iniciosesion;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

/**
 * Siembra las cuentas de demo al arrancar la aplicación, después de las
 * migraciones y antes de que abra el puerto.
 *
 * <p>Excepción temporal a la regla del dueño de datos: {@code cuentas} es de
 * Inicio de sesión, pero en la etapa 2 (paso 1) este servicio todavía siembra
 * las cuentas de demo, hasta que pasen al servicio {@code identidad}. Es
 * idempotente: ids fijos y upsert por id.
 *
 * <p>Para la prueba de carga puede agregar cuentas {@code carga001},
 * {@code carga002}, … con ids desde 101, solo si se pide con
 * {@code siembra.cuentas-carga}.
 */
@Component
@DependsOnDatabaseInitialization
@ConditionalOnProperty(name = "siembra.activa", havingValue = "true", matchIfMissing = true)
class SiembraCuentasDemo implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SiembraCuentasDemo.class);

    /** Los ids de la siembra van del 1 al 999 y las cuentas de carga empiezan en 101. */
    private static final int MAXIMO_CUENTAS_CARGA = 899;

    private static final String SEMBRAR_CUENTAS_CARGA = """
            INSERT INTO cuentas (id, usuario, nombre)
            SELECT 100 + n, 'carga' || lpad(n::text, 3, '0'), 'Alumno de carga ' || n
            FROM generate_series(1, ?) AS n
            ON CONFLICT (id) DO UPDATE
                SET usuario = EXCLUDED.usuario,
                    nombre  = EXCLUDED.nombre
            """;

    private final DataSource fuente;
    private final int cuentasCarga;

    SiembraCuentasDemo(DataSource fuente, @Value("${siembra.cuentas-carga:0}") int cuentasCarga) {
        if (cuentasCarga < 0 || cuentasCarga > MAXIMO_CUENTAS_CARGA) {
            throw new IllegalArgumentException(
                    "siembra.cuentas-carga debe estar entre 0 y " + MAXIMO_CUENTAS_CARGA);
        }
        this.fuente = fuente;
        this.cuentasCarga = cuentasCarga;
    }

    @Override
    public void afterPropertiesSet() {
        ResourceDatabasePopulator siembra = new ResourceDatabasePopulator(new ClassPathResource("siembra/cuentas.sql"));
        siembra.setSqlScriptEncoding("UTF-8");
        siembra.execute(fuente);
        if (cuentasCarga > 0) {
            new JdbcTemplate(fuente).update(SEMBRAR_CUENTAS_CARGA, cuentasCarga);
        }
        log.info("Siembra de cuentas de demo aplicada ({} cuentas de carga)", cuentasCarga);
    }
}
