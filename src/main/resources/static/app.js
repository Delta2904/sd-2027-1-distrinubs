// Distrinubs · Etapa 02-separacion · Módulo Front & Observabilidad
// Responsable: Diego Contreras (@diegocontrras)
//
// Funcionalidades:
// 1. Llamadas a través del Gateway con rutas públicas y reporte claro de servicios caídos (502/503/504).
// 2. Inicio de sesión (Paso 3) con token JWT firmado (sub, rol, exp) enviado en Authorization: Bearer.
// 3. ID de correlación (X-Correlation-Id) en cada petición para trazabilidad en logs.
// 4. Selector de lenguaje para C++, C, Java, JavaScript y Python 3 con plantillas de inicio (Paso 8).
// 5. Editor de código al costado del enunciado (estilo LeetCode).
// 6. Vista del estado de los servicios y observabilidad mínima (Paso 7).

const REINTENTOS_MAX = 3;
const ESPERA_REINTENTO_MS = 1000;
const TIMEOUT_PETICION_MS = 8000;
const SONDEO_ESTADO_MS = 1000;
const SONDEO_ESTADO_MAX = 120;
const REFRESCO_TABLA_MS = 6000;
const REFRESCO_OBS_MS = 10000;

const GATEWAY_URL = window.GATEWAY_URL || '';

const PLANTILLAS_CODIGO = {
  PYTHON3: `# Solución en Python 3\na, b = map(int, input().split())\nprint(a + b)\n`,
  CPP: `// Solución en C++ (g++)\n#include <iostream>\nusing namespace std;\n\nint main() {\n    ios_base::sync_with_stdio(false);\n    cin.tie(NULL);\n    long long a, b;\n    if (cin >> a >> b) {\n        cout << (a + b) << "\\n";\n    }\n    return 0;\n}\n`,
  C: `// Solución en C (gcc)\n#include <stdio.h>\n\nint main() {\n    long long a, b;\n    if (scanf("%lld %lld", &a, &b) == 2) {\n        printf("%lld\\n", a + b);\n    }\n    return 0;\n}\n`,
  JAVA: `// Solución en Java (convención de evaluación: clase Main)\nimport java.util.Scanner;\n\npublic class Main {\n    public static void main(String[] args) {\n        Scanner sc = new Scanner(System.in);\n        if (sc.hasNextLong()) {\n            long a = sc.nextLong();\n            long b = sc.nextLong();\n            System.out.println(a + b);\n        }\n    }\n}\n`,
  JAVASCRIPT: `// Solución en JavaScript (Node.js)\nconst fs = require('fs');\nconst entrada = fs.readFileSync(0, 'utf-8').trim().split(/\\s+/);\nif (entrada.length >= 2) {\n    const a = BigInt(entrada[0]);\n    const b = BigInt(entrada[1]);\n    console.log((a + b).toString());\n}\n`,
};

const $ = (id) => document.getElementById(id);
let concurso = null;
let sondeoActual = 0;
let sesionActual = null;
let ultimoIdCorrelacion = '-';

// --- Utilidades ---------------------------------------------------------------

const esperar = (ms) => new Promise((r) => setTimeout(r, ms));

