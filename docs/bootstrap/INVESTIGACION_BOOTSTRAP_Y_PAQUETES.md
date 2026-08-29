# Investigación: bootstrap propio, mirror de paquetes y aislamiento de archivos

Este documento reúne los hallazgos de una investigación sobre tres ideas relacionadas con el
bootstrap de Termux que usa la aplicación: personalizar el bootstrap, alojar un mirror propio de
paquetes, y si tiene sentido "esconder" archivos operativos fuera del alcance de un usuario sin
privilegios de root.

## 1. Cómo funciona el bootstrap hoy

El bootstrap (el conjunto mínimo de `bash`, `dpkg`, `apt` y coreutils necesario para que exista
el prefijo de instalación y `apt` funcione) **no se descarga desde ninguna URL en tiempo de
ejecución**. Se descarga en **tiempo de compilación** directamente del repositorio público y
oficial de `termux-packages`, con un checksum SHA-256 fijado por arquitectura y verificado byte a
byte — si el checksum no coincide, la compilación falla de forma dura, sin continuar con un
bootstrap potencialmente corrupto.

El zip descargado se compila dentro de una librería nativa que forma parte del APK. El código
nativo que la envuelve es un wrapper trivial: solo expone el contenido del zip embebido a la capa
Java/Kotlin de la aplicación — no hay ninguna transformación, ni un `sources.list` propio, ni
nada específico de Kairos en ese paso. El archivo que termina compilado dentro del APK es, byte
por byte, el que publica el proyecto oficial de Termux, sin modificar.

El archivo `sources.list` de APT (el que le indica al gestor de paquetes dónde buscar) también
viene tal cual del bootstrap oficial, apuntando al repositorio público estándar de Termux — no es
algo que este proyecto configure o reescriba en el pipeline de compilación. Si en el futuro se
quisiera apuntar a un mirror propio, el punto de intervención correcto sería reescribir ese
archivo **después** de que `apt` ya está funcionando en el dispositivo (una operación trivial en
tiempo de ejecución), no tocar el pipeline de compilación del bootstrap.

## 2. Identidad de paquete: la aplicación usa el mismo identificador que Termux

La aplicación comparte deliberadamente el mismo identificador de paquete y el mismo `UID`
compartido (`sharedUserId`) que la aplicación oficial de Termux. Esto no es "compartir UID con
otra app" en el sentido de coexistir — es la misma identidad de paquete, por lo que la
aplicación y el Termux oficial **no pueden estar instalados al mismo tiempo** en el mismo
dispositivo. La decisión es intencional: preserva la compatibilidad con los complementos
oficiales del ecosistema Termux (por ejemplo los que dan acceso a la API del sistema o los que
disparan tareas al arrancar el dispositivo), que buscan específicamente ese identificador de
paquete. La consecuencia práctica es que, en el dispositivo, la aplicación reemplaza a Termux, no
convive con él.

## 3. Aislamiento de archivos: el directorio privado de la app y el `$HOME` de Termux son el mismo árbol de permisos

Se investigó la hipótesis de si el directorio privado de la aplicación (el sandbox estándar de
cualquier app Android) ofrecía algún aislamiento adicional frente a `$HOME` de Termux, de cara a
"esconder" archivos operativos de un usuario sin privilegios de root.

**Conclusión: no hay diferencia de permisos entre ambos.** El directorio raíz de archivos de la
app y `$HOME` de Termux vienen del mismo árbol — `$HOME` es simplemente un subdirectorio dentro
de ese árbol. Cualquier sesión de shell lanzada desde la propia aplicación es un proceso hijo del
proceso de la app, hereda el mismo identificador de usuario del sistema operativo automáticamente
y por lo tanto ya tiene acceso completo a todo ese árbol, no solo a `$HOME`.

No existe entonces ninguna subcarpeta "más oculta" dentro del propio sandbox de la aplicación:
todo lo que está bajo ese árbol es igual de invisible para otras apps sin privilegios de root, e
igual de accesible para la propia sesión de shell de la aplicación, sea `$HOME` o cualquier otra
ruta. La única diferencia real es de convención de dónde el usuario espera encontrar cada cosa
al explorar su propia sesión, no de permisos del sistema operativo.

## 4. Mirror de paquetes propio: viable, con un alcance mucho más acotado de lo que parece a primera vista

- La política oficial del proyecto Termux para forks con distribución propia es explícita: se
  recomienda montar un repositorio propio en lugar de depender del host oficial para el tráfico
  de un fork con distribución masiva.
