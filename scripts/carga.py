#!/usr/bin/env python3
"""Prueba de carga del marcador provisional (etapa 01-monolito).

Simula un concurso de N alumnos que envían soluciones y mide el retraso del
marcador: el tiempo entre que el servidor responde "recibido" a un envío y el
primer sondeo de la tabla de posiciones en que la fila del alumno ya lo refleja.
El objetivo del producto es que ese retraso quede bajo 5 minutos.

Solo usa la biblioteca estándar de Python 3.

Cada alumno simulado se comporta como el front: manda sus envíos, consulta el
estado de su último envío cada segundo y refresca la tabla cada 5 segundos.
Manda primero sus envíos fallidos y al final uno aceptado, así cada envío cambia
su fila de la tabla y se puede saber cuándo apareció.

Escenarios:
    rafaga     todos los alumnos mandan todos sus envíos a la vez (peor caso).
    sostenido  los envíos se reparten en --duracion segundos; cada alumno espera
               el veredicto de un envío antes de mandar el siguiente.

Requisitos: la aplicación levantada con cuentas de carga sin usar. Desde cero:
    PowerShell:  docker compose down -v; $env:SIEMBRA_CUENTAS_CARGA=200; docker compose up -d --build
    bash:        docker compose down -v && SIEMBRA_CUENTAS_CARGA=200 docker compose up -d --build

Uso:
    python3 scripts/carga.py --escenario rafaga        (en Windows: python scripts/carga.py ...)
    python3 scripts/carga.py --escenario sostenido --duracion 300

Termina con código 0 si el retraso máximo quedó bajo el objetivo y 1 si no.
"""
import argparse
import http.client
import json
import random
import re
import statistics
import sys
import threading
import time
import urllib.parse
import uuid

CODIGOS = {
    "ACCEPTED": "a, b = map(int, input().split())\nprint(a + b)\n",
    "WRONG_ANSWER": "a, b = map(int, input().split())\nprint(a - b)\n",
    "RUNTIME_ERROR": "a, b = map(int, input().split())\nprint(a // 0)\n",
    "ERROR_COMPILACION": "a, b = map(int, input().split()\nprint(a + b)\n",
    "TIME_LIMIT_EXCEEDED": "while True:\n    pass\n",
}
# Mezcla de los envíos fallidos.
FALLIDOS = ["WRONG_ANSWER", "RUNTIME_ERROR", "ERROR_COMPILACION", "TIME_LIMIT_EXCEEDED"]
PESOS_FALLIDOS = [70, 10, 10, 10]

SONDEO_ESTADO_S = 1.0   # como el front
REFRESCO_TABLA_S = 5.0  # como el front
SONDEO_MEDICION_S = 0.5  # resolución de la medición


class Cliente:
    """Una conexión HTTP persistente por hilo, para no agotar los puertos del sistema."""

    def __init__(self, base):
        u = urllib.parse.urlsplit(base)
        self.host, self.puerto = u.hostname, u.port or 80
        self.conexion = None

    def pedir(self, metodo, ruta, cuerpo=None, intentos=3):
        datos = json.dumps(cuerpo).encode("utf-8") if cuerpo is not None else None
        cabeceras = {"Content-Type": "application/json"} if datos else {}
        ultimo = None
        for _ in range(intentos):
            try:
                if self.conexion is None:
                    self.conexion = http.client.HTTPConnection(self.host, self.puerto, timeout=30)
                self.conexion.request(metodo, ruta, body=datos, headers=cabeceras)
                r = self.conexion.getresponse()
                texto = r.read().decode("utf-8")
                if r.status >= 500:
                    raise OSError(f"HTTP {r.status}")
                return r.status, (json.loads(texto) if texto else None)
            except (OSError, http.client.HTTPException, ValueError) as e:
                ultimo = e
                if self.conexion is not None:
                    self.conexion.close()
                self.conexion = None
                time.sleep(0.5)
        raise OSError(f"{metodo} {ruta}: {ultimo}")


class Medicion:
    """Lo que comparten los hilos. Se protege con un candado."""

    def __init__(self):
        self.candado = threading.Lock()
        self.recibido = {}    # (usuario, j) -> instante en que el servidor respondió "recibido"
        self.visible = {}     # (usuario, j) -> instante del primer sondeo de la tabla que lo refleja
        self.veredictos = []  # segundos entre "recibido" y ver el veredicto en el estado del envío
        self.errores = []
        self.terminado = threading.Event()

    def error(self, texto):
        with self.candado:
            self.errores.append(texto)