function nuevoUuid() {
  if (crypto.randomUUID) return crypto.randomUUID();
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

function normalizar(texto) {
  return String(texto ?? '').trim().toLowerCase().replace(/[_-]+/g, ' ');
}

function ponerMensaje(elemento, texto, clase = '') {
  if (!elemento) return;
  elemento.textContent = texto;
  elemento.className = clase;
}

// --- Manejo de errores y alertas del Gateway ----------------------------------

function ocultarAlertaFalla() {
  const alerta = $('alerta-servicio');
  if (alerta) alerta.classList.add('oculto');
}

function mostrarAlertaFalla(titulo, mensaje, codigo = null, idCorr = null) {
  const alerta = $('alerta-servicio');
  if (!alerta) return;
  $('alerta-titulo').textContent = titulo;
  $('alerta-mensaje').textContent = mensaje;
  const meta = [];
  if (codigo) meta.push(`Código HTTP: ${codigo}`);
  if (idCorr && idCorr !== '-') meta.push(`Id correlación: ${idCorr}`);
  $('alerta-detalle').textContent = meta.join(' · ');
  alerta.classList.remove('oculto');
}

// --- Peticiones HTTP centralizadas por el Gateway -----------------------------

async function pedir(url, opciones = {}) {
  const rutaCompleta = `${GATEWAY_URL}${url}`;
  const control = new AbortController();
  const temporizador = setTimeout(() => control.abort(), TIMEOUT_PETICION_MS);

  const idCorr = opciones.idCorrelacion || nuevoUuid();
  ultimoIdCorrelacion = idCorr;
  if ($('id-correlacion-actual')) $('id-correlacion-actual').textContent = idCorr;

  const cabeceras = {
    Accept: 'application/json',
    'X-Correlation-Id': idCorr,
    ...(opciones.headers || {}),
  };

  // Adjunta el token JWT en Authorization: Bearer en cada petición
  if (sesionActual?.token && !cabeceras['Authorization']) {
    cabeceras['Authorization'] = `Bearer ${sesionActual.token}`;
  }

  try {
    const respuesta = await fetch(rutaCompleta, {
      ...opciones,
      headers: cabeceras,
      signal: control.signal,
    });

    const corrRespuesta = respuesta.headers.get('X-Correlation-Id');
    if (corrRespuesta) {
      ultimoIdCorrelacion = corrRespuesta;
      if ($('id-correlacion-actual')) $('id-correlacion-actual').textContent = corrRespuesta;
    }

    // Detección de fallas en servicios / réplicas caídas o gateway
    if (respuesta.status === 502) {
      mostrarAlertaFalla(
        '502 Bad Gateway',
        'El API Gateway no pudo conectarse con el servicio de destino. Es posible que el contenedor se haya caído o esté reiniciando.',
        502,
        ultimoIdCorrelacion
      );
    } else if (respuesta.status === 503) {
      mostrarAlertaFalla(
        '503 Service Unavailable',
        'El servicio solicitado no está disponible temporalmente (sobrecarga o réplica apagada).',
        503,
        ultimoIdCorrelacion
      );
    } else if (respuesta.status === 504) {
      mostrarAlertaFalla(
        '504 Gateway Timeout',
        'El servicio tardó demasiado en responder a la petición a través del Gateway.',
        504,
        ultimoIdCorrelacion
      );
    } else if (respuesta.status === 401) {
      // Sesión expirada o no autorizada
      if (sesionActual && !url.includes('/api/auth/login')) {
        mostrarAlertaFalla('Sesión expirada (401)', 'Tu token ha vencido o no es válido. Por favor inicia sesión nuevamente.', 401, ultimoIdCorrelacion);
        cerrarSesion();
      }
    }

    return respuesta;
  } catch (error) {
    if (error.name === 'AbortError') {
      mostrarAlertaFalla(
        'Timeout de red',
        `La petición a ${url} excedió el tiempo límite (${TIMEOUT_PETICION_MS / 1000} s). ¿Está corriendo el gateway o servicio?`,
        'TIMEOUT',
        ultimoIdCorrelacion
      );
    } else {
      mostrarAlertaFalla(
        'Falla de conexión',
        `No se pudo comunicar con el Gateway en ${rutaCompleta}: ${error.message}.`,
        'RED',
        ultimoIdCorrelacion
      );
    }
    throw error;
  } finally {
    clearTimeout(temporizador);
  }
}

async function obtenerJson(url) {
  const r = await pedir(url);
  if (!r.ok) {
    const errorTexto = await r.text().catch(() => '');
    throw new Error(`${url} respondió ${r.status}: ${errorTexto.slice(0, 100)}`);
  }
  return r.json();
}

// --- Inicio de sesión y Token JWT (Paso 3) ------------------------------------

function cargarSesionGuardada() {
  try {
    const guardada = localStorage.getItem('distrinubs_sesion');
    if (guardada) {
      const datos = JSON.parse(guardada);
      if (datos.exp && datos.exp > Date.now() / 1000) {
        sesionActual = datos;
        aplicarSesionEnUi();
      } else {
        localStorage.removeItem('distrinubs_sesion');
      }
    }
  } catch (e) {
    localStorage.removeItem('distrinubs_sesion');
  }
}

function aplicarSesionEnUi() {
  if (sesionActual) {
    $('panel-login').classList.add('oculto');
    $('tarjeta-sesion').classList.remove('oculto');
    $('sesion-nombre').textContent = sesionActual.nombre || sesionActual.usuario;
    $('sesion-usuario').textContent = sesionActual.usuario;
    $('sesion-rol').textContent = sesionActual.rol || 'ALUMNO';
    const recToken = sesionActual.token.slice(0, 18) + '…' + sesionActual.token.slice(-8);
    $('sesion-token-vista').textContent = recToken;
    $('sesion-token-vista').title = sesionActual.token;
  } else {
    $('panel-login').classList.remove('oculto');
    $('tarjeta-sesion').classList.add('oculto');
  }
}

async function iniciarSesion() {
  const usuario = $('login-usuario').value.trim();
  const contrasena = $('login-contrasena').value;
  const msg = $('mensaje-login');

  if (!usuario) {
    return ponerMensaje(msg, 'Escribe tu usuario.', 'error');
  }
  if (!contrasena) {
    return ponerMensaje(msg, 'Escribe tu contraseña.', 'error');
  }

  ponerMensaje(msg, 'Iniciando sesión…');
  try {
    const r = await pedir('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ usuario, contrasena }),
    });

    if (!r.ok) {
      if (r.status === 401) {
        ponerMensaje(msg, 'Credenciales incorrectas.', 'error');
      } else if (r.status === 404 || r.status === 502 || r.status === 503) {
        ponerMensaje(msg, `El servicio de identidad aún no está disponible (HTTP ${r.status}).`, 'aviso');
      } else {
        ponerMensaje(msg, `Error en el servicio de identidad (${r.status}).`, 'error');
      }
      return;
    }

    const datos = await r.json();
    sesionActual = datos;
    localStorage.setItem('distrinubs_sesion', JSON.stringify(datos));
    ponerMensaje(msg, '¡Sesión iniciada correctamente!', 'ok');
    aplicarSesionEnUi();
    ocultarAlertaFalla();

    // Si la cuenta coincide con el selector de alumnos, sincronizar
    const select = $('alumno');
    for (let i = 0; i < select.options.length; i++) {
      if (Number(select.options[i].value) === Number(datos.cuentaId)) {
        select.selectedIndex = i;
        break;
      }
    }
  } catch (e) {
    ponerMensaje(msg, `No se pudo iniciar sesión: ${e.message}`, 'error');
  }
}

