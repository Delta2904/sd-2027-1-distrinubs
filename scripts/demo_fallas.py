#!/usr/bin/env python3
"""Script de demostración de fallas reales (Paso 4 de la etapa 02-separacion).

Responsable: Diego Contreras (@diegocontrras)
Área de defensa: Qué ve el usuario cuando algo falla.

Ejecuta y verifica los tres casos del Paso 4 contra el sistema real en Docker:
  Caso 1: Matar el worker que tiene el lease del envío. Sube una solución
          correcta pero lenta (duerme cerca del 70 % del límite de tiempo en
          cada caso), lee en la tabla leases_evaluacion qué worker la tomó,
          mata con SIGKILL el contenedor de ese worker y espera el veredicto.
          Solo pasa si llega ACCEPTED y el lease muestra la reentrega
          (veces_tomado creció o el worker es otro). ERROR_SERVIDOR es un
          resultado definitivo y cuenta como falla.
          Requiere psycopg2-binary (pip install psycopg2-binary) y acceso a
          PostgreSQL; la conexión se puede cambiar con DATABASE_URL.
          Si existe POST /api/auth/login, inicia sesión y manda el token en
          Authorization: Bearer; si no existe, sigue con una cuenta de demo.
  Caso 2: Apagar una réplica del gateway (docker stop a una réplica) y
          comprobar que la entrada sigue respondiendo HTTP 200.
  Caso 3: Ver salir de Eureka a una instancia caída. Empareja la instancia de
          Eureka con su contenedor por IP o hostname, mata ese contenedor con
          docker kill y consulta /eureka/apps hasta que la instancia deja de
          figurar UP. Informa cuántos segundos tardó en salir.

Si las piezas necesarias (réplicas del gateway, workers separados o Eureka)
todavía no existen o no están levantadas, el script lo indica como
"no disponible" y NO reporta éxito.

Uso:
  python3 scripts/demo_fallas.py --todos
  python3 scripts/demo_fallas.py --caso 1 --usuario alumno1 --contrasena <contraseña>
  python3 scripts/demo_fallas.py --caso 2 --servicio-gateway gateway
  python3 scripts/demo_fallas.py --caso 3 --app-eureka CONCURSOS --espera-eureka 180
"""

import argparse
import http.client
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

# Usuario, contraseña y base del docker-compose.yml, publicada en localhost:5432.
DB_URL_POR_OMISION = "postgresql://distrinubs:distrinubs@localhost:5432/distrinubs"

# Si el concurso no informa el límite de tiempo, la solución lenta duerme esto por caso.
DORMIR_POR_OMISION_MS = 700

# Hostname, nombre e IPs de un contenedor, en una sola línea.
FORMATO_INSPECT = (
    "{{.Id}}|{{.Config.Hostname}}|{{.Name}}|"
    "{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}"
)


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
    """Devuelve (estado HTTP, cuerpo, id de correlación). Estado 0 = no hubo respuesta útil."""
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
        try:
            cuerpo_error = e.read().decode("utf-8", "replace")
        except (OSError, http.client.HTTPException) as e_lectura:
            cuerpo_error = f"{type(e_lectura).__name__}: {e_lectura}"
        return e.code, cuerpo_error, id_corr
    except urllib.error.URLError as e:
        return 0, str(e.reason), id_corr
    except (TimeoutError, ConnectionError, http.client.HTTPException, ValueError) as e:
        # Timeout de lectura, conexión cortada o un 200 cuyo cuerpo no es JSON.
        return 0, f"{type(e).__name__}: {e}", id_corr


def contenedores_de(servicio=None):
    """IDs de los contenedores en ejecución de un servicio; sin servicio, todos los del proyecto."""
    if servicio:
        comandos = (
            ["docker", "compose", "ps", "-q", servicio],
            ["docker", "ps", "--filter", f"name={servicio}", "--filter", "status=running", "-q"],
        )
    else:
        comandos = (
            ["docker", "compose", "ps", "-q"],
            ["docker", "ps", "-q"],
        )
    for cmd in comandos:
        try:
            res = subprocess.run(cmd, capture_output=True, text=True, timeout=5)
            if res.returncode == 0:
                ids = [line.strip() for line in res.stdout.splitlines() if line.strip()]
                if ids:
                    return ids
        except Exception:
            pass
    return []


