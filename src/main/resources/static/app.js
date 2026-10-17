// Front pequeño de la etapa 1 (PRD, secciones 0 y 8): HTML y JavaScript sin framework.
// Solo llama a la API por HTTP. No hay inicio de sesión: la identidad es la cuenta de demo elegida.

const REINTENTOS_MAX = 3;          // intentos totales de un mismo clic en "Enviar"
const ESPERA_REINTENTO_MS = 1000;  // se duplica en cada reintento
const TIMEOUT_PETICION_MS = 8000;
const SONDEO_ESTADO_MS = 1000;
const SONDEO_ESTADO_MAX = 120;     // ~2 minutos
const REFRESCO_TABLA_MS = 5000;

const $ = (id) => document.getElementById(id);
let concurso = null;
let sondeoActual = 0; // permite cancelar el sondeo de un envío anterior

// --- Utilidades ---------------------------------------------------------------

const esperar = (ms) => new Promise((r) => setTimeout(r, ms));

function nuevoIntentoId() {
  if (crypto.randomUUID) return crypto.randomUUID();
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

async function pedir(url, opciones = {}) {
  const control = new AbortController();
  const temporizador = setTimeout(() => control.abort(), TIMEOUT_PETICION_MS);
  try {
    return await fetch(url, { ...opciones, signal: control.signal });
  } finally {
    clearTimeout(temporizador);
  }
}

async function obtenerJson(url) {
  const r = await pedir(url);
  if (!r.ok) throw new Error(`${url} respondió ${r.status}`);
  return r.json();
}

function normalizar(texto) {
  return String(texto ?? '').trim().toLowerCase().replace(/[_-]+/g, ' ');
}

function ponerMensaje(elemento, texto, clase = '') {
  elemento.textContent = texto;
  elemento.className = clase;
}

// --- Alumno de demo y concurso -------------------------------------------------

async function cargarCuentas() {
  const select = $('alumno');
  try {
    const cuentas = await obtenerJson('/api/cuentas-demo');
    select.replaceChildren();
    if (cuentas.length === 0) {
      select.add(new Option('(sin cuentas: corre la siembra)', ''));
      return;
    }
    for (const c of cuentas) select.add(new Option(`${c.nombre} (${c.usuario})`, c.id));
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
    $('concurso-nombre').textContent = 'No hay concurso abierto (o la API no respondió).';
  }
}

function mostrarEnunciado() {
  const id = $('problema').value;
  const p = (concurso?.problemas ?? []).find((x) => String(x.id) === id);
  if (!p) { $('enunciado').textContent = ''; return; }
  const tiempo = p.limiteTiempoMs ?? p.limite_tiempo_ms;
  const limite = tiempo ? `\n\nLímite de tiempo: ${tiempo} ms` : '';
  $('enunciado').textContent = `${p.enunciado ?? ''}${limite}`;
}

// --- Subir envío ---------------------------------------------------------------

// Cada clic genera un intentoId. Los reintentos automáticos reutilizan ese mismo
// identificador (PRD, sección 5, funcionalidad 2). Solo se reintenta ante fallas
// de red, timeout o 5xx; un 4xx es un error del envío y no se repite.
async function subirConReintentos(cuerpo) {
  let ultimoError = null;
  for (let intento = 1; intento <= REINTENTOS_MAX; intento++) {
    try {
      const r = await pedir('/api/envios', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(cuerpo),
      });
      if (r.status === 200 || r.status === 201) return r.json();
      if (r.status >= 400 && r.status < 500) {
        throw Object.assign(new Error(`El servidor rechazó el envío (${r.status}).`), { definitivo: true });
      }
      ultimoError = new Error(`El servidor respondió ${r.status}.`);
    } catch (e) {
      if (e.definitivo) throw e;
      ultimoError = e;
    }
    if (intento < REINTENTOS_MAX) {
      ponerMensaje($('mensaje-envio'), `Enviando… (reintento ${intento} de ${REINTENTOS_MAX - 1})`);
      await esperar(ESPERA_REINTENTO_MS * 2 ** (intento - 1));
    }
  }
  throw ultimoError ?? new Error('No se pudo enviar.');
}

async function enviar() {
  const alumnoId = Number($('alumno').value);
  const problemaId = Number($('problema').value);
  const codigo = $('codigo').value;
  const mensaje = $('mensaje-envio');

  if (!alumnoId || !problemaId) return ponerMensaje(mensaje, 'Elige alumno y problema.', 'error');
  if (!codigo.trim()) return ponerMensaje(mensaje, 'Escribe tu código.', 'error');

  const boton = $('enviar');
  boton.disabled = true;
  ponerMensaje(mensaje, 'Enviando…');
  const cuerpo = {
    alumnoId,
    problemaId,
    intentoId: nuevoIntentoId(),
    lenguaje: $('lenguaje').value,
    codigo,
  };

  try {
    const envio = await subirConReintentos(cuerpo);
    // "Recibido" solo se muestra cuando la API respondió.
    ponerMensaje(mensaje, `Recibido (envío ${envio.envioId}).`, 'ok');
    sondearEstado(envio.envioId);
  } catch (e) {
    // El servidor pudo haber guardado el envío aunque no llegó la respuesta. El
    // siguiente clic genera un intentoId nuevo (riesgo declarado en el README).
    ponerMensaje(mensaje, `No se pudo confirmar el envío: ${e.message} Vuelve a enviar.`, 'error');
  } finally {
    boton.disabled = false;
  }
}

// --- Estado y veredicto --------------------------------------------------------

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
    if (miSondeo !== sondeoActual) return; // hubo un envío más nuevo
    try {
      const d = await obtenerJson(`/api/envios/${envioId}`);
      const veredicto = extraerVeredicto(d);
      if (veredicto) {
        const tiempo = d.tiempoMs ?? d.resultado?.tiempoMs;
        const extra = tiempo != null ? ` (${tiempo} ms)` : '';
        ponerMensaje(salida, `Envío ${envioId}: ${String(veredicto).replace(/_/g, ' ')}${extra}`, 'ok');
        cargarTabla();
        return;
      }
      ponerMensaje(salida, `Envío ${envioId}: ${d.estado ?? 'recibido'}`);
    } catch (e) {
      ponerMensaje(salida, `Envío ${envioId}: no se pudo consultar el estado, reintentando…`, 'error');
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
      contenedor.textContent = 'Todavía no hay posiciones.';
      return;
    }
    // Las columnas salen de las claves de la primera fila: el contrato lo define Joshua.
    const columnas = Object.keys(filas[0]);
    const tabla = document.createElement('table');
    const cabecera = tabla.createTHead().insertRow();
    for (const c of columnas) cabecera.appendChild(Object.assign(document.createElement('th'), { textContent: c }));
    const cuerpo = tabla.createTBody();
    for (const f of filas) {
      const fila = cuerpo.insertRow();
      for (const c of columnas) fila.insertCell().textContent = valorCelda(f[c]);
    }
    contenedor.replaceChildren(tabla);
  } catch (e) {
    contenedor.textContent = 'No se pudo cargar la tabla.';
  }
}

// --- Arranque ------------------------------------------------------------------

$('enviar').addEventListener('click', enviar);
$('problema').addEventListener('change', mostrarEnunciado);
$('actualizar-tabla').addEventListener('click', cargarTabla);

cargarCuentas();
cargarConcurso();
cargarTabla();
setInterval(cargarTabla, REFRESCO_TABLA_MS);