function cerrarSesion() {
  sesionActual = null;
  localStorage.removeItem('distrinubs_sesion');
  aplicarSesionEnUi();
  ponerMensaje($('mensaje-login'), 'Sesión cerrada.', 'aviso');
}

function autocompletarDemo() {
  const select = $('alumno');
  const opcion = select.options[select.selectedIndex];
  if (!opcion || !opcion.value) return;

  const texto = opcion.textContent;
  const match = texto.match(/\(([^)]+)\)/);
  const usuario = match ? match[1] : opcion.value;

  $('login-usuario').value = usuario;
  $('login-contrasena').value = 'distrinubs';
  ponerMensaje($('mensaje-login'), `Credenciales listas para ${usuario}. Clic en 'Iniciar sesión'.`, 'aviso');
}

// --- Cuentas y Concurso --------------------------------------------------------

async function cargarCuentas() {
  const select = $('alumno');
  try {
    const cuentas = await obtenerJson('/api/cuentas-demo');
    select.replaceChildren();
    if (cuentas.length === 0) {
      select.add(new Option('(sin cuentas: revisa que la siembra esté activa)', ''));
      return;
    }
    for (const c of cuentas) {
      select.add(new Option(`${c.nombre} (${c.usuario})`, c.id));
    }
  } catch (e) {
    select.replaceChildren(new Option('(no se pudieron cargar las cuentas)', ''));
  }
}