def reflejados(celda):
    """Cuántos envíos de un alumno refleja su celda del problema en la tabla.

    "-" es ninguno, "-N" son N fallidos y "✔ (+N, Mm)" son N fallidos más el aceptado.
    """
    celda = str(celda or "").strip()
    resuelto = re.search(r"\(\+(\d+)", celda)
    if resuelto:
        return int(resuelto.group(1)) + 1
    fallidos = re.fullmatch(r"-(\d+)", celda)
    return int(fallidos.group(1)) if fallidos else 0


def alumno(base, cuenta, problema_id, plan, horas, medicion, inicio, escenario):
    cliente = Cliente(base)
    usuario = cuenta["usuario"]
    ultima_tabla = time.monotonic() + random.uniform(0, REFRESCO_TABLA_S)

    def refrescar_tabla_si_toca():
        nonlocal ultima_tabla
        if time.monotonic() - ultima_tabla >= REFRESCO_TABLA_S:
            ultima_tabla = time.monotonic()
            cliente.pedir("GET", "/api/concursos/activo/tabla")

    def esperar_veredicto(envio_id, t_recibido):
        while not medicion.terminado.is_set():
            _, d = cliente.pedir("GET", f"/api/envios/{envio_id}")
            if d and d.get("veredicto"):
                with medicion.candado:
                    medicion.veredictos.append(time.monotonic() - t_recibido)
                return
            refrescar_tabla_si_toca()
            time.sleep(SONDEO_ESTADO_S)

    try:
        ultimo = None
        for j, (veredicto, hora) in enumerate(zip(plan, horas), start=1):
            espera = inicio + hora - time.monotonic()
            if espera > 0:
                time.sleep(espera)
            cuerpo = {"alumnoId": cuenta["id"], "problemaId": problema_id, "intentoId": str(uuid.uuid4()),
                      "lenguaje": "PYTHON3", "codigo": CODIGOS[veredicto]}
            estado, d = cliente.pedir("POST", "/api/envios", cuerpo)
            t_recibido = time.monotonic()
            if estado not in (200, 201) or not d or "envioId" not in d:
                medicion.error(f"{usuario}: POST respondió {estado}")
                return
            with medicion.candado:
                medicion.recibido[(usuario, j)] = t_recibido
            ultimo = (d["envioId"], t_recibido)
            if escenario == "sostenido":
                esperar_veredicto(*ultimo)
        if escenario == "rafaga" and ultimo:
            esperar_veredicto(*ultimo)
        # Sigue refrescando la tabla, como un alumno que la mira, hasta el final.
        while not medicion.terminado.is_set():
            refrescar_tabla_si_toca()
            time.sleep(SONDEO_ESTADO_S)
    except OSError as e:
        medicion.error(f"{usuario}: {e}")