def datos_contenedores(ids):
    """Hostname, nombre e IPs de cada contenedor, con una sola llamada a docker inspect."""
    if not ids:
        return []
    try:
        res = subprocess.run(
            ["docker", "inspect", "--format", FORMATO_INSPECT, *ids],
            capture_output=True, text=True, timeout=10,
        )
    except Exception:
        return []
    datos = []
    for linea in res.stdout.splitlines():
        partes = linea.strip().split("|", 3)
        if len(partes) != 4:
            continue
        datos.append({
            "id": partes[0],
            "hostname": partes[1],
            "nombre": partes[2].lstrip("/"),
            "ips": partes[3].split(),
        })
    return datos


def contenedor_del_worker(worker, contenedores):
    """Devuelve el contenedor donde corre un worker, o None.

    El worker se llama worker-<hostname>-<uuid8>-<n>, y en Docker el hostname es,
    por omisión, el id corto del contenedor. Se busca el contenedor cuyo hostname
    o id corto aparece dentro del nombre del worker. Si coinciden varios, gana la
    coincidencia más larga.
    """
    worker = worker or ""
    mejor, largo_mejor = None, 0
    for contenedor in contenedores:
        for clave in (contenedor.get("hostname") or "", (contenedor.get("id") or "")[:12]):
            if clave and clave in worker and len(clave) > largo_mejor:
                mejor, largo_mejor = contenedor, len(clave)
    return mejor


def contenedor_de_instancia(instancia, contenedores):
    """Devuelve el contenedor de una instancia de Eureka, o None.

    Los servicios se registran con su IP, así que ipAddr u hostName es la IP del
    contenedor. También se acepta el hostname, y cualquiera de los dos como parte
    del instanceId (que tiene la forma host:aplicación:puerto).
    """
    pistas = {instancia.get("ipAddr"), instancia.get("hostName")}
    pistas.update(str(instancia.get("instanceId") or "").split(":"))
    pistas.discard(None)
    pistas.discard("")
    for contenedor in contenedores:
        propias = set(contenedor.get("ips") or [])
        if contenedor.get("hostname"):
            propias.add(contenedor["hostname"])
        if propias & pistas:
            return contenedor
    return None


def instancias_de_eureka(datos):
    """Aplana la respuesta de /eureka/apps en una lista de instancias."""
    if not isinstance(datos, dict):
        return []
    apps = (datos.get("applications") or {}).get("application") or []
    if isinstance(apps, dict):
        apps = [apps]
    instancias = []
    for app in apps:
        de_la_app = app.get("instance") or []
        if isinstance(de_la_app, dict):
            de_la_app = [de_la_app]
        for inst in de_la_app:
            instancias.append({
                "app": app.get("name", ""),
                "instanceId": inst.get("instanceId", ""),
                "status": inst.get("status", ""),
                "ipAddr": inst.get("ipAddr", ""),
                "hostName": inst.get("hostName", ""),
            })
    return instancias


def docker_cmd(accion, contenedor_id):
    if accion == "kill":
        cmd = ["docker", "kill", "-s", "SIGKILL", contenedor_id]
    else:
        cmd = ["docker", accion, contenedor_id]
    try:
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=15)
    except Exception as e:
        return False, f"{type(e).__name__}: {e}"
    return res.returncode == 0, (res.stderr or res.stdout).strip()


def iniciar_sesion(base, usuario, contrasena):
    """Inicia sesión si el servicio existe. Nunca imprime la contraseña ni el token.

    Devuelve (True, sesion) con el token y la cuenta, (True, None) si todavía no hay
    servicio de inicio de sesión, o (False, None) si el inicio de sesión falló.
    """
    st, cuerpo, _ = llamar_api(
        base, "POST", "/api/auth/login", {"usuario": usuario, "contrasena": contrasena or ""}
    )
    if st in (0, 404, 405, 503):
        detalle = f"HTTP {st}" if st else "sin conexión"
        info(f"Sin inicio de sesión (monolito): POST /api/auth/login no está disponible ({detalle}).")
        return True, None
    if st in (401, 403):
        if not contrasena:
            fallo(
                f"El inicio de sesión rechazó a '{usuario}' (HTTP {st}) y no se dio contraseña: "
                "pasa --contrasena o define SIEMBRA_CONTRASENA_DEMO."
            )
        else:
            fallo(f"El inicio de sesión rechazó a '{usuario}' (HTTP {st}): revisa el usuario y la contraseña.")
        return False, None
    if st == 200 and isinstance(cuerpo, dict) and cuerpo.get("token"):
        if cuerpo.get("cuentaId") is None:
            fallo("El inicio de sesión respondió 200 con token, pero sin cuentaId.")
            return False, None
        return True, {"token": cuerpo["token"], "cuentaId": cuerpo["cuentaId"]}
    fallo(f"Respuesta inesperada de POST /api/auth/login (HTTP {st}); no se puede continuar.")
    return False, None


