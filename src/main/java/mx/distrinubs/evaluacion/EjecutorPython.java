package mx.distrinubs.evaluacion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Corre una solución en Python 3 contra casos de prueba y decide el veredicto.
 *
 * <p>Sin sandbox (PRD, sección 0): el código corre como proceso hijo de la
 * aplicación, en un directorio temporal y sin variables de entorno. Es un riesgo
 * declarado, no una condición de producción.
 */
@Component
class EjecutorPython {

    /** Salida más grande que se lee; más que esto es Wrong Answer sin compararla. */
    private static final long SALIDA_MAXIMA_BYTES = 16 * 1024 * 1024;
    private static final int DETALLE_MAXIMO = 2000;

    private final String python;
    private final long salvaguardaCompilacionMs;

    EjecutorPython(
            @Value("${evaluacion.python:python3}") String python,
            @Value("${evaluacion.salvaguarda-compilacion-ms:10000}") long salvaguardaCompilacionMs) {
        this.python = python;
        this.salvaguardaCompilacionMs = salvaguardaCompilacionMs;
    }

    record Caso(int orden, String entrada, String salidaEsperada) {}

    record Evaluacion(String veredicto, Integer tiempoMs, String detalle) {}

    /** Falla del servidor, no del alumno: no se guarda resultado y no se penaliza. */
    static class FallaServidor extends Exception {
        FallaServidor(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }

    private record Corrida(boolean excedioTiempo, int codigoSalida, long tiempoMs, Path salida, Path errores) {}

    Evaluacion evaluar(String codigo, List<Caso> casos, int limiteTiempoMs) throws FallaServidor {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("envio-");
            Files.writeString(dir.resolve("solucion.py"), codigo, StandardCharsets.UTF_8);

            // Revisión de sintaxis. La salvaguarda es provisional y no es el tope
            // de compilación de la sección 10 (PRD, bitácora 10).
            Corrida compilacion = correr(dir, List.of(python, "-I", "-m", "py_compile", "solucion.py"),
                    null, "compilacion", salvaguardaCompilacionMs);
            if (compilacion.excedioTiempo()) {
                throw new FallaServidor("La revisión de sintaxis excedió " + salvaguardaCompilacionMs + " ms", null);
            }
            if (compilacion.codigoSalida() != 0) {
                return new Evaluacion("ERROR_COMPILACION", null, recortar(leer(compilacion.errores())));
            }

            long tiempoMaximo = 0;
            for (Caso caso : casos) {
                Path entrada = dir.resolve("entrada-" + caso.orden() + ".txt");
                Files.writeString(entrada, caso.entrada(), StandardCharsets.UTF_8);
                Corrida corrida = correr(dir, List.of(python, "-I", "solucion.py"),
                        entrada, "caso-" + caso.orden(), limiteTiempoMs);
                tiempoMaximo = Math.max(tiempoMaximo, corrida.tiempoMs());

                // Con WA, TLE y RE el alumno solo ve el veredicto (PRD, bitácora 9).
                if (corrida.excedioTiempo()) {
                    return new Evaluacion("TIME_LIMIT_EXCEEDED", (int) corrida.tiempoMs(), null);
                }
                if (corrida.codigoSalida() != 0) {
                    return new Evaluacion("RUNTIME_ERROR", (int) tiempoMaximo, null);
                }
                if (Files.size(corrida.salida()) > SALIDA_MAXIMA_BYTES
                        || !normalizar(leer(corrida.salida())).equals(normalizar(caso.salidaEsperada()))) {
                    return new Evaluacion("WRONG_ANSWER", (int) tiempoMaximo, null);
                }
            }
            return new Evaluacion("ACCEPTED", (int) tiempoMaximo, null);

        } catch (IOException e) {
            throw new FallaServidor("No se pudo preparar o lanzar Python: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FallaServidor("La evaluación se interrumpió", e);
        } finally {
            borrar(dir);
        }
    }

    /**
     * Compara sin espacios al final de cada línea ni líneas vacías al final, y
     * acepta saltos de línea de Windows (PRD, bitácora 11).
     */
    static String normalizar(String texto) {
        return texto.lines().map(String::stripTrailing).collect(Collectors.joining("\n")).stripTrailing();
    }

    private Corrida correr(Path dir, List<String> comando, Path entrada, String nombre, long limiteMs)
            throws IOException, InterruptedException {
        Path salida = dir.resolve(nombre + ".salida");
        Path errores = dir.resolve(nombre + ".errores");
        ProcessBuilder pb = new ProcessBuilder(comando)
                .directory(dir.toFile())
                .redirectOutput(salida.toFile())
                .redirectError(errores.toFile());
        if (entrada != null) {
            pb.redirectInput(entrada.toFile());
        }
        // El código del alumno no hereda las credenciales de la base.
        pb.environment().clear();

        long inicio = System.nanoTime();
        Process proceso = pb.start();
        if (entrada == null) {
            proceso.getOutputStream().close();
        }
        boolean termino = proceso.waitFor(limiteMs, TimeUnit.MILLISECONDS);
        long tiempoMs = (System.nanoTime() - inicio) / 1_000_000;
        if (!termino) {
            proceso.descendants().forEach(ProcessHandle::destroyForcibly);
            proceso.destroyForcibly();
            proceso.waitFor(1, TimeUnit.SECONDS);
            return new Corrida(true, -1, tiempoMs, salida, errores);
        }
        return new Corrida(false, proceso.exitValue(), tiempoMs, salida, errores);
    }

    private static String leer(Path archivo) throws IOException {
        return new String(Files.readAllBytes(archivo), StandardCharsets.UTF_8);
    }

    private static String recortar(String texto) {
        String limpio = texto.strip();
        return limpio.length() <= DETALLE_MAXIMO ? limpio : limpio.substring(0, DETALLE_MAXIMO) + "…";
    }

    private static void borrar(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> archivos = Files.walk(dir)) {
            archivos.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignorada) {
            // Un directorio temporal que no se borra no afecta el veredicto.
        }
    }
}
