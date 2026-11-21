package mx.distrinubs.iniciosesion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import mx.distrinubs.BaseDatosDePrueba;

class SiembraCuentasDemoTest {

    private JdbcTemplate jdbc;

    @BeforeEach
    void preparar() {
        jdbc = BaseDatosDePrueba.jdbc();
        BaseDatosDePrueba.vaciar();
    }

    @Test
    @DisplayName("La siembra de cuentas es idempotente: correrla dos veces deja las 5 cuentas de demo")
    void sembrarDosVecesNoDuplica() {
        SiembraCuentasDemo siembra = new SiembraCuentasDemo(jdbc.getDataSource(), 0);

        siembra.afterPropertiesSet();
        siembra.afterPropertiesSet();

        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM cuentas", Integer.class));
        assertEquals("Alumno Uno", jdbc.queryForObject("SELECT nombre FROM cuentas WHERE id = 1", String.class));
    }

    @Test
    @DisplayName("Las cuentas de carga solo se siembran si se piden, con ids desde 101")
    void cuentasDeCargaSoloSiSePiden() {
        SiembraCuentasDemo siembra = new SiembraCuentasDemo(jdbc.getDataSource(), 200);

        siembra.afterPropertiesSet();
        siembra.afterPropertiesSet();

        assertEquals(205, jdbc.queryForObject("SELECT count(*) FROM cuentas", Integer.class));
        assertEquals("carga200", jdbc.queryForObject("SELECT usuario FROM cuentas WHERE id = 300", String.class));
    }
}
