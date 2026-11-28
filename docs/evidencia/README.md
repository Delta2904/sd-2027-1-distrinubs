# Evidencia de la verificación del 28/11/2026

Salidas de la prueba final de la etapa 2, corrida desde cero con `docker compose down -v` y `docker compose up -d --build` en una computadora con Docker Desktop (32 CPU y 16 GB asignados).

| Archivo | Qué trae |
|---|---|
| `2026-11-28-arranque.txt` | Segundos hasta que los 12 contenedores quedaron sanos y la salida de `docker compose ps` |
| `2026-11-28-demo.txt` | Salida completa de `scripts/demo.py` |
| `2026-11-28-demo-fallas-resumen.txt` | Resumen de tres corridas seguidas de `scripts/demo_fallas.py --todos`, con los tiempos medidos |
| `2026-11-28-demo-fallas-corrida-1.txt`, `-2.txt` y `-3.txt` | Salida completa de cada corrida |
| `2026-11-28-caso2-diez-corridas.txt` | El caso de la réplica del gateway apagada, diez veces seguidas, con el código y el tiempo de cada petición |
| `2026-11-28-circuit-breaker.txt` | El circuit breaker del gateway con `concursos` muerto: código, tiempo y cuerpo de cada petición |
| `2026-11-28-cola-mensajes-muertos.txt` | Un mensaje sin `envioId` publicado a mano termina en `envios.evaluacion.dlq` |
| `2026-11-28-id-correlacion.txt` | Una petición con un id de correlación conocido, y ese id en los logs de nginx, del gateway, de `identidad` y de `envios` |
| `2026-11-28-pruebas-por-modulo.txt` | Conteo de pruebas de cada módulo, corridas dentro de Docker con JDK 21 |

Las corridas se hicieron con `COMPOSE_PROJECT_NAME=distrinubs-cierre`, para no borrar el volumen de desarrollo. Por eso los contenedores aparecen como `distrinubs-cierre-<servicio>-<n>`; con el nombre por omisión se llaman `sd-2027-1-distrinubs-<servicio>-<n>`.

Las pruebas del circuit breaker y de la cola de mensajes muertos se hicieron en corridas anteriores del mismo día; el código de esas piezas no cambió después.