def leer_lease(conexion, envio_id):
    """Devuelve (worker, veces_tomado) del lease del envío, o None si no hay."""
    with conexion.cursor() as cursor:
        cursor.execute(
            "SELECT worker, veces_tomado FROM leases_evaluacion WHERE envio_id = %s",
            (envio_id,),
        )
        fila = cursor.fetchone()
    return (str(fila[0]), int(fila[1])) if fila else None


def veredicto_de(detalle):
    return detalle.get("veredicto") or (detalle.get("resultado") or {}).get("veredicto")


# -----------------------------------------------------------------------------
# CASO 1: Matar el worker que tiene el lease del envío
# -----------------------------------------------------------------------------
def caso_1_worker(base, espera_s, servicio, usuario, contrasena):
    encabezado("CASO 1: Matar el worker que tiene el lease del envío")

    # 1.1 — Validar API, contenedores y acceso a la base de datos
    paso("1.1", f"Comprobando API, contenedores '{servicio}' y acceso a la base de datos")
    st, concurso, _ = llamar_api(base, "GET", "/api/concursos/activo")
    if st != 200 or not isinstance(concurso, dict) or not concurso.get("problemas"):
        no_disponible(f"La API en {base} no respondió con concurso activo (HTTP {st}).")
        return False

    ids = contenedores_de(servicio)
    if len(ids) < 2:
        no_disponible(
            f"Se encontraron {len(ids)} contenedor(es) '{servicio}'. "
            "Se requieren al menos 2 réplicas para matar una y observar que otra retoma el envío."
        )
        return False
    workers = datos_contenedores(ids)
    if len(workers) < len(ids):
        fallo(f"docker inspect solo describió {len(workers)} de {len(ids)} contenedores '{servicio}'.")
        return False

    try:
        import psycopg2
    except ImportError:
        fallo(
            "psycopg2 no está instalado. Se necesita para leer el lease del envío. "
            "Instálalo con: pip install psycopg2-binary"
        )
        return False

    try:
        conexion = psycopg2.connect(dsn=os.getenv("DATABASE_URL", DB_URL_POR_OMISION), connect_timeout=5)
        conexion.autocommit = True
    except Exception as e:
        fallo(
            f"No se pudo conectar a la base de datos para leer el lease: {str(e).strip()} "
            "Revisa DATABASE_URL."
        )
        return False

    try:
        return _matar_worker_y_comprobar_reentrega(
            base, espera_s, concurso["problemas"][0], workers, conexion, usuario, contrasena
        )
    except Exception as e:
        fallo(f"Error inesperado durante el caso 1: {type(e).__name__}: {e}")
        return False
    finally:
        conexion.close()


