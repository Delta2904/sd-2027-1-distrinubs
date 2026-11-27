#!/usr/bin/env python3
"""Script de demostración de fallas reales (Paso 4 de la etapa 02-separacion).

Responsable: Diego Contreras (@diegocontrras)
Área de defensa: Qué ve el usuario cuando algo falla.

Ejecuta y verifica los tres casos del Paso 4 contra el sistema real en Docker:
  Caso 1: Matar un worker a media evaluación (SIGKILL al contenedor worker, esperar
          vencimiento de lease y confirmar que otro worker entrega veredicto único).
  Caso 2: Apagar una réplica del gateway (docker stop a una réplica y comprobar que
          el balanceador sigue respondiendo HTTP 200 sin caída visible).
  Caso 3: Ver salir de Eureka a una instancia caída (detener una instancia registrada
          y consultar /eureka/apps hasta observar su salida del directorio).

Si las piezas necesarias (p. ej. réplicas de gateway, workers separados o Eureka)
todavía no existen o no están levantadas, el script lo indica claramente y NO
reporta éxito.

Uso:
  python3 scripts/demo_fallas.py --todos
  python3 scripts/demo_fallas.py --caso 1
  python3 scripts/demo_fallas.py --caso 2
  python3 scripts/demo_fallas.py --caso 3
"""

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

for _flujo in (sys.stdout, sys.stderr):
    if hasattr(_flujo, "reconfigure"):
        _flujo.reconfigure(encoding="utf-8")


def encabezado(titulo):
    sep = "=" * 76
    print(f"\n{sep}\n  {titulo.center(72)}\n{sep}\n")


def paso(num, texto):
    print(f"  [{num}] {texto}")


def ok(texto):
    print(f"      [OK]  {texto}")


def info(texto):
    print(f"      [INFO] {texto}")


def fallo(texto):
    print(f"      [FALLÓ] {texto}")


def no_disponible(texto):
    print(f"      [NO DISPONIBLE] {texto}")


