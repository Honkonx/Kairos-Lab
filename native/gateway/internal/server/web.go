package server

import (
	_ "embed"
	"net/http"
)

// handleHome sirve la página de inicio del gateway: estado y un cliente mínimo para probar un token. No lleva secretos ni
// datos del teléfono; todo lo demás se pide a la API con el token que pegue la persona. Es la semilla del panel web en Go.
func (s *Server) handleHome(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'none'")
	_, _ = w.Write([]byte(homePage))
}

//go:embed panel/index.html
var panelPage string

// handlePanel sirve el panel web completo (archivos, terminales, módulos, distros...). Es una sola página estática sin
// datos: todo lo que muestra lo pide a la API con el token que pega la persona (guardado solo en sessionStorage).
func (s *Server) handlePanel(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("X-Frame-Options", "DENY")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
	_, _ = w.Write([]byte(panelPage))
}

//go:embed panel/screen.html
var screenPage string

// handleScreenWindow sirve la ventana aparte del visor de pantalla (2026-10-16, ver —
// "ventana independiente... a futuro sacarla como pestaña propia, como scrcpy"). El panel completo la abre con
// `window.open('/panel/screen#<token>')` — el token viaja en el fragmento de la URL (`#...`), que el navegador
// NUNCA manda al servidor (ni en la petición inicial ni en ninguna posterior) — más estricto todavía que el
// subprotocolo WebSocket que ya usa /v1/screen/ws, no es un paso atrás de seguridad.
func (s *Server) handleScreenWindow(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("X-Frame-Options", "DENY")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
	_, _ = w.Write([]byte(screenPage))
}

const homePage = `<!doctype html>
<html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Gateway — Kairos</title>
<style>
:root{color-scheme:dark}
body{margin:0;font:15px system-ui,sans-serif;background:#0d1117;color:#e6edf3}
main{max-width:640px;margin:0 auto;padding:20px}
h1{font-size:20px;margin:0 0 4px}
.sub{color:#8b949e;font-size:13px;margin-bottom:16px}
.card{background:#161b22;border:1px solid #30363d;border-radius:10px;padding:14px;margin-bottom:14px}
.dot{display:inline-block;width:9px;height:9px;border-radius:50%;background:#8b949e;margin-right:6px}
.ok{background:#3fb950}.bad{background:#f85149}
input{width:100%;box-sizing:border-box;padding:9px;border-radius:8px;border:1px solid #30363d;background:#0d1117;color:#e6edf3;font:inherit}
button{padding:8px 12px;border-radius:8px;border:1px solid #30363d;background:#21262d;color:#e6edf3;font:inherit;margin:8px 6px 0 0;cursor:pointer}
button.run{background:#1f6feb;border-color:#1f6feb}
.row{display:flex;justify-content:space-between;gap:8px;align-items:center;padding:7px 0;border-top:1px solid #21262d}
.risk{font-size:12px;color:#8b949e}
pre{white-space:pre-wrap;word-break:break-word;font-size:12.5px;color:#8b949e;margin:8px 0 0}
</style></head><body><main>
<h1>Gateway de Kairos</h1>
<div class="sub">Acciones del teléfono con permisos. Pega un token creado en la app (Homelab → Gateway → Permisos).</div>
<div class="card"><a href="/panel" style="color:#58a6ff">Abrir el panel completo →</a></div>
<div class="card"><span id="dot" class="dot"></span><span id="state">Comprobando…</span></div>
<div class="card">
<input id="tok" type="password" placeholder="Token" autocomplete="off">
<button id="me">Ver mi acceso</button><button id="list">Listar acciones</button>
<pre id="out"></pre>
<div id="actions"></div>
</div>
<script>
const $=id=>document.getElementById(id);
const hdr=()=>({Authorization:'Bearer '+$('tok').value.trim()});
async function api(path,opt){const r=await fetch(path,opt);let b={};try{b=await r.json()}catch(e){}return{status:r.status,body:b}}
function show(v){$('out').textContent=typeof v==='string'?v:JSON.stringify(v,null,2)}
fetch('/healthz').then(r=>{$('dot').className='dot '+(r.ok?'ok':'bad');$('state').textContent=r.ok?'En marcha':'No responde'}).catch(()=>{$('dot').className='dot bad';$('state').textContent='No responde'});
$('me').onclick=async()=>{const r=await api('/v1/me',{headers:hdr()});show(r.status===200?r.body:'Token inválido ('+r.status+')')};
$('list').onclick=async()=>{
  const r=await api('/v1/actions',{headers:hdr()});
  const box=$('actions');box.textContent='';
  if(r.status!==200){show('Token inválido ('+r.status+')');return}
  show('Nivel efectivo: '+r.body.effective_level);
  (r.body.actions||[]).forEach(a=>{
    const row=document.createElement('div');row.className='row';
    const t=document.createElement('div');t.textContent=a.title;
    const s=document.createElement('div');s.className='risk';s.textContent=a.id+' · riesgo '+a.risk+' · '+a.decision;t.appendChild(s);
    const b=document.createElement('button');b.className='run';b.textContent='Ejecutar';
    b.onclick=async()=>{const x=await api('/v1/actions/'+encodeURIComponent(a.id),{method:'POST',headers:Object.assign({'Content-Type':'application/json'},hdr()),body:'{}'});show(x.body.status?x.status+' · '+x.body.status:x)};
    row.appendChild(t);row.appendChild(b);box.appendChild(row);
  });
};
</script></main></body></html>`
