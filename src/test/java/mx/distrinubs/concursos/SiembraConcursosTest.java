package mx.distrinubs.concursos;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import mx.distrinubs.BaseDatosDePrueba;

class SiembraConcursosTest {

    @Test
    @DisplayName("La siembra de concursos es idempotente: correrla dos veces no duplica nada")
    void sembrarDosVecesNoDuplica() {
        JdbcTemplate jdbc = BaseDatosDePrueba.jdbc();
        BaseDatosDePrueba.vaciar();
        SiembraConcursos siembra = new SiembraConcursos(jdbc.getDataSource());

        siembra.afterPropertiesSet();
        siembra.afterPropertiesSet();

        assertEquals("Distrinubs Round 1", jdbc.queryForObject(
                "SELECT nombre FROM concursos WHERE estado = 'ABIERTO'", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM concursos", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM problemas", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM casos_prueba", Integer.class));
    }
}