def llamar_api(base, metodo, ruta, cuerpo=None, headers_extra=None, timeout=5):
    datos = json.dumps(cuerpo).encode("utf-8") if cuerpo is not None else None
    id_corr = str(uuid.uuid4())
    headers = {
        "X-Correlation-Id": id_corr,
        "Accept": "application/json",
    }
    if datos:
        headers["Content-Type"] = "application/json"
    if headers_extra:
        headers.update(headers_extra)

    req = urllib.request.Request(base + ruta, data=datos, method=metodo, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode("utf-8")
            return r.status, json.loads(raw) if raw else None, id_corr
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace"), id_corr
    except urllib.error.URLError as e:
        return 0, str(e.reason), id_corr


def contenedores_de(servicio):
    """Obtiene los IDs de contenedores en ejecución cuyo nombre coincide con el servicio."""
    for cmd in (
        ["docker", "compose", "ps", "-q", servicio],
        ["docker", "ps", "--filter", f"name={servicio}", "--filter", "status=running", "-q"],
    ):
        try:
            res = subprocess.run(cmd, capture_output=True, text=True, timeout=5)
            if res.returncode == 0:
                ids = [line.strip() for line in res.stdout.splitlines() if line.strip()]
                if ids:
                    return ids
        except Exception:
            pass
    return []


def docker_cmd(accion, contenedor_id):
    if accion == "kill":
        cmd = ["docker", "kill", "-s", "SIGKILL", contenedor_id]
    else:
        cmd = ["docker", accion, contenedor_id]
    res = subprocess.run(cmd, capture_output=True, text=True, timeout=15)
    return res.returncode == 0, (res.stderr or res.stdout).strip()


# -----------------------------------------------------------------------------
# CASO 1: Matar un worker a media evaluación
# -----------------------------------------------------------------------------
def caso_1_worker(base, espera_s):
    encabezado("CASO 1: Matar un worker a media evaluación")

    paso("1.1", "Comprobando API y contenedores de evaluación")
    st, concurso, _ = llamar_api(base, "GET", "/api/concursos/activo")
    if st != 200 or not isinstance(concurso, dict) or not concurso.get("problemas"):
        no_disponible(f"La API en {base} no respondió con concurso activo (HTTP {st}).")
        return False

    st_c, cuentas, _ = llamar_api(base, "GET", "/api/cuentas-demo")
    if st_c != 200 or not isinstance(cuentas, list) or not cuentas:
        no_disponible(f"No se pudieron obtener cuentas de demo (HTTP {st_c}).")
        return False

    workers = contenedores_de("evaluacion")
    if len(workers) < 2:
        no_disponible(
            f"Se encontraron {len(workers)} contenedor(es) 'evaluacion'. "
            "Se requieren al menos 2 réplicas para matar una y observar que otra retoma el envío."
        )
        return False

    problema_id = concurso["problemas"][0]["id"]
    alumno_id = cuentas[0]["id"]
    ok(f"Concurso listo (problema {problema_id}) y {len(workers)} workers detectados.")

    paso("1.2", "Subiendo solución lenta (4 s) para interceptar el estado 'evaluando'")
    codigo_lento = (
        "import time\n"
        "time.sleep(4)\n"
        "a, b = map(int, input().split())\n"
        "print(a + b)\n"
    )
    st_e, env, _ = llamar_api(
        base,
        "POST",
        "/api/envios",
        {
            "alumnoId": alumno_id,
            "problemaId": problema_id,
            "intentoId": str(uuid.uuid4()),
            "lenguaje": "PYTHON3",
            "codigo": codigo_lento,
        },
    )
    if st_e not in (200, 201) or not isinstance(env, dict) or "envioId" not in env:
        fallo(f"No se pudo crear el envío (HTTP {st_e}): {env}")
        return False

    envio_id = env["envioId"]
    ok(f"Envío {envio_id} creado (estado inicial: {env.get('estado')}).")

    paso("1.3", "Esperando a que un worker tome el envío (estado = 'evaluando')")
    fin_espera = time.time() + 15
    en_evaluacion = False
    while time.time() < fin_espera:
        st_g, detalle, _ = llamar_api(base, "GET", f"/api/envios/{envio_id}")
        if st_g == 200 and isinstance(detalle, dict) and detalle.get("estado") == "evaluando":
            en_evaluacion = True
            break
        time.sleep(0.3)

    if not en_evaluacion:
        fallo(f"El envío {envio_id} no pasó a estado 'evaluando' dentro de 15 s.")
        return False
    ok(f"Envío {envio_id} en estado 'evaluando'.")

    objetivo = workers[0]
    paso("1.4", f"Matando worker {objetivo[:12]} con SIGKILL a media evaluación")
    matado, err = docker_cmd("kill", objetivo)
    if not matado:
        fallo(f"No se pudo matar el contenedor {objetivo[:12]}: {err}")
        return False
    ok(f"Contenedor {objetivo[:12]} terminado abruptamente.")

    try:
        paso("1.5", f"Esperando vencimiento de lease y re-evaluación (máx. {espera_s} s)")
        fin = time.time() + espera_s
        veredicto = None
        while time.time() < fin:
            st_g, detalle, _ = llamar_api(base, "GET", f"/api/envios/{envio_id}")
            if st_g == 200 and isinstance(detalle, dict):
                v = detalle.get("veredicto") or (detalle.get("resultado") or {}).get("veredicto")
                if v:
                    veredicto = v
                    break
            time.sleep(1)

        if not veredicto:
            fallo(f"El envío {envio_id} no obtuvo veredicto tras {espera_s} s.")
            return False

        ok(f"Envío {envio_id} completado por otro worker con veredicto: {veredicto}")
        return True
    finally:
        info(f"Levantando nuevamente el worker {objetivo[:12]}...")
        docker_cmd("start", objetivo)


# -----------------------------------------------------------------------------
# CASO 2: Apagar una réplica del Gateway
# -----------------------------------------------------------------------------
def caso_2_gateway(base):
    encabezado("CASO 2: Apagar una réplica del Gateway")

    paso("2.1", "Verificando réplicas del servicio 'gateway' en Docker")
    replicas = contenedores_de("gateway")
    if len(replicas) < 2:
        no_disponible(
            f"Se encontraron {len(replicas)} contenedor(es) 'gateway'. "
            "El gateway en 2 réplicas todavía no existe o no está levantado; no se puede comprobar."
        )
        return False

    st, _, corr = llamar_api(base, "GET", "/api/concursos/activo")
    if st != 200:
        no_disponible(f"El punto de entrada {base} no respondió HTTP 200 antes de la falla (HTTP {st}).")
        return False
    ok(f"{len(replicas)} réplicas activas y entrada respondiendo HTTP 200 (Id: {corr[:8]}…).")

    objetivo = replicas[0]
    paso("2.2", f"Apagando réplica del gateway {objetivo[:12]} (docker stop)")
    detenido, err = docker_cmd("stop", objetivo)
    if not detenido:
        fallo(f"No se pudo detener {objetivo[:12]}: {err}")
        return False
    ok(f"Réplica {objetivo[:12]} detenida.")

    try:
        paso("2.3", "Enviando 8 peticiones por la entrada con una réplica apagada")
        exitos = 0
        for i in range(1, 9):
            t0 = time.time()
            st_p, _, corr_p = llamar_api(base, "GET", "/api/concursos/activo")
            ms = int((time.time() - t0) * 1000)
            if st_p == 200:
                exitos += 1
                ok(f"Petición {i}/8 -> HTTP 200 ({ms} ms, Id: {corr_p[:8]}…)")
            else:
                fallo(f"Petición {i}/8 -> HTTP {st_p} ({ms} ms, Id: {corr_p[:8]}…)")

        if exitos == 8:
            ok("8/8 peticiones atendidas sin interrupción por la réplica superviviente.")
            return True
        fallo(f"Solo {exitos}/8 peticiones tuvieron éxito tras apagar la réplica.")
        return False
    finally:
        info(f"Restaurando réplica {objetivo[:12]}...")
        docker_cmd("start", objetivo)


# -----------------------------------------------------------------------------
# CASO 3: Ver salir de Eureka a una instancia caída
# -----------------------------------------------------------------------------
def caso_3_eureka(eureka_url, espera_s):
    encabezado("CASO 3: Ver salir de Eureka a una instancia caída")

    paso("3.1", f"Consultando directorio Eureka en {eureka_url}/eureka/apps")
    st, datos, _ = llamar_api(eureka_url, "GET", "/eureka/apps", timeout=3)
    if st != 200 or not isinstance(datos, dict):
        no_disponible(f"Eureka no está disponible en {eureka_url} (HTTP {st}).")
        return False

    apps = datos.get("applications", {}).get("application", [])
    if isinstance(apps, dict):
        apps = [apps]
    if not apps:
        no_disponible("Eureka respondió 200, pero no hay ninguna instancia registrada.")
        return False

    # Mostrar instancias reales y buscar un contenedor que podamos detener
    objetivo_app = None
    objetivo_instancia = None
    objetivo_contenedor = None

    for app in apps:
        nombre = app.get("name", "")
        instancias = app.get("instance", [])
        if isinstance(instancias, dict):
            instancias = [instancias]
        for inst in instancias:
            iid = inst.get("instanceId", "")
            estado = inst.get("status", "")
            info(f"Registrada en Eureka: {nombre} | {iid} | status={estado}")
            if not objetivo_contenedor and estado == "UP":
                cands = contenedores_de(nombre.lower())
                if cands:
                    objetivo_app = nombre
                    objetivo_instancia = iid
                    objetivo_contenedor = cands[0]

    if not objetivo_contenedor:
        no_disponible("No se encontró un contenedor Docker activo asociado a una instancia UP en Eureka.")
        return False

    paso("3.2", f"Deteniendo instancia '{objetivo_instancia}' (contenedor {objetivo_contenedor[:12]})")
    detenido, err = docker_cmd("stop", objetivo_contenedor)
    if not detenido:
        fallo(f"No se pudo detener el contenedor {objetivo_contenedor[:12]}: {err}")
        return False

    try:
        paso("3.3", f"Esperando a que Eureka saque la instancia caída (máx. {espera_s} s)")
        fin = time.time() + espera_s
        salio = False
        while time.time() < fin:
            st_e, datos_e, _ = llamar_api(eureka_url, "GET", "/eureka/apps", timeout=3)
            presente_up = False
            if st_e == 200 and isinstance(datos_e, dict):
                apps_e = datos_e.get("applications", {}).get("application", [])
                if isinstance(apps_e, dict):
                    apps_e = [apps_e]
                for app in apps_e:
                    if app.get("name") == objetivo_app:
                        insts = app.get("instance", [])
                        if isinstance(insts, dict):
                            insts = [insts]
                        for inst in insts:
                            if inst.get("instanceId") == objetivo_instancia and inst.get("status") == "UP":
                                presente_up = True
            if not presente_up:
                salio = True
                break
            info("Instancia aún figura como UP en Eureka; esperando expiración de heartbeat…")
            time.sleep(2)

        if salio:
            ok(f"Instancia '{objetivo_instancia}' salió del estado UP en Eureka.")
            return True

        fallo(f"La instancia '{objetivo_instancia}' siguió figurando UP tras {espera_s} s.")
        return False
    finally:
        info(f"Restaurando contenedor {objetivo_contenedor[:12]}...")
        docker_cmd("start", objetivo_contenedor)


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--base", default="http://localhost:8080", help="URL base del Gateway")
    parser.add_argument(
        "--eureka",
        default=os.environ.get("EUREKA_URL", "http://localhost:8761"),
        help="URL base de Eureka Server",
    )
    parser.add_argument("--caso", type=int, choices=[1, 2, 3], help="Ejecutar solo un caso (1, 2 o 3)")
    parser.add_argument("--todos", action="store_true", help="Ejecutar los 3 casos")
    parser.add_argument("--espera", type=int, default=60, help="Segundos máximos de espera por caso")
    args = parser.parse_args()

    base = args.base.rstrip("/")
    eureka = args.eureka.rstrip("/")

    encabezado("DISTRINUBS · DEMO DE FALLAS REALES (PASO 4)")
    print(f"Gateway: {base}")
    print(f"Eureka:  {eureka}")

    resultados = {}
    if args.caso in (None, 1) or args.todos:
        resultados["Caso 1 (Worker)"] = caso_1_worker(base, args.espera)
    if args.caso in (None, 2) or args.todos:
        resultados["Caso 2 (Gateway)"] = caso_2_gateway(base)
    if args.caso in (None, 3) or args.todos:
        resultados["Caso 3 (Eureka)"] = caso_3_eureka(eureka, args.espera)

    encabezado("RESUMEN REAL OBSERVADO")
    todos_ok = True
    for nombre, exito in resultados.items():
        etiqueta = "PASÓ" if exito else "NO SUPERADO / NO DISPONIBLE"
        print(f"  - {nombre}: {etiqueta}")
        if not exito:
            todos_ok = False

    if not todos_ok:
        print("\nAl menos un caso no se pudo comprobar o falló. Revisa los detalles arriba.\n")
        sys.exit(1)

    print("\nTodos los casos ejecutados pasaron sobre el sistema real.\n")


if __name__ == "__main__":
    main()