async function cargarConcurso() {
  try {
    concurso = await obtenerJson('/api/concursos/activo');
    $('concurso-nombre').textContent = concurso.nombre ?? 'Concurso activo';
    const select = $('problema');
    select.replaceChildren();
    for (const p of concurso.problemas ?? []) {
      select.add(new Option(`${p.letra}. ${p.titulo}`, p.id));
    }
    mostrarEnunciado();
  } catch (e) {
    $('concurso-nombre').textContent = 'No hay concurso abierto (o el servicio no respondió).';
  }
}

function mostrarEnunciado() {
  const id = $('problema').value;
  const p = (concurso?.problemas ?? []).find((x) => String(x.id) === id);
  if (!p) {
    $('enunciado').textContent = 'No se encontró el problema seleccionado.';
    return;
  }
  const tiempo = p.limiteTiempoMs ?? p.limite_tiempo_ms;
  const limite = tiempo ? `\n\nLímite de tiempo: ${tiempo} ms` : '';
  const memoria = p.limiteMemoriaMb ? `\nLímite de memoria: ${p.limiteMemoriaMb} MB` : '';
  $('enunciado').textContent = `${p.enunciado ?? ''}${limite}${memoria}`;
}

// --- Lenguajes y Editor de código (Paso 8 y Opcional) ---------------------------

function aplicarPlantillaLenguaje() {
  const leng = $('lenguaje').value;
  const codigo = $('codigo');
  const plantilla = PLANTILLAS_CODIGO[leng] || '';

  if (!codigo.value.trim() || confirm(`¿Deseas reemplazar el código actual con la plantilla de ${leng}?`)) {
    codigo.value = plantilla;
  }
}

// --- Subir envío ---------------------------------------------------------------

async function subirConReintentos(cuerpo) {
  let ultimoError = null;
  for (let intento = 1; intento <= REINTENTOS_MAX; intento++) {
    try {
      const r = await pedir('/api/envios', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(cuerpo),
        idCorrelacion: cuerpo.intentoId,
      });

      if (r.status === 200 || r.status === 201) return r.json();
      if (r.status >= 400 && r.status < 500) {
        const errorJson = await r.json().catch(() => ({}));
        throw Object.assign(
          new Error(errorJson.message || `El servidor rechazó el envío (${r.status}).`),
          { definitivo: true }
        );
      }
      ultimoError = new Error(`El servidor respondió HTTP ${r.status}.`);
    } catch (e) {
      if (e.definitivo) throw e;
      ultimoError = e;
    }
    if (intento < REINTENTOS_MAX) {
      ponerMensaje($('mensaje-envio'), `Enviando… (reintento ${intento} de ${REINTENTOS_MAX - 1})`, 'aviso');
      await esperar(ESPERA_REINTENTO_MS * 2 ** (intento - 1));
    }
  }
  throw ultimoError ?? new Error('No se pudo enviar.');
}

async function enviar() {
  // Se obtiene la cuenta de la sesión activa o del selector de demo
  let alumnoId = sesionActual?.cuentaId;
  if (!alumnoId) {
    alumnoId = Number($('alumno').value);
  }

  const problemaId = Number($('problema').value);
  const codigo = $('codigo').value;
  const lenguaje = $('lenguaje').value;
  const mensaje = $('mensaje-envio');

  if (!alumnoId) {
    return ponerMensaje(mensaje, 'Inicia sesión o elige una cuenta de alumno.', 'error');
  }
  if (!problemaId) {
    return ponerMensaje(mensaje, 'Selecciona un problema.', 'error');
  }
  if (!codigo.trim()) {
    return ponerMensaje(mensaje, 'Escribe tu código de solución.', 'error');
  }

  const boton = $('enviar');
  boton.disabled = true;
  ponerMensaje(mensaje, 'Enviando por el Gateway…', 'aviso');

  const cuerpo = {
    alumnoId,
    problemaId,
    intentoId: nuevoUuid(),
    lenguaje,
    codigo,
  };

  try {
    const envio = await subirConReintentos(cuerpo);
    ponerMensaje(mensaje, `Recibido por el Gateway (envío ${envio.envioId}).`, 'ok');
    sondearEstado(envio.envioId);
    cargarObservabilidad();
  } catch (e) {
    ponerMensaje(mensaje, `No se pudo confirmar el envío: ${e.message} Vuelve a enviar.`, 'error');
  } finally {
    boton.disabled = false;
  }
}

