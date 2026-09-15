# Claude Code

**Módulo de Kairos** — instalación gestionada por la app vía `ModuleController.installModule()` → `ProcessBuilder` → `modulos/claude.sh`. Sin switch on/off (no es un servicio persistente, es un CLI que se invoca en la terminal).

---

**Script:** `modulos/claude.sh`
**Fragment:** `ClaudeFragment.kt`
**`modules.json`:** `id: "claude"`, `hasSwitch: false`
**Registry prefix:** `claude.*`

---

## 1. Descripción general

Claude Code es el CLI de codificación con IA de Anthropic. En Kairos corre 100% dentro de Termux, sin proot — dos métodos de instalación posibles, elegidos por el usuario o pasados por `--variant`.

## 2. Permisos

Ninguno específico de Android — solo los ya cubiertos por el asistente general de primer uso (almacenamiento, para que los proyectos en `~/proyectos`/Download sean accesibles). No expone ningún puerto ni corre en segundo plano — es un binario que se invoca a demanda desde la terminal.

## 3. Variantes de instalación

| Variante | Método | Cuándo usarla |
|---|---|---|
| `native` (recomendada) | Binario ELF real (`downloads.claude.ai`), parcheado con `patchelf` para correr vía `glibc-runner` sobre Bionic | Default — más rápido, sin overhead de Node.js |
| `legacy` | Paquete npm `@anthropic-ai/claude-code`, corre sobre Node.js | Fallback si `native` falla o el dispositivo tiene problemas con `glibc-runner` |

Ambas variantes también aceptan `--source clean` (descarga limpia, default) o `--source github` (restaura desde un backup de respaldo alojado en GitHub Releases).

## 4. Lógica de instalación (paso a paso)

### Variante `native`
1. Actualiza Termux (`pkg update`, con checkpoint).
2. Instala `glibc-repo` → `pkg update` → `glibc-runner`/`patchelf-glibc`/`jq`, agrega `~/.local/bin` al `PATH` en `.bashrc` si falta.
3. Descarga e instala el binario:
   - Resuelve la versión (fija o `latest` vía el manifiesto oficial de releases).
   - Descarga el binario ARM64.
   - **Verifica SHA256** contra el manifiesto real — si no coincide, borra el binario y aborta, sin confiar en una descarga silenciosamente corrupta.
   - `chmod +x` + `patchelf --set-interpreter` apuntando a `$PREFIX/glibc/lib/ld-linux-aarch64.so.1` — esto es lo que le permite correr un ELF glibc real sobre Bionic sin proot.
   - Genera un wrapper que hace `unset LD_PRELOAD` antes de ejecutar el binario real (necesario para que el intérprete parcheado no herede `LD_PRELOAD` de Termux, que rompería el binario glibc).
   - Escribe `~/.claude/settings.json` (ver sección 6).
   - Actualiza el registry y verifica `claude --version` responde antes de dar la instalación por buena.

### Variante `legacy`
1. Actualiza Termux (igual que arriba).
2. Instala Node.js (`nodejs-lts` si no hay Node ≥18) + `npm install -g npm`.
3. Instala el paquete con **4 estrategias en cascada**, cada una verificada (que `cli.js` exista, no esté vacío, y `node cli.js --version` responda) antes de darse por satisfecha:
   1. `npm install -g @anthropic-ai/claude-code --save-exact` directo.
   2. Si falla: mismo `npm install` con `--ignore-scripts`.
   3. Si sigue fallando: descarga el tarball directo del registro npm y lo extrae a mano.
   4. Si sigue fallando: repara solo `cli.js` desde un backup de respaldo.
   - Genera el wrapper (`node cli.js` con `DISABLE_AUTOUPDATER=1 DISABLE_UPDATES=1`), escribe `settings.json` y aliases.

## 5. Detección de estado

No depende del registry para decidir si ya está instalado — chequea el filesystem real en cada corrida:
- `native`: existe y es ejecutable el binario nativo instalado.
- `legacy`: existe el `cli.js` del paquete npm global.
- `broken`: existe el wrapper pero no el binario/paquete real detrás — dispara reinstalación.
- `none`: nada de lo anterior.

Si ya está instalado con el método pedido (o cualquiera, si no se especifica `--variant`) y no hay `--force`, el script igual re-sincroniza el registry antes de salir — evita que un binario funcionando quede mostrando "No instalado" en la app por un registry desactualizado.

Del lado de la app, Claude Code es un módulo sin switch (`hasSwitch: false`) — la detección de instalación es lo único que gatea la UI, sin concepto de "corriendo/detenido".

## 6. `settings.json`