def percentil(valores, p):
    orden = sorted(valores)
    return orden[min(len(orden) - 1, int(round(p / 100 * (len(orden) - 1))))]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--alumnos", type=int, default=200)
    ap.add_argument("--fallidos", type=int, default=3, help="envíos fallidos por alumno antes del aceptado")
    ap.add_argument("--escenario", choices=["rafaga", "sostenido"], default="rafaga")
    ap.add_argument("--duracion", type=int, default=300, help="segundos en que se reparten los envíos (sostenido)")
    ap.add_argument("--objetivo", type=int, default=300, help="retraso máximo aceptable, en segundos")
    ap.add_argument("--limite", type=int, default=1800, help="segundos máximos de la corrida")
    ap.add_argument("--semilla", type=int, default=2027)
    ap.add_argument("--salida", help="archivo JSON donde guardar el resumen")
    a = ap.parse_args()
    base = a.base.rstrip("/")
    random.seed(a.semilla)

    cliente = Cliente(base)
    _, concurso = cliente.pedir("GET", "/api/concursos/activo")
    problema = concurso["problemas"][0]
    _, cuentas = cliente.pedir("GET", "/api/cuentas-demo")
    _, tabla = cliente.pedir("GET", "/api/concursos/activo/tabla")
    usadas = {f["usuario"] for f in tabla["filas"] if reflejados(f.get(problema["letra"])) > 0}
    libres = [c for c in cuentas if c["usuario"].startswith("carga") and c["usuario"] not in usadas]
    if len(libres) < a.alumnos:
        print(f"Hacen falta {a.alumnos} cuentas de carga sin usar y hay {len(libres)}. Levanta desde cero "
              f"con SIEMBRA_CUENTAS_CARGA={a.alumnos} (ver el encabezado de este script).", file=sys.stderr)
        return 2
    participantes = libres[:a.alumnos]
    por_alumno = a.fallidos + 1

    medicion = Medicion()
    inicio = time.monotonic() + 2
    hilos = []
    conteo_plan = {}
    for cuenta in participantes:
        plan = random.choices(FALLIDOS, weights=PESOS_FALLIDOS, k=a.fallidos) + ["ACCEPTED"]
        for v in plan:
            conteo_plan[v] = conteo_plan.get(v, 0) + 1
        horas = [0.0] * por_alumno if a.escenario == "rafaga" else \
            sorted(random.uniform(0, a.duracion) for _ in range(por_alumno))
        h = threading.Thread(target=alumno, daemon=True,
                             args=(base, cuenta, problema["id"], plan, horas, medicion, inicio, a.escenario))
        h.start()
        hilos.append(h)

    total = len(participantes) * por_alumno
    nombres = {c["usuario"] for c in participantes}
    print(f"Escenario {a.escenario}: {len(participantes)} alumnos, {total} envíos {conteo_plan}")

    # Hilo principal: mide. Sondea la tabla y anota cuándo aparece cada envío.
    latencias_tabla, retraso_servidor, aviso, ultimo_avance = [], 0, False, time.monotonic()
    while len(medicion.visible) < total and time.monotonic() - inicio < a.limite:
        t0 = time.monotonic()
        try:
            _, tabla = cliente.pedir("GET", "/api/concursos/activo/tabla")
        except OSError as e:
            medicion.error(f"medición: {e}")
            time.sleep(SONDEO_MEDICION_S)
            continue
        ahora = time.monotonic()
        latencias_tabla.append(ahora - t0)
        retraso_servidor = max(retraso_servidor, tabla.get("retrasoSegundos") or 0)
        aviso = aviso or bool(tabla.get("avisoOrganizadores"))
        with medicion.candado:
            for fila in tabla["filas"]:
                if fila["usuario"] in nombres:
                    for j in range(1, reflejados(fila.get(problema["letra"])) + 1):
                        medicion.visible.setdefault((fila["usuario"], j), ahora)
            vistos = len(medicion.visible)
        if ahora - ultimo_avance >= 15:
            ultimo_avance = ahora
            print(f"  t={ahora - inicio:6.0f} s  en la tabla: {vistos}/{total}  "
                  f"retraso según el servidor: {tabla.get('retrasoSegundos')} s", flush=True)
        time.sleep(max(0, SONDEO_MEDICION_S - (time.monotonic() - t0)))
    fin = time.monotonic()
    medicion.terminado.set()

    with medicion.candado:
        retrasos = [medicion.visible[k] - medicion.recibido[k] for k in medicion.visible if k in medicion.recibido]
        sin_reflejar = total - len(medicion.visible)
        veredictos = list(medicion.veredictos)
        errores = list(medicion.errores)
        primero = min(medicion.recibido.values(), default=inicio)
    if not retrasos:
        print("Ningún envío apareció en la tabla.", file=sys.stderr)
        return 1

    resumen = {
        "escenario": a.escenario, "alumnos": len(participantes), "envios": total, "mezcla": conteo_plan,
        "duracion_s": round(fin - primero, 1),
        "envios_por_segundo": round(len(retrasos) / (fin - primero), 2),
        "retraso_marcador_s": {"p50": round(statistics.median(retrasos), 1), "p95": round(percentil(retrasos, 95), 1),
                               "max": round(max(retrasos), 1)},
        "espera_veredicto_s": ({"p50": round(statistics.median(veredictos), 1),
                                "p95": round(percentil(veredictos, 95), 1), "max": round(max(veredictos), 1)}
                               if veredictos else None),
        "retraso_max_segun_servidor_s": retraso_servidor, "aviso_organizadores": aviso,
        "latencia_tabla_s": {"p50": round(statistics.median(latencias_tabla), 3),
                             "p95": round(percentil(latencias_tabla, 95), 3), "max": round(max(latencias_tabla), 3)},
        "sin_reflejar": sin_reflejar, "errores": len(errores), "objetivo_s": a.objetivo,
    }
    resumen["cumple"] = sin_reflejar == 0 and not errores and resumen["retraso_marcador_s"]["max"] < a.objetivo
    print(json.dumps(resumen, indent=2, ensure_ascii=False))
    for e in errores[:10]:
        print("  error:", e, file=sys.stderr)
    if a.salida:
        with open(a.salida, "w", encoding="utf-8") as f:
            json.dump(resumen, f, indent=2, ensure_ascii=False)
    print("CUMPLE" if resumen["cumple"] else "NO CUMPLE",
          f"el objetivo de {a.objetivo} s (resolución de la medición: {SONDEO_MEDICION_S} s)")
    return 0 if resumen["cumple"] else 1


if __name__ == "__main__":
    sys.exit(main())