// --- Estado y veredicto del envío ---------------------------------------------

const ESTADOS_EN_CURSO = ['recibido', 'en cola', 'evaluando'];

function extraerVeredicto(d) {
  const v = d.veredicto ?? d.resultado?.veredicto ?? (typeof d.resultado === 'string' ? d.resultado : null);
  if (v) return v;
  const e = normalizar(d.estado);
  return e && !ESTADOS_EN_CURSO.includes(e) ? d.estado : null;
}

async function sondearEstado(envioId) {
  const miSondeo = ++sondeoActual;
  const salida = $('estado');
  for (let i = 0; i < SONDEO_ESTADO_MAX; i++) {
    if (miSondeo !== sondeoActual) return;
    try {
      const d = await obtenerJson(`/api/envios/${envioId}`);
      const veredicto = extraerVeredicto(d);
      if (normalizar(veredicto) === 'error servidor') {
        ponerMensaje(
          salida,
          `Envío ${envioId}: el servidor no pudo evaluarlo (ERROR_SERVIDOR). No cuenta como intento: vuelve a enviarlo.`,
          'error'
        );
        cargarTabla();
        return;
      }
      if (veredicto) {
        const tiempo = d.tiempoMs ?? d.resultado?.tiempoMs;
        const extra = tiempo != null ? ` (${tiempo} ms)` : '';
        const clase = normalizar(veredicto) === 'accepted' ? 'ok' : 'error';
        ponerMensaje(salida, `Envío ${envioId}: ${String(veredicto).replace(/_/g, ' ')}${extra}`, clase);
        cargarTabla();
        cargarObservabilidad();
        return;
      }
      ponerMensaje(salida, `Envío ${envioId}: ${d.estado ?? 'recibido'}`);
    } catch (e) {
      ponerMensaje(salida, `Envío ${envioId}: consultando estado… (${e.message})`, 'aviso');
    }
    await esperar(SONDEO_ESTADO_MS);
  }
  if (miSondeo === sondeoActual) {
    ponerMensaje(salida, `Envío ${envioId}: la evaluación tarda más de lo esperado. Actualiza más tarde.`, 'error');
  }
}

// --- Tabla de posiciones -------------------------------------------------------

function filasDe(datos) {
  if (Array.isArray(datos)) return datos;
  for (const clave of ['filas', 'posiciones', 'tabla', 'participantes']) {
    if (Array.isArray(datos?.[clave])) return datos[clave];
  }
  return [];
}

function valorCelda(v) {
  if (v === null || v === undefined) return '';
  return typeof v === 'object' ? JSON.stringify(v) : String(v);
}

async function cargarTabla() {
  const contenedor = $('tabla');
  try {
    const filas = filasDe(await obtenerJson('/api/concursos/activo/tabla'));
    if (filas.length === 0) {
      contenedor.textContent = 'Todavía no hay posiciones registradas.';
      return;
    }
    const columnas = Object.keys(filas[0]);
    const tabla = document.createElement('table');
    const cabecera = tabla.createTHead().insertRow();
    for (const c of columnas) {
      cabecera.appendChild(Object.assign(document.createElement('th'), { textContent: c }));
    }
    const cuerpo = tabla.createTBody();
    for (const f of filas) {
      const fila = cuerpo.insertRow();
      for (const c of columnas) {
        fila.insertCell().textContent = valorCelda(f[c]);
      }
    }
    contenedor.replaceChildren(tabla);
  } catch (e) {
    contenedor.textContent = 'No se pudo cargar la tabla de posiciones.';
  }
}

// --- Observabilidad y Estado de Servicios (Paso 7) -----------------------------

