package mx.distrinubs.evaluacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class EjecutorPythonTest {

    @Test
    void ignoraEspaciosAlFinalDeLineaYLineasVaciasAlFinal() {
        assertEquals(EjecutorPython.normalizar("5\n"), EjecutorPython.normalizar("5   \r\n\n\n"));
        assertEquals(EjecutorPython.normalizar("1 2\n3\n"), EjecutorPython.normalizar("1 2  \n3"));
    }

    @Test
    void distingueContenidoDistinto() {
        assertNotEquals(EjecutorPython.normalizar("5\n"), EjecutorPython.normalizar("-1\n"));
        assertNotEquals(EjecutorPython.normalizar("1 2\n"), EjecutorPython.normalizar("1  2\n"));
        assertNotEquals(EjecutorPython.normalizar("5\n"), EjecutorPython.normalizar("\n5\n"));
    }
}