def _matar_worker_y_comprobar_reentrega(base, espera_s, problema, workers, conexion, usuario, contrasena):
    problema_id = problema["id"]
    limite_ms = problema.get("limiteTiempoMs")
    con_limite = isinstance(limite_ms, (int, float)) and limite_ms > 0
    dormir_ms = int(limite_ms * 0.7) if con_limite else DORMIR_POR_OMISION_MS
    ok(f"Concurso listo (problema {problema_id}) y {len(workers)} workers detectados.")

    # 1.2 — Inicio de sesión, si el servicio ya existe
    paso("1.2", f"Iniciando sesión como '{usuario}' (si el servicio de inicio de sesión existe)")
    seguir, sesion = iniciar_sesion(base, usuario, contrasena)
    if not seguir:
        return False
    if sesion:
        cabeceras = {"Authorization": f"Bearer {sesion['token']}"}
        alumno_id = sesion["cuentaId"]
        ok(f"Sesión iniciada como '{usuario}' (cuenta {alumno_id}); el token va en Authorization: Bearer.")
    else:
        cabeceras = None
        st_c, cuentas, _ = llamar_api(base, "GET", "/api/cuentas-demo")
        if st_c != 200 or not isinstance(cuentas, list) or not cuentas:
            no_disponible(f"No se pudieron obtener cuentas de demo (HTTP {st_c}).")
            return False
        alumno_id = cuentas[0]["id"]
        ok(f"Se usa la primera cuenta de demo (cuenta {alumno_id}), sin token.")

    # 1.3 — Crear un envío correcto pero lento
    origen_limite = f"70 % del límite de {limite_ms} ms" if con_limite else "el concurso no informó el límite"
    paso("1.3", f"Subiendo solución correcta que duerme {dormir_ms} ms por caso ({origen_limite})")
    codigo_lento = (
        "import time\n"
        f"time.sleep({dormir_ms / 1000})\n"
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
        headers_extra=cabeceras,
    )
    if st_e not in (200, 201) or not isinstance(env, dict) or "envioId" not in env:
        fallo(f"No se pudo crear el envío (HTTP {st_e}): {env}")
        return False

    envio_id = env["envioId"]
    ok(f"Envío {envio_id} creado (estado inicial: {env.get('estado')}).")

    # 1.4 — Esperar a que un worker tome el envío
    paso("1.4", "Esperando a que un worker tome el envío (estado = 'evaluando')")
    fin_espera = time.time() + 15
    en_evaluacion = False
    ultimo_estado = None
    while time.time() < fin_espera:
        st_g, detalle, _ = llamar_api(base, "GET", f"/api/envios/{envio_id}", headers_extra=cabeceras)
        ultimo_estado = f"HTTP {st_g}"
        if st_g == 200 and isinstance(detalle, dict):
            ultimo_estado = detalle.get("estado")
            if detalle.get("estado") == "evaluando":
                en_evaluacion = True
                break
            v = veredicto_de(detalle)
            if v and v != "PENDIENTE":
                fallo(
                    f"El envío {envio_id} terminó con veredicto {v} antes de poder matar a su worker: "
                    "no se demostró la reentrega."
                )
                return False
        time.sleep(0.2)

    if not en_evaluacion:
        fallo(f"El envío {envio_id} no pasó a estado 'evaluando' dentro de 15 s (último estado: {ultimo_estado}).")
        return False
    ok(f"Envío {envio_id} en estado 'evaluando'.")

    # 1.5 — Leer el lease e identificar el contenedor de ese worker
    paso("1.5", "Leyendo el lease del envío en la base de datos e identificando el contenedor del worker")
    antes = leer_lease(conexion, envio_id)
    if not antes:
        fallo(f"No se encontró lease para el envío {envio_id} en leases_evaluacion.")
        return False
    worker_antes, veces_antes = antes
    info(f"Lease antes de la falla: worker={worker_antes}, veces_tomado={veces_antes}")

    objetivo = contenedor_del_worker(worker_antes, workers)
    if not objetivo:
        fallo(
            f"El worker '{worker_antes}' no corresponde a ningún contenedor. "
            f"Hostnames encontrados: {[w['hostname'] for w in workers]}. "
            "¿Cambió el formato del nombre del worker?"
        )
        return False
    id_corto = objetivo["id"][:12]
    ok(f"El worker corre en el contenedor {objetivo['nombre']} ({id_corto}).")

    # 1.6 — Matar ese contenedor con SIGKILL
    paso("1.6", f"Matando el contenedor {objetivo['nombre']} ({id_corto}) con SIGKILL")
    matado, err = docker_cmd("kill", objetivo["id"])
    if not matado:
        fallo(f"No se pudo matar el contenedor {id_corto}: {err}")
        return False
    ok(f"Contenedor {id_corto} terminado abruptamente.")

    # 1.7 — Esperar el veredicto y comprobar la reentrega en el lease
    try:
        paso(
            "1.7",
            f"Esperando veredicto ACCEPTED (máx. {espera_s} s): otro worker retoma el envío cuando vence el lease",
        )
        fin = time.time() + espera_s
        while time.time() < fin:
            st_g, detalle, _ = llamar_api(base, "GET", f"/api/envios/{envio_id}", headers_extra=cabeceras)
            if st_g == 200 and isinstance(detalle, dict):
                v = veredicto_de(detalle)
                if v == "ACCEPTED":
                    return _comprobar_reentrega(conexion, envio_id, worker_antes, veces_antes)
                if v == "ERROR_SERVIDOR":
                    fallo(
                        f"El envío {envio_id} terminó en ERROR_SERVIDOR: es su resultado definitivo, "
                        "nadie lo volverá a evaluar."
                    )
                    return False
                if v and v != "PENDIENTE":
                    fallo(f"El envío {envio_id} terminó con veredicto {v} (se esperaba ACCEPTED).")
                    return False
            time.sleep(1)

        fallo(f"Timeout: el envío {envio_id} no obtuvo veredicto ACCEPTED tras {espera_s} s.")
        return False
    finally:
        info(f"Restaurando contenedor {id_corto}…")
        docker_cmd("start", objetivo["id"])


