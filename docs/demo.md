# Comandos de la demo

Desde la raíz del repositorio, en PowerShell. En bash, `python3` en lugar de `python` y `export SIEMBRA_CONTRASENA_DEMO=...` en el paso 4.

## Antes de presentar

1. Arranca desde cero (borra los datos anteriores):

   ```powershell
   docker compose down -v
   docker compose up -d --build
   ```

2. Revisa que los 12 contenedores digan `healthy`:

   ```powershell
   docker compose ps
   ```

3. Instala lo que pide la demo de fallas (una sola vez):

   ```powershell
   pip install psycopg2-binary
   ```

4. Deja la contraseña de `SIEMBRA_CONTRASENA_DEMO` del `.env` en una variable:

   ```powershell
   $env:SIEMBRA_CONTRASENA_DEMO = "<contraseña>"
   ```

5. Comprueba el recorrido normal:

   ```powershell
   python scripts/demo.py
   ```

## En vivo

6. Muestra el front: abre `http://localhost:8080`, entra como `alumno1` y sube un envío.

7. Corre la demo de fallas (worker muerto, réplica del gateway apagada e instancia caída en Eureka):

   ```powershell
   python scripts/demo_fallas.py --todos
   ```

8. Antes de repetirla, espera a que todo vuelva a `healthy` y 20 s más:

   ```powershell
   docker compose ps
   ```

## Si preguntan

9. Circuit breaker: mata `concursos`, pide su ruta diez veces y vuelve a arrancarlo:

   ```powershell
   docker kill (docker compose ps -q concursos)
   1..10 | ForEach-Object { curl.exe -s -o NUL -w "%{http_code} %{time_total}s`n" http://localhost:8080/api/concursos/activo }
   docker compose start concursos
   ```

10. Id de correlación: manda una petición con un id y búscalo en los logs:

    ```powershell
    curl.exe -s -H "X-Correlation-Id: demo-profe" http://localhost:8080/api/concursos/activo
    docker compose logs nginx gateway concursos | Select-String demo-profe
    ```

11. Sin token no entra (responde 401):

    ```powershell
    curl.exe -s -i http://localhost:8080/api/envios/1
    ```

12. Actuator no se ve desde afuera (responde 404):

    ```powershell
    curl.exe -s -i http://localhost:8080/actuator/health
    ```

13. Directorio y cola: abre `http://localhost:8761` (Eureka) y `http://localhost:15672` (RabbitMQ).

## Al terminar

14. Apaga todo (sin `-v`, los datos se conservan):

    ```powershell
    docker compose down
    ```

Si un contenedor se queda en `health: starting` más de un minuto con el log vacío, reinicia Docker Desktop y vuelve al paso 1. Si la demo de fallas falla en vivo, la salida real está en el respaldo E del pitch y en [evidencia/](evidencia/).
