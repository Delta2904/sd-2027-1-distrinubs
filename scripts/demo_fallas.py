#!/usr/bin/env python3
"""Script de demostración de fallas reales (Paso 4 de la etapa 02-separacion).

Responsable: Diego Contreras (@diegocontrras)
Área de defensa: Qué ve el usuario cuando algo falla.

Ejecuta y verifica los tres casos del Paso 4 contra el sistema real en Docker:
  Caso 1: Matar el worker que tiene el lease del envío (consulta la BD para
          identificar al lease-holder, usa SIGKILL, trata ERROR_SERVIDOR como
          falla y solo pasa con veredicto ACCEPTED).
  Caso 2: Apagar una réplica del gateway (docker stop a una réplica y comprobar que
          el balanceador sigue respondiendo HTTP 200 sin caída visible).
  Caso 3: Ver salir de Eureka a una instancia caída (docker kill a la instancia
          y consultar /eureka/apps hasta que responda 200 sin la instancia UP).

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


def nombre_contenedor(contenedor_id):
    """Obtiene el nombre de un contenedor Docker a partir de su ID."""
    try:
        res = subprocess.run(
            ["docker", "inspect", "--format", "{{.Name}}", contenedor_id],
            capture_output=True, text=True, timeout=5,
        )
        if res.returncode == 0:
            return res.stdout.strip().lstrip("/")
    except Exception:
        pass
    return contenedor_id


def docker_cmd(accion, contenedor_id):
    if accion == "kill":
        cmd = ["docker", "kill", "-s", "SIGKILL", contenedor_id]
    else:
        cmd = ["docker", accion, contenedor_id]
    res = subprocess.run(cmd, capture_output=True, text=True, timeout=15)
    return res.returncode == 0, (res.stderr or res.stdout).strip()


# -----------------------------------------------------------------------------
# CASO 1: Matar el worker que tiene el lease del envío
# -----------------------------------------------------------------------------
def caso_1_worker(base, espera_s):
    encabezado("CASO 1: Matar el worker que tiene el lease del envío")

    # 1.1 — Validar API y contenedores
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

    # 1.2 — Crear envío con código lento
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

    # 1.3 — Esperar a que un worker tome el envío
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

    # 1.4 — Identificar el worker que tiene el lease (consulta a la BD)
    paso("1.4", "Identificando worker con lease del envío en la base de datos")
    objetivo = None
    try:
        import psycopg2
        db_url = os.getenv("DATABASE_URL", "postgresql://postgres:postgres@localhost:5432/distrinubs")
        conn = psycopg2.connect(dsn=db_url)
        try:
            cur = conn.cursor()
            cur.execute(
                "SELECT worker_id FROM leases_evaluacion WHERE envio_id = %s",
                (envio_id,),
            )
            row = cur.fetchone()
            if row:
                lease_worker_id = str(row[0])
                info(f"Lease del envío {envio_id} pertenece a worker_id={lease_worker_id}")
                # Buscar el contenedor que coincida con ese worker_id
                for w in workers:
                    w_name = nombre_contenedor(w)
                    if lease_worker_id in w or lease_worker_id in w_name:
                        objetivo = w
                        break
                if not objetivo:
                    fallo(
                        f"El worker con lease ({lease_worker_id}) no corresponde a ningún "
                        f"contenedor activo: {[nombre_contenedor(w) for w in workers]}."
                    )
                    return False
            else:
                fallo(f"No se encontró lease para el envío {envio_id} en leases_evaluacion.")
                return False
        finally:
            conn.close()
    except ImportError:
        fallo(
            "psycopg2 no está instalado. Se necesita para consultar el lease-holder. "
            "Instálalo con: pip install psycopg2-binary"
        )
        return False
    except Exception as e:
        fallo(f"Error al consultar la base de datos para el lease: {e}")
        return False

    ok(f"Worker con lease identificado: {nombre_contenedor(objetivo)} ({objetivo[:12]})")

    # 1.5 — Matar el worker lease-holder con SIGKILL
    paso("1.5", f"Matando worker {nombre_contenedor(objetivo)} ({objetivo[:12]}) con SIGKILL")
    matado, err = docker_cmd("kill", objetivo)
    if not matado:
        fallo(f"No se pudo matar el contenedor {objetivo[:12]}: {err}")
        return False
    ok(f"Contenedor {objetivo[:12]} terminado abruptamente.")

    # 1.6 — Esperar veredicto ACCEPTED; ERROR_SERVIDOR = falla
    try:
        paso("1.6", f"Esperando veredicto ACCEPTED (máx. {espera_s} s); ERROR_SERVIDOR = falla")
        fin = time.time() + espera_s
        vio_error_servidor = False
        while time.time() < fin:
            st_g, detalle, _ = llamar_api(base, "GET", f"/api/envios/{envio_id}")
            if st_g == 200 and isinstance(detalle, dict):
                v = detalle.get("veredicto") or (detalle.get("resultado") or {}).get("veredicto")
                if v == "ACCEPTED":
                    ok(f"Envío {envio_id} terminó con veredicto ACCEPTED.")
                    if vio_error_servidor:
                        info("Se observó ERROR_SERVIDOR intermedio antes de la recuperación.")
                    return True
                if v == "ERROR_SERVIDOR":
                    if not vio_error_servidor:
                        info(f"Envío {envio_id} devolvió ERROR_SERVIDOR; esperando re-evaluación…")
                        vio_error_servidor = True
                elif v and v not in ("PENDIENTE",):
                    # Otro veredicto final que no es ACCEPTED
                    fallo(f"Envío {envio_id} terminó con veredicto {v} (se esperaba ACCEPTED).")
                    return False
            time.sleep(1)

        fallo(f"Timeout: el envío {envio_id} no obtuvo veredicto ACCEPTED tras {espera_s} s.")
        return False
    finally:
        info(f"Restaurando worker {objetivo[:12]}…")
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
# CASO 3: Ver salir de Eureka a una instancia caída (docker kill)
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

    # Mostrar instancias reales y buscar un contenedor que podamos matar
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

    # 3.2 — docker kill (no stop) para que no se dé de baja sola
    paso("3.2", f"Matando instancia '{objetivo_instancia}' (contenedor {objetivo_contenedor[:12]}) con docker kill")
    matado, err = docker_cmd("kill", objetivo_contenedor)
    if not matado:
        fallo(f"No se pudo matar el contenedor {objetivo_contenedor[:12]}: {err}")
        return False
    ok(f"Contenedor {objetivo_contenedor[:12]} terminado abruptamente (SIGKILL).")

    # 3.3 — Esperar a que Eureka saque la instancia; solo contar cuando responde 200
    try:
        paso("3.3", f"Esperando a que Eureka saque la instancia caída (máx. {espera_s} s)")
        fin = time.time() + espera_s
        salio = False
        consultas_200 = 0
        consultas_no_200 = 0
        while time.time() < fin:
            st_e, datos_e, _ = llamar_api(eureka_url, "GET", "/eureka/apps", timeout=3)

            # Solo contar una consulta como válida cuando Eureka responde 200
            if st_e != 200:
                consultas_no_200 += 1
                info(f"Eureka respondió HTTP {st_e}; no se cuenta como consulta válida.")
                time.sleep(2)
                continue

            consultas_200 += 1
            presente_up = False
            if isinstance(datos_e, dict):
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
            info(f"Instancia aún figura como UP en Eureka (consulta válida #{consultas_200}); esperando expiración de heartbeat…")
            time.sleep(2)

        if salio:
            ok(
                f"Instancia '{objetivo_instancia}' salió del estado UP en Eureka "
                f"({consultas_200} consultas válidas, {consultas_no_200} respuestas no-200 ignoradas)."
            )
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
