#!/usr/bin/env python3
"""Script de demostración de la etapa 01-monolito (PRD, sección 0, punto 8).

Recorre de punta a punta los puntos 1 a 6 llamando a la API, solo con la
biblioteca estándar de Python 3. Se puede correr varias veces: cada corrida usa
intentoId nuevos y las comprobaciones sobre la tabla son relativas, no absolutas.

Requisitos: `docker compose up` corriendo, la siembra de concurso y de cuentas
ya aplicada, y el worker de evaluación activo.

Uso:
    python3 scripts/demo.py
    python3 scripts/demo.py --base http://localhost:8080 \
        --correcta soluciones/a_correcta.py --incorrecta soluciones/a_incorrecta.py

Las soluciones por omisión (suma de dos enteros) son un ejemplo: deben
reemplazarse con --correcta y --incorrecta si el problema sembrado es otro.
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request
import uuid

CORRECTA_POR_OMISION = "a, b = map(int, input().split())\nprint(a + b)\n"
INCORRECTA_POR_OMISION = "a, b = map(int, input().split())\nprint(a - b)\n"
ESTADOS_EN_CURSO = {"recibido", "en cola", "evaluando"}


class FallaDemo(Exception):
    pass


def paso(numero, texto):
    print(f"\n[{numero}] {texto}")


def ok(texto):
    print(f"    OK  {texto}")


def exigir(condicion, mensaje):
    if not condicion:
        raise FallaDemo(mensaje)


def normalizar(texto):
    return str(texto or "").strip().lower().replace("_", " ").replace("-", " ")


def llamar(base, metodo, ruta, cuerpo=None, timeout=10):
    datos = json.dumps(cuerpo).encode("utf-8") if cuerpo is not None else None
    peticion = urllib.request.Request(
        base + ruta, data=datos, method=metodo,
        headers={"Content-Type": "application/json"} if datos else {},
    )
    try:
        with urllib.request.urlopen(peticion, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode("utf-8") or "null")
    except urllib.error.HTTPError as e:
        raise FallaDemo(f"{metodo} {ruta} respondió {e.code}: {e.read().decode('utf-8', 'replace')[:200]}")
    except urllib.error.URLError as e:
        raise FallaDemo(f"No se pudo llegar a {base}{ruta}: {e.reason}. ¿Está corriendo docker compose up?")


def extraer_veredicto(d):
    v = d.get("veredicto")
    if not v and isinstance(d.get("resultado"), dict):
        v = d["resultado"].get("veredicto")
    if not v and isinstance(d.get("resultado"), str):
        v = d["resultado"]
    if not v and normalizar(d.get("estado")) not in ESTADOS_EN_CURSO:
        v = d.get("estado")
    return v


def esperar_veredicto(base, envio_id, limite_s):
    fin = time.time() + limite_s
    ultimo = None
    while time.time() < fin:
        _, d = llamar(base, "GET", f"/api/envios/{envio_id}")
        ultimo = d.get("estado")
        v = extraer_veredicto(d)
        if v:
            return normalizar(v)
        time.sleep(1)
    raise FallaDemo(f"El envío {envio_id} no tuvo veredicto en {limite_s} s (último estado: {ultimo}).")


def enviar(base, alumno_id, problema_id, codigo, intento_id=None):
    intento_id = intento_id or str(uuid.uuid4())
    cuerpo = {
        "alumnoId": alumno_id, "problemaId": problema_id,
        "intentoId": intento_id, "lenguaje": "PYTHON3", "codigo": codigo,
    }
    estado_http, d = llamar(base, "POST", "/api/envios", cuerpo)
    return intento_id, cuerpo, estado_http, d


def filas_de(datos):
    if isinstance(datos, list):
        return datos
    for clave in ("filas", "posiciones", "tabla", "participantes"):
        if isinstance(datos, dict) and isinstance(datos.get(clave), list):
            return datos[clave]
    return []


def leer(ruta, por_omision):
    if not ruta:
        return por_omision
    with open(ruta, encoding="utf-8") as f:
        return f.read()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--correcta", help="archivo con una solución correcta (Python 3)")
    ap.add_argument("--incorrecta", help="archivo con una solución incorrecta (Python 3)")
    ap.add_argument("--espera", type=int, default=60, help="segundos máximos esperando cada veredicto")
    a = ap.parse_args()
    base = a.base.rstrip("/")
    correcta = leer(a.correcta, CORRECTA_POR_OMISION)
    incorrecta = leer(a.incorrecta, INCORRECTA_POR_OMISION)

    # Punto 1: concurso abierto con problemas (lo siembra Joshua).
    paso(1, "Concurso abierto con sus problemas")
    _, concurso = llamar(base, "GET", "/api/concursos/activo")
    problemas = concurso.get("problemas") or []
    exigir(problemas, "El concurso activo no trae problemas. ¿Corriste la siembra del concurso?")
    problema = problemas[0]
    ok(f"{concurso.get('nombre', 'concurso')}: problema {problema.get('letra')} · {problema.get('titulo')}")

    # Punto 2: identidad de demo (cuentas sembradas por scripts/siembra-cuentas.sql).
    paso(2, "Cuentas de demo")
    _, cuentas = llamar(base, "GET", "/api/cuentas-demo")
    exigir(len(cuentas) >= 2, "Hacen falta al menos 2 cuentas. ¿Corriste scripts/siembra-cuentas.sql?")
    alumno = cuentas[0]
    ok(f"{len(cuentas)} cuentas; se usará {alumno['usuario']} (id {alumno['id']})")

    # Punto 3: "recibido" con identificador; un reintento con el mismo intentoId devuelve el mismo envío.
    paso(3, "Subir envío incorrecto y reintentar con el mismo intentoId")
    intento, cuerpo, http1, d1 = enviar(base, alumno["id"], problema["id"], incorrecta)
    exigir(http1 == 201 and "envioId" in d1, f"Se esperaba 201 con envioId; llegó {http1}: {d1}")
    exigir(normalizar(d1.get("estado")) == "recibido", f"Se esperaba estado 'recibido'; llegó {d1.get('estado')!r}")
    ok(f"recibido, envío {d1['envioId']}")
    _, d1b = llamar(base, "POST", "/api/envios", cuerpo)
    exigir(d1b.get("envioId") == d1["envioId"], f"El reintento creó otro envío: {d1b.get('envioId')} ≠ {d1['envioId']}")
    ok("el reintento con el mismo intentoId devolvió el mismo envío")

    # Puntos 4 y 5: la evaluación lo juzga; se ve estado y veredicto.
    paso(4, "Evaluación y veredicto del envío incorrecto")
    v1 = esperar_veredicto(base, d1["envioId"], a.espera)
    exigir(v1 == "wrong answer", f"Se esperaba Wrong Answer; llegó {v1!r}. ¿La solución --incorrecta corresponde al problema sembrado?")
    ok("Wrong Answer")

    paso(5, "Subir envío correcto y ver su veredicto")
    _, _, http2, d2 = enviar(base, alumno["id"], problema["id"], correcta)
    exigir(http2 == 201, f"Se esperaba 201; llegó {http2}")
    v2 = esperar_veredicto(base, d2["envioId"], a.espera)
    exigir(v2 == "accepted", f"Se esperaba Accepted; llegó {v2!r}. ¿La solución --correcta corresponde al problema sembrado?")
    ok(f"envío {d2['envioId']}: Accepted")

    # Punto 6: la tabla refleja al alumno con al menos un problema resuelto.
    paso(6, "Tabla provisional")
    _, datos = llamar(base, "GET", "/api/concursos/activo/tabla")
    filas = filas_de(datos)
    exigir(filas, "La tabla vino vacía después de un envío aceptado.")
    texto_tabla = json.dumps(filas, ensure_ascii=False).lower()
    exigir(alumno["usuario"].lower() in texto_tabla or alumno["nombre"].lower() in texto_tabla,
           f"{alumno['usuario']} no aparece en la tabla.")
    ok(f"la tabla tiene {len(filas)} fila(s) y {alumno['usuario']} aparece en ella")
    print(json.dumps(filas, indent=2, ensure_ascii=False))

    print("\nDemostración completa: puntos 1 a 6 recorridos.")


if __name__ == "__main__":
    try:
        main()
    except FallaDemo as e:
        print(f"\nFALLÓ: {e}", file=sys.stderr)
        sys.exit(1)
