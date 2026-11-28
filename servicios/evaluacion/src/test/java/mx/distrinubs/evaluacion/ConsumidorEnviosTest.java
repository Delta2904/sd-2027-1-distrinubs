package mx.distrinubs.evaluacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;

import mx.distrinubs.BaseDatosDePrueba;
import mx.distrinubs.evaluacion.ConsumidorEnvios.Decision;

/**
 * Qué hace el consumidor con cada mensaje, contra un PostgreSQL real y sin
 * broker: el canal de RabbitMQ es un doble.
 */
class ConsumidorEnviosTest {

    private static final int MAX_TOMAS = 3;
    private static final long ETIQUETA = 7;

    private JdbcTemplate jdbc;

    @BeforeEach
    void preparar() {
        jdbc = BaseDatosDePrueba.jdbc();
        BaseDatosDePrueba.vaciar();
        BaseDatosDePrueba.sembrarMinimo(1000);
    }

    private ConsumidorEnvios consumidor(EjecutorPython ejecutor) {
        return new ConsumidorEnvios(new WorkerEvaluacion(jdbc, ejecutor, 0, MAX_TOMAS), new ObjectMapper());
    }

    @Test
    @DisplayName("Confirma el mensaje después de guardar el veredicto")
    void confirmaAlGuardar() throws Exception {
        long envio = BaseDatosDePrueba.encolarEnvio();
        Channel canal = mock(Channel.class);

        consumidor(new EjecutoresFalsos.QueFalla(0)).recibir(mensaje("{\"envioId\":" + envio + "}"), canal);

        verify(canal).basicAck(ETIQUETA, false);
        verify(canal, never()).basicReject(anyLong(), anyBoolean());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM resultados", Integer.class));
    }

    @Test
    @DisplayName("Ante una falla del servidor devuelve el mensaje a la cola para otro worker")
    void devuelveAnteFalla() throws Exception {
        long envio = BaseDatosDePrueba.encolarEnvio();
        Channel canal = mock(Channel.class);

        consumidor(new EjecutoresFalsos.QueFalla(1)).recibir(mensaje("{\"envioId\":" + envio + "}"), canal);

        verify(canal).basicReject(ETIQUETA, true);
        verify(canal, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("Un mensaje sin envioId va a la cola de mensajes muertos")
    void malformadoVaALaColaDeMuertos() throws Exception {
        Channel canal = mock(Channel.class);

        consumidor(new EjecutoresFalsos.QueFalla(0)).recibir(mensaje("no es json"), canal);

        verify(canal).basicReject(ETIQUETA, false);
    }

    @Test
    @DisplayName("Entiende el mensaje que publica envios")
    void leeElMensajeDeEnvios() {
        ConsumidorEnvios c = consumidor(new EjecutoresFalsos.QueFalla(0));

        assertEquals(42L, c.leerEnvioId(mensaje("{\"envioId\":42}")));
        assertNull(c.leerEnvioId(mensaje("{\"otro\":1}")));
        Message soloEncabezado = mensaje("");
        soloEncabezado.getMessageProperties().setHeader("x-envio-id", 9L);
        assertEquals(9L, c.leerEnvioId(soloEncabezado));
    }

    @Test
    @DisplayName("Worker caído a media evaluación: el que recibe el mensaje espera su lease y lo evalúa una vez")
    void esperaElLeaseDeUnWorkerCaido() {
        long envio = BaseDatosDePrueba.encolarEnvio();
        jdbc.update("INSERT INTO leases_evaluacion (envio_id, worker, tomado_en, vence_en) "
                + "VALUES (?, 'worker-caido', now() - interval '1 second', now() + interval '400 milliseconds')",
                envio);
        EjecutoresFalsos.Contador ejecutor = new EjecutoresFalsos.Contador();

        long inicio = System.nanoTime();
        assertEquals(Decision.CONFIRMAR, consumidor(ejecutor).atender(envio, "w2"));
        long transcurridoMs = (System.nanoTime() - inicio) / 1_000_000;

        assertTrue(transcurridoMs >= 300, "no esperó al lease: " + transcurridoMs + " ms");
        assertEquals(1, ejecutor.evaluaciones.size());
        assertEquals(2, jdbc.queryForObject(
                "SELECT veces_tomado FROM leases_evaluacion WHERE envio_id = ?", Integer.class, envio));
    }

    @Test
    @DisplayName("Envío que no existe: lo intenta unas veces y descarta el mensaje")
    void descartaSiNoExiste() {
        assertEquals(Decision.CONFIRMAR, consumidor(new EjecutoresFalsos.QueFalla(0)).atender(999_999, "w1"));
    }

    @Test
    @DisplayName("4 consumidores, 200 envíos con cada mensaje entregado dos veces: cada envío se evalúa una sola vez")
    void mensajesDuplicadosNoEvaluanDosVeces() throws Exception {
        // Lease corto: el duplicado que encuentra un lease ajeno no espera mucho.
        jdbc.update("UPDATE problemas SET limite_tiempo_ms = 50");
        List<Long> envios = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            long id = BaseDatosDePrueba.encolarEnvio();
            // El código distingue a cada envío para contar sus evaluaciones.
            jdbc.update("UPDATE envios SET codigo = ? WHERE id = ?", "envio-" + id, id);
            envios.add(id);
            envios.add(id);
        }
        EjecutoresFalsos.Contador ejecutor = new EjecutoresFalsos.Contador();
        ConsumidorEnvios c = consumidor(ejecutor);

        int consumidores = 4;
        CountDownLatch salida = new CountDownLatch(1);
        ExecutorService hilos = Executors.newFixedThreadPool(consumidores);
        List<Future<List<Decision>>> trabajos = new ArrayList<>();
        for (int w = 0; w < consumidores; w++) {
            int numero = w;
            trabajos.add(hilos.submit(() -> {
                salida.await();
                List<Decision> decisiones = new ArrayList<>();
                // Cada consumidor recibe mensajes alternados: los dos de un mismo envío caen en hilos distintos.
                for (int i = numero; i < envios.size(); i += consumidores) {
                    decisiones.add(c.atender(envios.get(i), "w" + numero));
                }
                return decisiones;
            }));
        }
        salida.countDown();
        for (Future<List<Decision>> trabajo : trabajos) {
            assertTrue(trabajo.get(120, TimeUnit.SECONDS).stream().allMatch(d -> d == Decision.CONFIRMAR));
        }
        hilos.shutdown();

        assertEquals(200, jdbc.queryForObject("SELECT count(*) FROM resultados", Integer.class));
        assertEquals(200, ejecutor.evaluaciones.size());
        assertTrue(ejecutor.evaluaciones.values().stream().allMatch(veces -> veces == 1),
                "algún envío se evaluó más de una vez");
    }

    private static Message mensaje(String cuerpo) {
        MessageProperties propiedades = new MessageProperties();
        propiedades.setDeliveryTag(ETIQUETA);
        return new Message(cuerpo.getBytes(StandardCharsets.UTF_8), propiedades);
    }
}
