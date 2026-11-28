package mx.distrinubs.directorio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cloud.netflix.eureka.server.EurekaServerConfigBean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class DirectorioApplicationTest {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private EurekaServerConfigBean configuracion;

    @Test
    void respondeLaListaDeInstanciasEnJson() {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setAccept(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<String> respuesta =
                http.exchange("/eureka/apps", HttpMethod.GET, new HttpEntity<>(cabeceras), String.class);

        assertEquals(HttpStatus.OK, respuesta.getStatusCode());
        assertNotNull(respuesta.getBody());
        assertTrue(respuesta.getBody().contains("\"applications\""), "La lista debe venir en JSON");
    }

    @Test
    void registraUnaInstanciaYLaDevuelveEnLaLista() {
        String instancia = """
                {"instance": {
                    "instanceId": "prueba:1",
                    "hostName": "10.0.0.9",
                    "app": "PRUEBA",
                    "ipAddr": "10.0.0.9",
                    "vipAddress": "prueba",
                    "status": "UP",
                    "port": {"$": 8099, "@enabled": "true"},
                    "dataCenterInfo": {
                        "@class": "com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo",
                        "name": "MyOwn"
                    }
                }}
                """;
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        cabeceras.setAccept(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<String> alta =
                http.exchange("/eureka/apps/PRUEBA", HttpMethod.POST, new HttpEntity<>(instancia, cabeceras), String.class);
        assertEquals(HttpStatus.NO_CONTENT, alta.getStatusCode());

        ResponseEntity<String> lista =
                http.exchange("/eureka/apps", HttpMethod.GET, new HttpEntity<>(cabeceras), String.class);
        assertEquals(HttpStatus.OK, lista.getStatusCode());
        assertNotNull(lista.getBody());
        assertTrue(lista.getBody().contains("prueba:1"), "La instancia recién registrada debe verse de inmediato");
    }

    @Test
    void laSaludRespondeUp() {
        ResponseEntity<String> respuesta = http.getForEntity("/actuator/health", String.class);

        assertEquals(HttpStatus.OK, respuesta.getStatusCode());
        assertNotNull(respuesta.getBody());
        assertTrue(respuesta.getBody().contains("\"UP\""));
    }

    @Test
    void sacaProntoALasInstanciasQueDejanDeRenovar() {
        assertFalse(configuracion.shouldEnableSelfPreservation(), "La auto-preservación debe estar apagada");
        assertEquals(5000, configuracion.getEvictionIntervalTimerInMs());
        assertFalse(configuracion.shouldUseReadOnlyResponseCache(), "La lista no debe servirse desde la copia de 30 s");
    }
}