def _comprobar_reentrega(conexion, envio_id, worker_antes, veces_antes):
    """El veredicto ACCEPTED solo cuenta si el lease muestra que otro worker retomó el envío."""
    despues = leer_lease(conexion, envio_id)
    worker_despues, veces_despues = despues if despues else ("(sin lease)", None)
    info(f"Worker antes:   {worker_antes} (veces_tomado={veces_antes})")
    info(f"Worker después: {worker_despues} (veces_tomado={veces_despues})")
    reentregado = bool(despues) and (veces_despues > veces_antes or worker_despues != worker_antes)
    if reentregado:
        ok(f"El envío {envio_id} terminó en ACCEPTED después de que otro worker lo retomó.")
        return True
    fallo(
        f"El envío {envio_id} terminó en ACCEPTED, pero no se demostró la reentrega: "
        "el lease no cambió, así que el worker terminó antes de morir."
    )
    return False


# -----------------------------------------------------------------------------
# CASO 2: Apagar una réplica del Gateway
# -----------------------------------------------------------------------------
def caso_2_gateway(base, servicio):
    encabezado("CASO 2: Apagar una réplica del Gateway")

    paso("2.1", f"Verificando réplicas del servicio '{servicio}' en Docker")
    replicas = contenedores_de(servicio)
    if len(replicas) < 2:
        no_disponible(
            f"Se encontraron {len(replicas)} contenedor(es) '{servicio}'. "
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
def caso_3_eureka(eureka_url, espera_s, app_eureka):
    encabezado("CASO 3: Ver salir de Eureka a una instancia caída")

    paso("3.1", f"Consultando directorio Eureka en {eureka_url}/eureka/apps")
    st, datos, _ = llamar_api(eureka_url, "GET", "/eureka/apps", timeout=3)
    if st != 200 or not isinstance(datos, dict):
        no_disponible(f"Eureka no está disponible en {eureka_url} (HTTP {st}).")
        return False

    instancias = instancias_de_eureka(datos)
    if not instancias:
        no_disponible("Eureka respondió 200, pero no hay ninguna instancia registrada.")
        return False
    for inst in instancias:
        info(f"Registrada en Eureka: {inst['app']} | {inst['instanceId']} | status={inst['status']}")

    candidatas = [i for i in instancias if i["status"] == "UP"]
    if app_eureka:
        candidatas = [i for i in candidatas if i["app"].upper() == app_eureka.upper()]
        if not candidatas:
            no_disponible(f"La aplicación '{app_eureka}' no tiene instancias UP en Eureka.")
            return False

    # La instancia que se vigila y el contenedor que se mata deben ser la misma réplica.
    contenedores = datos_contenedores(contenedores_de())
    objetivo_instancia = None
    objetivo_contenedor = None
    for inst in candidatas:
        contenedor = contenedor_de_instancia(inst, contenedores)
        if contenedor:
            objetivo_instancia, objetivo_contenedor = inst, contenedor
            break

    if not objetivo_contenedor:
        no_disponible(
            "Ninguna instancia UP de Eureka se pudo emparejar con un contenedor por IP o hostname; "
            f"no se mata nada. Contenedores vistos: {[(c['nombre'], c['ips']) for c in contenedores]}."
        )
        return False

    app = objetivo_instancia["app"]
    iid = objetivo_instancia["instanceId"]
    id_corto = objetivo_contenedor["id"][:12]
    ok(
        f"Instancia '{iid}' de {app} (ipAddr={objetivo_instancia['ipAddr']}) emparejada con el contenedor "
        f"{objetivo_contenedor['nombre']} ({id_corto})."
    )

    # 3.2 — docker kill (no stop) para que no se dé de baja sola
    paso("3.2", f"Matando instancia '{iid}' (contenedor {id_corto}) con docker kill")
    matado, err = docker_cmd("kill", objetivo_contenedor["id"])
    if not matado:
        fallo(f"No se pudo matar el contenedor {id_corto}: {err}")
        return False
    momento_kill = time.time()
    ok(f"Contenedor {id_corto} terminado abruptamente (SIGKILL).")

    # 3.3 — Esperar a que Eureka saque la instancia; solo contar cuando responde 200
    try:
        paso("3.3", f"Esperando a que Eureka saque la instancia caída (máx. {espera_s} s)")
        fin = momento_kill + espera_s
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
            presente_up = any(
                i["app"] == app and i["instanceId"] == iid and i["status"] == "UP"
                for i in instancias_de_eureka(datos_e)
            )
            if not presente_up:
                salio = True
                break
            info(f"Instancia aún figura como UP en Eureka (consulta válida #{consultas_200}); esperando expiración de heartbeat…")
            time.sleep(2)

        tardo_s = int(time.time() - momento_kill)
        if salio:
            ok(
                f"Instancia '{iid}' salió del estado UP en Eureka {tardo_s} s después del kill "
                f"({consultas_200} consultas válidas, {consultas_no_200} respuestas no-200 ignoradas)."
            )
            return True

        fallo(
            f"La instancia '{iid}' siguió figurando UP tras {espera_s} s. Conviene revisar en Eureka "
            "la auto-preservación y los tiempos de expiración del lease, o subir --espera-eureka."
        )
        return False
    finally:
        info(f"Restaurando contenedor {id_corto}...")
        docker_cmd("start", objetivo_contenedor["id"])


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
    parser.add_argument(
        "--espera", type=int, default=60, help="Caso 1: segundos máximos de espera del veredicto (por omisión 60)"
    )
    parser.add_argument(
        "--usuario", default="alumno1", help="Caso 1: usuario para iniciar sesión (por omisión alumno1)"
    )
    parser.add_argument(
        "--contrasena",
        default=os.environ.get("SIEMBRA_CONTRASENA_DEMO"),
        help="Caso 1: contraseña del usuario (por omisión, la variable de entorno SIEMBRA_CONTRASENA_DEMO)",
    )
    parser.add_argument(
        "--servicio-evaluacion",
        default="evaluacion",
        help="Caso 1: nombre del servicio de Compose de los workers (por omisión evaluacion)",
    )
    parser.add_argument(
        "--servicio-gateway",
        default="gateway",
        help="Caso 2: nombre del servicio de Compose del gateway (por omisión gateway)",
    )
    parser.add_argument(
        "--app-eureka",
        help="Caso 3: aplicación de Eureka a tumbar, por ejemplo CONCURSOS "
        "(por omisión, la primera instancia UP que tenga contenedor)",
    )
    parser.add_argument(
        "--espera-eureka",
        type=int,
        default=120,
        help="Caso 3: segundos máximos para que la instancia salga de Eureka (por omisión 120)",
    )
    args = parser.parse_args()

    base = args.base.rstrip("/")
    eureka = args.eureka.rstrip("/")

    encabezado("DISTRINUBS · DEMO DE FALLAS REALES (PASO 4)")
    print(f"Gateway: {base}")
    print(f"Eureka:  {eureka}")

    resultados = {}
    if args.caso in (None, 1) or args.todos:
        resultados["Caso 1 (Worker)"] = caso_1_worker(
            base, args.espera, args.servicio_evaluacion, args.usuario, args.contrasena
        )
    if args.caso in (None, 2) or args.todos:
        resultados["Caso 2 (Gateway)"] = caso_2_gateway(base, args.servicio_gateway)
    if args.caso in (None, 3) or args.todos:
        resultados["Caso 3 (Eureka)"] = caso_3_eureka(eureka, args.espera_eureka, args.app_eureka)

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