- No hace falta recompilar nada: la forma recomendada de espejar el repositorio es una
  sincronización periódica (`rsync`) que copia los binarios ya compilados, sin pasar por el
  pipeline completo de compilación cruzada del proyecto de paquetes de Termux.
- Como la aplicación preserva el mismo identificador de paquete compartido y el mismo prefijo de
  instalación que Termux oficial, los paquetes oficiales ya son binariamente compatibles — no
  hace falta "forkear" los paquetes en el sentido de recompilarlos.
- El alcance real de paquetes que la aplicación usa es acotado: unas pocas decenas de paquetes en
  una única arquitectura (ARM64). Un mirror parcial de solo esos paquetes representa un problema
  de tamaño trivial (decenas de megabytes), que cabe cómodamente en cualquier hosting estático
  gratuito.
- El riesgo real de mantener un mirror propio no es quedar desactualizado en seguridad (una
  sincronización periódica resuelve eso) — aparece únicamente si en algún momento se quisiera
  divergir de los paquetes oficiales con parches propios, lo cual sí exigiría el pipeline
  completo de compilación y una carga de mantenimiento continua real.

## 5. Dos capas de bootstrap, independientes por diseño

Vale la pena dejar explícito algo que resulta fácil de confundir: hay dos mecanismos separados
que ambos se llaman coloquialmente "bootstrap".

| Capa | Qué instala | De dónde sale | Repositorio fuente |
|---|---|---|---|
| Bootstrap mínimo | `bash`, `dpkg`, `apt`, coreutils | Zip oficial de `termux-packages`, compilado en tiempo de build | Repositorio oficial y público de Termux |
| Rootfs embebido | Paquetes base adicionales (Git, Python, Node.js, herramientas de compilación) | Artefacto propio generado a partir del índice público de paquetes de Termux, instalado vía `apt` en tiempo de ejecución | Repositorio de distribución propio de la aplicación |

La capa 1 nunca depende de la visibilidad del repositorio propio — siempre es pública y oficial.
La capa 2 sí depende de cómo se distribuya el artefacto propio del rootfs (ver
`ROOTFS_EMBEBIDO.md`). Cualquier plan futuro de "mirror propio" debería decidir explícitamente si
apunta a reemplazar la capa 1, la capa 2, o ambas — hoy son independientes y no comparten código
de descarga.

## 6. Mecanismo de recuperación ante un prefijo de instalación corrupto

No existe, ni en el código base de Termux oficial ni en ningún fork de referencia revisado, un
mecanismo de reparación granular de un entorno de instalación corrupto. El patrón universal —
tanto en el proyecto oficial como en cualquier fork revisado — es "borrar todo y volver a
extraer desde cero", ya sea manualmente o mediante un botón de reintento. La recomendación
conocida en la comunidad de Termux ante un bootstrap roto es, literalmente, desinstalar y volver
a instalar la aplicación.

Esto significa que un diagnóstico más inteligente (categorizar el tipo de corrupción y aplicar
una reparación selectiva en lugar de una re-extracción completa) sería una mejora real sobre el
estado del arte del propio ecosistema Termux, no solo sobre esta aplicación en particular — no
hay ningún precedente ya construido para adoptar, tendría que diseñarse desde cero.

## Conclusiones

| Idea | Viabilidad | Esfuerzo | Recomendación |
|---|---|---|---|
| Bootstrap propio personalizado | Media — requiere tocar el pipeline de compilación nativa protegido | Medio-alto | No es necesario hoy — no hay una necesidad concreta identificada más allá de la idea abstracta de "personalizarlo" |
| Mirror propio parcial (sincronización de unas pocas decenas de paquetes) | Alta — es lo que el propio proyecto Termux recomienda para forks | Bajo | Viable a futuro si se busca independencia real del host oficial, aunque no urgente mientras el repositorio oficial siga disponible |
| Recompilar/forkear paquetes con cambios propios | Baja para el alcance de este proyecto | Alto y continuo | No recomendable salvo que aparezca la necesidad concreta de un parche que el oficial no ofrezca |
| Aislar archivos operativos en el sandbox privado en vez de `$HOME` | No aporta beneficio real | N/A | Descartada — mismo árbol de permisos, solo cambiaría la convención de ubicación |
| Reparación granular de un entorno corrupto | Alta — patrón concreto y bien definido, sin precedente ya construido para copiar | Bajo-medio | Mejora real a futuro, de bajo riesgo |
