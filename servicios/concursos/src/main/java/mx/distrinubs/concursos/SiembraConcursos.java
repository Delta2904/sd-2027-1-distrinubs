package mx.distrinubs.concursos;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

/**
 * Siembra el concurso de demo, su problema y sus casos de prueba al arrancar
 * la aplicación, después de las migraciones y antes de que abra el puerto.
 *
 * <p>En 01 no hay interfaz de organizador: el concurso lo crea esta siembra.
 * Es idempotente: ids fijos y upsert por id. Dueño de datos: Concursos y
 * problemas es el único que escribe {@code concursos}, {@code problemas} y
 * {@code casos_prueba}.
 */
@Component
@DependsOnDatabaseInitialization
@ConditionalOnProperty(name = "siembra.activa", havingValue = "true", matchIfMissing = true)
class SiembraConcursos implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SiembraConcursos.class);

    private final DataSource fuente;

    SiembraConcursos(DataSource fuente) {
        this.fuente = fuente;
    }

    @Override
    public void afterPropertiesSet() {
        ResourceDatabasePopulator siembra = new ResourceDatabasePopulator(new ClassPathResource("siembra/concursos.sql"));
        siembra.setSqlScriptEncoding("UTF-8");
        siembra.execute(fuente);
        log.info("Siembra de concursos aplicada");
    }
}