`~/.claude/settings.json` no escribe una key `"autoUpdates": false` — esa key no existe en el schema real de Claude Code. En su lugar:
```json
{
  "env": {
    "DISABLE_AUTOUPDATER": "1",
    "DISABLE_UPDATES": "1"
  }
}
```
(la variante `native` agrega además `LD_PRELOAD` apuntando a `libtermux-exec-ld-preload.so` dentro de `env`). Sin esto, el binario `native` (parcheado con `patchelf`) puede autoactualizarse y sobreescribirse con un ELF sin parchear que ya no corre en Bionic.

## 7. Pantalla de la app (`ClaudeFragment.kt`)

- **Card ESTADO**: método, versión (leída del registry), pill de estado, pill de estado de terminal.
- **"▶ Abrir en directorio raíz (~)"** — arma el comando real según el método detectado (`unset LD_PRELOAD && ...` para native, `node cli.js` con env vars para legacy).
- **"▶ Abrir en proyecto"** — lista proyectos reales vía el gestor de proyectos compartido (symlinks + registry con file locking).
- **"📁 Gestionar proyectos"** — symlink desde Download, eliminar proyecto, sincronizar todos.
- **Card PROMPT DIRECTO — "💬 Enviar prompt (no interactivo)"** — diálogo de texto libre para el prompt, seguido de un diálogo para elegir modelo (`Default` omite `--model`, o `sonnet`/`opus`/`haiku`/`fable`), y un tercer diálogo para elegir `--permission-mode` (`Default`, o `default`/`acceptEdits`/`plan`/`auto`/`dontAsk`/`bypassPermissions`). Arma `-p '<prompt escapado>' --model <alias> --permission-mode <modo>` — modo headless real del CLI (`-p`/`--print`: un prompt entra, una respuesta sale, sin REPL).
- **Card SESIÓN**:
  - **"↻ Continuar última conversación"** — `claude --continue`.
  - **"🕒 Reanudar sesión…"** — `claude --resume` (picker interactivo del propio CLI).
- **Card DIAGNÓSTICO**:
  - **"🔍 claude doctor"** — diagnóstico de solo lectura (instalación/settings/Remote Control), sin sesión interactiva, resultado en un diálogo.
  - **"🔑 Estado de autenticación"** — `claude auth status`, imprime JSON con el estado de sesión, resultado en un diálogo.
  - Ambos corren en background y muestran el resultado sin abrir terminal.
- **Card MCP** — "🔌 Ver servidores MCP" lee `~/.claude.json` directo (sin invocar el CLI) y lo muestra en un panel nativo; `claude mcp list` en terminal queda como alternativa explícita dentro de ese mismo panel, no como acción principal.
- **Card CUENTA — TOKEN OAUTH FIJO** — permite fijar la variable `CLAUDE_CODE_OAUTH_TOKEN` (generada con `claude setup-token`, requiere plan Pro/Max/Team/Enterprise) para que la app siempre abra con la cuenta de ese token en vez de la cuenta de sesión interactiva. El token se guarda en `~/.claude_oauth_token` (`chmod 600`) y se exporta tanto en `.bashrc` (terminal interactiva) como inline en cada comando armado por la app (llamadas en background no interactivas no sourcean `.bashrc`). Cuando el archivo existe, la UI muestra "⚠ Usando token OAuth fijo (no la cuenta de sesión)" — esto es importante porque, con la variable seteada, Claude Code la usa en silencio en vez de las credenciales de sesión normal, y ese es un punto de confusión conocido del propio CLI. Limitación real: un token OAuth fijo está scoped solo a inferencia — no puede abrir sesiones de Remote Control.
- **Card PROMPT DIRECTO — "🔧 Permisos de herramientas"** — diálogo con 2 campos de texto (permitir/denegar, separados por coma) que se aplican al mismo flujo de prompt directo como `--allow-tool '<tool>'`/`--deny-tool '<tool>'` repetido por herramienta.
- **"⚙ Instalar / cambiar método"** — reinstala vía `ModuleController.installModule()`.
- **Card "MANTENIMIENTO" — "🗑 Desinstalar"**.

## 8. Registry

```
claude.installed=true
claude.version=<x.y.z>
claude.method=native|legacy
claude.install_date=<YYYY-MM-DD>
claude.location=termux_native
```

## 9. Notas técnicas

- **Conflicto de métodos**: si el usuario tiene `legacy` instalado y pide `native` (o viceversa) en modo `--silent`, el script desinstala el método viejo automáticamente antes de instalar el nuevo (en modo interactivo, pregunta primero).
- El prompt directo se manda con escapado mínimo de comilla simple (no vía shell completo) para evitar inyección.
