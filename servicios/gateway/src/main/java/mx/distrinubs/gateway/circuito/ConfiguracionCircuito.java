package mx.distrinubs.gateway.circuito;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;

/**
 * Umbrales del circuit breaker que protege cada ruta del gateway.
 *
 * <p>El circuito cuenta como falla una petición que no llegó al servicio: conexión
 * rechazada, tiempo de espera agotado o servicio sin instancias en el directorio.
 * Una respuesta del servicio, aunque sea un error, no cuenta: el servicio contestó.
 *
 * <p>Con el circuito abierto el gateway ya no intenta la llamada y responde de
 * inmediato con el aviso de {@link ServicioNoDisponibleController}. Pasado el
 * tiempo de espera deja pasar unas llamadas de prueba: si salen bien, cierra.
 */
@Configuration
public class ConfiguracionCircuito {

    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> umbralesDelCircuito(
            @Value("${distrinubs.circuito.ventana-llamadas:10}") int ventanaLlamadas,
            @Value("${distrinubs.circuito.minimo-llamadas:5}") int minimoLlamadas,
            @Value("${distrinubs.circuito.porcentaje-fallas:50}") float porcentajeFallas,
            @Value("${distrinubs.circuito.espera-abierto:10s}") Duration esperaAbierto,
            @Value("${distrinubs.circuito.llamadas-de-prueba:2}") int llamadasDePrueba,
            @Value("${distrinubs.circuito.tiempo-maximo:8s}") Duration tiempoMaximo) {
        CircuitBreakerConfig circuito = CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(ventanaLlamadas)
                .minimumNumberOfCalls(minimoLlamadas)
                .failureRateThreshold(porcentajeFallas)
                .waitDurationInOpenState(esperaAbierto)
                .permittedNumberOfCallsInHalfOpenState(llamadasDePrueba)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        // Mayor que el tiempo de respuesta del cliente HTTP del gateway: así el
        // que corta una llamada lenta es ese cliente, y el circuito solo la cuenta.
        TimeLimiterConfig limite = TimeLimiterConfig.custom()
                .timeoutDuration(tiempoMaximo)
                .build();
        return fabrica -> fabrica.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(circuito)
                .timeLimiterConfig(limite)
                .build());
    }
}