async function cargarObservabilidad() {
  const grid = $('grid-servicios');
  try {
    const datos = await obtenerJson('/api/observabilidad/estado');

    // Resumen del sistema
    if ($('obs-activos')) $('obs-activos').textContent = datos.resumen?.activos ?? '0';
    if ($('obs-caidos')) $('obs-caidos').textContent = datos.resumen?.caidos ?? '0';
    if ($('obs-no-disponibles')) $('obs-no-disponibles').textContent = datos.resumen?.noDisponibles ?? '0';

    const estadoGlobal = (datos.estadoGlobal || 'NO_DISPONIBLE').toLowerCase();
    const badgeGlobal = $('estado-global-badge');
    if (badgeGlobal) {
      badgeGlobal.textContent = datos.estadoGlobal || 'NO_DISPONIBLE';
      badgeGlobal.className = `badge-estado estado-${estadoGlobal}`;
    }

    grid.replaceChildren();
    for (const s of datos.servicios || []) {
      const tarjeta = document.createElement('div');
      tarjeta.className = `tarjeta-servicio servicio-${s.estado.toLowerCase()}`;

      const cabecera = document.createElement('div');
      cabecera.className = 'servicio-cabecera';

      const nombre = document.createElement('strong');
      nombre.textContent = s.nombre.toUpperCase();

      const estado = document.createElement('span');
      estado.className = `badge-estado estado-${s.estado.toLowerCase()}`;
      estado.textContent = s.estado;

      cabecera.appendChild(nombre);
      cabecera.appendChild(estado);

      const detalles = document.createElement('p');
      detalles.className = 'servicio-detalle';
      detalles.textContent = s.detalle;

      const meta = document.createElement('div');
      meta.className = 'servicio-meta';
      meta.textContent = `Instancias: ${s.instancias} · Latencia: ${s.latenciaMs} ms`;

      tarjeta.appendChild(cabecera);
      tarjeta.appendChild(detalles);
      tarjeta.appendChild(meta);
      grid.appendChild(tarjeta);
    }
  } catch (e) {
    grid.innerHTML = `<p class="error">No se pudo obtener el estado de observabilidad: ${e.message}</p>`;
  }
}

// --- Arranque e inicialización -------------------------------------------------

function inicializar() {
  // Manejadores de autenticación
  $('btn-login').addEventListener('click', iniciarSesion);
  $('btn-logout').addEventListener('click', cerrarSesion);
  $('btn-usar-demo').addEventListener('click', autocompletarDemo);
  $('btn-cerrar-alerta').addEventListener('click', ocultarAlertaFalla);

  // Manejadores de envío y editor
  $('enviar').addEventListener('click', enviar);
  $('problema').addEventListener('change', mostrarEnunciado);
  $('lenguaje').addEventListener('change', aplicarPlantillaLenguaje);
  $('btn-plantilla').addEventListener('click', aplicarPlantillaLenguaje);

  // Observabilidad y tabla
  $('actualizar-tabla').addEventListener('click', cargarTabla);
  $('actualizar-observabilidad').addEventListener('click', cargarObservabilidad);

  // Atajo de tabulación en el editor de código
  $('codigo').addEventListener('keydown', function (e) {
    if (e.key === 'Tab') {
      e.preventDefault();
      const inicio = this.selectionStart;
      const fin = this.selectionEnd;
      this.value = this.value.substring(0, inicio) + '    ' + this.value.substring(fin);
      this.selectionStart = this.selectionEnd = inicio + 4;
    }
  });

  // Carga inicial
  cargarSesionGuardada();
  cargarCuentas();
  cargarConcurso().then(() => {
    if (!$('codigo').value.trim()) {
      aplicarPlantillaLenguaje();
    }
  });
  cargarTabla();
  cargarObservabilidad();

  // Refrescos periódicos
  setInterval(cargarTabla, REFRESCO_TABLA_MS);
  setInterval(cargarObservabilidad, REFRESCO_OBS_MS);
}

document.addEventListener('DOMContentLoaded', inicializar);
if (document.readyState === 'interactive' || document.readyState === 'complete') {
  inicializar();
}
