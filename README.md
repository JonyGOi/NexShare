# NexShare

**English** · [ภาษาไทย](README.th.md)

Turn your Android phone into a tiny NAS. NexShare runs a web server on the phone
and serves a Synology-DSM-style desktop you open from any device on the same
Wi-Fi at **http://nexshare.local:8080** (or the phone's IP). Browse, upload,
download, stream and manage the phone's files from a browser.

Built with plain Java and a hand-written HTTP server — no Gradle, no AndroidX.

## Screenshots

The web desktop, open in a browser on another device:

![NexShare web desktop](docs/screenshots/web-desktop.png)

The Android app (Root edition):

<img src="docs/screenshots/app.png" alt="NexShare Android app" width="320">

*(running on real hardware; the access PIN is redacted)*

## Two editions

| | **NexShare** (normal) | **NexShare Root** |
|---|---|---|
| Package id | `com.nexshare` | `com.nexshare.root` |
| File Station, Media Player, Download Station, Wallpaper | ✓ | ✓ |
| Install APKs from the web UI | prompts on the phone | **silent** (`pm install`) |
| Terminal (root shell, bundled BusyBox ~300 tools, uses Termux packages too) | — | ✓ |
| Live screen mirror + remote tap/swipe/keys | — | ✓ |
| Keep screen on across the whole device | foreground only | `svc power stayon` |
| App manager (disable/uninstall/force-stop/clear) | open only | full |

Both are signed with the same key and have different package ids, so you can
install them side by side. The Root edition needs Magisk/SuperSU and a granted
root prompt.

## Features

- **File Station** — DSM-style windowed file browser: list/grid views, drag-and-drop
  upload (files and whole folders), multi-select, download (multiple files zip on the
  fly), copy/move/rename/delete, search, image/video thumbnails, breadcrumb nav.
- **Media Player** — playlists, resume where you left off, auto-loads matching
  `.srt`/`.vtt` subtitles, playback speed, loop/shuffle, PiP, YouTube-style
  keyboard shortcuts, and a "open in VLC" fallback for codecs the browser can't play.
- **Download Station** — download from http/https links or BitTorrent
  (magnet links and `.torrent` files, via libtorrent4j) straight to the phone,
  with pause/resume, progress, speed and peers.
- **Torrent Player (live conversion)** — a staged player: paste a magnet or pick a
  `.torrent`, press **Assistir**, choose one file and it starts. On arm64, a bundled
  FFmpeg reads only the pieces already on disk and writes fragmented MP4 on the fly,
  so MKV/AVI/TS and AC3/DTS audio start playing before the whole file downloads.
  No VLC round-trip is needed. Other ABIs fall back to the full-file MP4 remux.
- **Debrid Hub** — turn the phone into a tiny Real-Debrid. Paste a magnet and the
  video starts streaming in seconds: the biggest video file is prioritised, pieces are
  fetched sequentially, and playback throttles itself until the bytes being read are on
  disk (VLC/browser play while the torrent downloads). Ships a Real-Debrid compatible
  `/rest/1.0` API so Stremio addons, Kodi and VLC can use the phone as a debrid host.
- **Remote Access (Cloudflare Tunnel)** — expose the server on the internet with one
  tap. Get a throwaway `https://<name>.trycloudflare.com` URL, or paste a named-tunnel
  token to bind your own domain. `cloudflared` is bundled (arm64 + arm), so no Termux
  needed. Enabling a tunnel force-enables the PIN, and the app shows the public link
  with Copy / Share buttons plus a public HTTPS WebDAV URL.
- **Wallpaper** — generate NexOS-branded wallpapers and set them on the phone,
  or set any image in File Station as wallpaper.
- **Always-on Background Service & Screen-Off Mode** — runs 24/7 in the background with partial wake lock and Wi-Fi lock so you can turn off the phone's screen completely without losing file transfers or network access. Automatically exempts from Doze mode (via root shell or 1-tap exemption dialog) and auto-starts on phone boot.
- **Auto-Reconnection** — dynamically monitors network state; when Wi-Fi or internet connection drops, the service automatically stays alive, updates notifications, rejoins mDNS, and keeps reconnecting the Cloudflare internet tunnel until restored.
- **Network Drive (WebDAV)** — map your Android phone's storage directly as a Windows network
  drive (e.g. `Z:`) over HTTP/WebDAV. Open, edit, copy, and stream files natively from File Explorer.
- **Usage Monitor** (web) — one screen that answers *is the phone being used right now?*:
  a big **Em uso / Ocioso** state (with what is driving it), live ↓/↑ rates, open connections,
  files being served, storage used/free with a bar, the torrents that are downloading/seeding
  (speed + progress), totals since boot and since you opened the window.
- **System widgets** — System Health, Resource Monitor (CPU/RAM/network),
  Storage, battery.
- Reachable at `nexshare.local` via a built-in mDNS responder, with IP fallback.
  Choose your login mode: none, a random 6-digit PIN, or username + password.

## Debrid Hub (phone as a Real-Debrid)

The **Debrid Hub** app in the web desktop turns magnet links into instant streaming:

1. Open **Debrid Hub** → paste a magnet (or a `.torrent`/direct URL) → **Desbridar & Transmitir**.
   The torrent is added **paused** (like qBittorrent's "add torrent" dialog), so nothing is
   downloaded before you choose what you want.
2. Wait for the metadata, tick the folders/files you want and press **▶ Aplicar e baixar**.
   Only the ticked files are downloaded; the biggest selected video gets top priority with a
   sequential piece range and piece deadlines.
3. Pick a file and either **Assistir aqui** (in-browser player) or **Link VLC** and open it
   in VLC/MPV/Kodi/Smart TV. Playback starts right away — the HTTP server writes only the
   bytes already on disk and waits for the pieces being read, instead of serving zeros.
4. Right below the torrent list, the **torrent contents** appear as a folder tree with
   checkboxes — exactly how it will be written to the phone:
   `📁 Serie/` → `📁 Temporada 1/` → `ep01.mkv`, `ep02.mkv`…
   Uncheck whole folders or single files and press **Aplicar seleção**; only what is
   checked is downloaded (`Marcar tudo`, `Desmarcar` and `Só vídeos` do it in one click,
   which is handy for skipping samples, `.nfo`, subtitles you don't need or extra languages).
   The choice is saved with the torrent, so it survives restarts.
5. Each torrent has a **⋯ menu**: *Pausar*, *Retomar*, *Remover torrent* (keeps the files)
   and *Remover e apagar arquivos do celular*.

Every link carries the device API key, so it keeps working after restarts.

### Real-Debrid compatible API

For clients that speak the Real-Debrid API (Stremio debrid addons, Torrentio-style
resolvers, Kodi addons):

| | |
|---|---|
| Base URL (LAN) | `http://<phone-ip>:8080/rest/1.0/` |
| Base URL (internet) | `https://<name>.trycloudflare.com/rest/1.0/` |
| API key | shown in Debrid Hub → *Usar no VLC / Stremio / Windows* |

Send the key as `Authorization: Bearer <key>`, `X-Api-Key: <key>` or `?token=<key>`.
Implemented endpoints: `/user`, `/torrents`, `/torrents/addMagnet`, `/torrents/addTorrent`,
`/torrents/info/{id}`, `/torrents/selectFiles/{id}`, `/torrents/delete/{id}`,
`/torrents/instantAvailability/{hash}`, `/unrestrict/link`, `/hosts/domains`, `/time/iso`.
`selectFiles` really applies the choice: ids not sent become *ignored* in libtorrent
(`files=all` downloads everything, `files=none` downloads nothing), and `/torrents/info`
reports `selected`/`done`/`progress` per file. Extra endpoints used by the bundled clients:
`/torrents/pause/{id}`, `/torrents/resume/{id}`, `/torrents/delete/{id}?files=1`,
`/api/hash?path=` (SHA-256 of a file, used to verify a copy before deleting it),
`/torrents/purgeFile/{id}?file=N` (forget + delete one file, frees space while the rest still
downloads), `free_space`/`total_space` in `/user`, `error` in `/torrents/info`,
`?paused=1` on `addMagnet`/`addTorrent` (add without downloading) and `eta` (seconds left)
in the task JSON.
The API requires the same login as the rest of NexShare (PIN or username+password).

## Nuvio / Stremio addon

NexShare **is** the addon: install it in Nuvio (or Stremio) and the phone resolves the streams.

1. Debrid Hub → *Usar no VLC / Stremio / Windows* → **3. Nuvio / Stremio** → copy the manifest URL
   (`http://<phone-ip>:8080/addon/<key>/manifest.json`, or the tunnel URL when you are away).
2. Nuvio: **Settings → Content & Discovery → Addons** → paste → **Install Addon**.
   Stremio: **Addons → Install from URL**.
3. Pick a movie/episode and tap a stream marked **▶ desbridar no celular (NexShare)**.

How it works: the addon asks [Torrentio](https://torrentio.strem.fun) for torrents (no debrid key
needed) and turns every result into a link to `…/addon/<key>/play?h=<hash>&i=<file>`. Nothing is
downloaded while you browse — the torrent is added in **metadata-only mode** when you tap a
stream, then the exact file (`fileIdx` from Torrentio, or the biggest video) is selected and
served to the player with full `Range` support (seeking works while it downloads). Until then
every file is held at `IGNORE`; only the torrent metadata is fetched. Torrents created by the
addon are flagged and skipped by the desktop app's auto-offload, so the file never disappears
mid-movie. `/addon/<key>/error` answers JSON (not 404) when a player probes it after a failure,
the manifest/stream endpoints send CORS headers, and the web UI is `noindex`, so search engines
do not list the tunnel. The **Testar addon** button (`/api/addon/test`) checks whether the
phone can reach Torrentio.

## Desktop app (Windows): NexShare Debrid

`desktop/NexShareDebrid.ps1` (+ `NexShareDebrid.cmd`) is a small Windows app that uses the
phone as a debrid server and **downloads straight to the PC** — no browser, no Explorer.

- Needs only Windows 10/11 (PowerShell 5.1 is built in). Nothing to install.
- Double-click **`NexShareDebrid.cmd`**.
- Fill **Servidor** (`http://<phone-ip>:8080` or the public tunnel URL) and **API key**
  (Debrid Hub → *Usar no VLC / Stremio / Windows*), then **Conectar**.
- Paste a magnet (or **Abrir .torrent...**) → the torrent appears with progress, speed and
  seeders. Select it to see the files.
- As soon as the metadata arrives, **"Adicionar torrent - escolha os arquivos"** opens with
  the torrent's folder tree (the same tree the phone will create). Tick what you want, use
  **Marcar tudo / Desmarcar / Só vídeos / Vídeos + zips**, check the total size, then
  **Baixar selecionados**. **Nothing is downloaded before that click**; **Cancelar** discards
  the torrent ("Torrent descartado - nada foi baixado"). **Selecionar arquivos...** reopens
  the picker later; the file list marks what was left out as `ignorado (nao baixa)`.
- Select a file and **Baixar para o PC** (progress bar and cancel), **Copiar link** or
  **Abrir no VLC** (streams while the phone downloads).
- **Remover** asks whether to delete the files from the phone too.
- Downloads go to `%USERPROFILE%\Downloads\NexShare` by default (**Pasta...** changes the
  default for the next torrents; the picker shows the folder this torrent will use).
  Settings live in `%APPDATA%\NexShareDebrid\config.json`.
- **Unidade** runs `map-drive.ps1` with the connected server and API key: the phone storage
  appears as a drive in Explorer without certificate, admin or registry changes.

### Transfer queue: phone → PC, then free the phone

The app keeps a **queue** (`queue.json`) so it can move a whole torrent to the PC in the
background and only then delete it from the phone:

- **Mandar pro PC automaticamente** (on by default): every file the phone finishes is queued
  and transferred right away, one by one. Files still downloading wait their turn
  (*"esperando o celular terminar"*).
- **Resume**: the bytes already written stay in a `.part` file next to the destination and the
  transfer continues with an HTTP `Range` request — close the app, reboot, whatever; it picks
  up exactly where it stopped. `queue.json` remembers the file, the byte count and the status.
- **ETA**: the status bar and the **Fila** window show speed, percentage and *faltam ~12min*
  for the file being transferred.
- **Safety margin before deleting**: a file is only accepted after a **SHA-256 comparison**
  between the phone (`/api/hash`) and the PC copy (size alone is never enough; the fallback for
  older phones is a size check plus 6 block comparisons spread across the file). If the hash
  differs, nothing is deleted and the item shows *"verificação falhou — não apaguei do celular"*.
- **apagar do celular depois de verificar** (on by default): when *every* selected file of a
  torrent is on the PC and verified, the app removes the torrent **and its files** from the
  phone (`torrents/delete?id&files=1`) and frees the space. Files are never deleted one by one
  while the torrent is still seeding — that would make libtorrent re-download them.
- **Espaço livre mínimo no PC** (default 5 GB, in the *Fila* window): the queue pauses instead
  of filling the disk.
- The **Fila** window lists everything: `No PC`, `Falta`, status, plus *Pausar fila*,
  *Tentar de novo os que falharam* and *Limpar concluídos*.

### Torrent bigger than the phone can hold (90 GB on a 50 GB phone)

No problem — it downloads in a rolling window instead of all at once:

1. Torrents download **in file order** (sequential), so early files finish first.
2. As soon as a file is verified on the PC it is **removed from the phone**
   (`torrents/purgeFile`, which marks the file *ignored* in libtorrent so it is never
   downloaded again) and the space comes back for the next file.
3. The phone also protects itself: below **2 GB free** it pauses the torrent by itself with a
   clear message (*"Pouco espaço no celular…"*), and resumes automatically once the PC frees
   space (above 3 GB).

Only a **single file larger than the free space** is physically impossible (the phone cannot
hold it at all) — the app pauses with that message instead of filling the disk.

```
desktop/
  NexShareDebrid.ps1   the app (WinForms UI, API client, downloader)
  NexShareDebrid.cmd   launcher (hidden console)
```

## Remote access in restricted networks

School, hotel and corporate Wi-Fi usually block device-to-device traffic, non-standard
ports and plain-HTTP WebDAV. In that case **do not** try to reach the phone by IP:

1. In the app, turn on **Remote Access / Internet (Cloudflare)** (or *Remote Access* in the
   web Control Panel) and wait for `https://<name>.trycloudflare.com`.
2. Use that HTTPS URL from any network — home, 4G/5G, faculty Wi-Fi. Nothing to port-forward.
3. The same URL also serves `/dav/`, so Windows can mount it as a drive over TLS (see below).

The public URL changes every time a *Quick Tunnel* restarts; use a named tunnel with your
own domain if you need a stable address.

### Cloudflare tunnel troubleshooting

The app now preflights and self-heals, but these are the usual mix-ups:

| Symptom | What it means | Fix |
|---|---|---|
| `Provided Tunnel token is not valid` | You pasted an **API Token**, a tunnel **UUID**, or the dashboard URL instead of the connector token | Zero Trust → Networks → Tunnels → *your tunnel* → **Configure** → **Install connector**: copy only the text after `--token` (it starts with `eyJh` and is long) |
| Named tunnel connects but the link does not open | The tunnel has no **Public hostname** route | In the tunnel's *Public hostnames*, add `nas.yourdomain.com` → `http://localhost:8080`, then type `nas.yourdomain.com` in the *Domínio público* field |
| Stuck on "Connecting…" then timeout | Network blocks **QUIC/UDP 7844** (typical on campus/corporate Wi-Fi) | Nothing to do — NexShare retries automatically over **HTTP/2 (443) + IPv4**; the panel shows *tentativa 2/3* |
| `failed to resolve any edge addresses` / `lookup … :53` | Android has no `/etc/resolv.conf`, so the static cloudflared binary needs the phone's DNS | Already handled: the phone's DNS servers are passed via `TUNNEL_DNS_RESOLVER_ADDRS`; 3rd attempt uses 1.1.1.1/8.8.8.8. Disable Private DNS/VPN if it still fails |
| `failed to request quick Tunnel` / `429` | Cloudflare rate-limited throwaway tunnels | Wait a minute, or use a named tunnel |
| Cloudflare **Error 1033** / **503** | The tunnel has no active connection with the edge: cloudflared stopped, the phone slept, or the network changed | 1.10.11 checks the public URL and restarts cloudflared after three gateway failures. If it persists, open **Tunnel diagnostics** and confirm the process is connected |
| **504 Gateway Timeout** (especially when opening a torrent video) | The edge waited too long for the first bytes. Before 1.10.11 the server buffered the response headers until the first piece arrived; that is fixed. A Quick Tunnel can still time out on very slow swarms | Update to 1.10.11, keep the app in the foreground notification, and prefer a named tunnel for large videos |
| Logs say `flag provided but not defined` | Older binary/CLI mismatch | Update the APK |

Use **Remote Access → Testar conexão / diagnóstico** (web) or **Tunnel diagnostics** (Android)
to see binary/arch, internet, the phone's DNS servers, reachability of
`api.trycloudflare.com` and `region1.v2.argotunnel.com`, the current attempt and the last logs.

## Download

Grab the APK for your edition from the [Releases](../../releases) page:

- `NexShare-vX.Y.Z.apk` — normal edition
- `NexShare-Root-vX.Y.Z.apk` — root edition

Enable "install from unknown sources", install, open the app, grant
**All files access**, and the server starts. Open the shown URL from another
device on the same Wi-Fi.

## Network drive (WebDAV)

You can mount your Android phone's storage as a network drive in Windows (e.g. `Z:`)
using the built-in WebDAV server running at `http://<phone-ip>:8080/dav/`.

### 1. Enable WebDAV on Android
1. Open the **NexShare** app (or open Control Panel in the web desktop).
2. Toggle the **WebDAV** switch to **ON**.
   - Note: If login mode was set to "No login", NexShare automatically enforces a 6-digit PIN for security.
3. Note the displayed URL and PIN (e.g., `http://192.168.1.100:8080/dav/` and PIN `123456`).

### 2. Configure Windows

Three ways, from easiest to most manual:

#### Option A: `map-drive.ps1` + local bridge (recommended, no admin)
The script starts `desktop/NexShareBridge.ps1`, a tiny local bridge. The bridge talks to the
phone over HTTP (or HTTPS, accepting the self-signed certificate) and exposes plain HTTP on
`127.0.0.1`; Windows mounts that local address and the bridge injects the **API key** on
every request. Because Windows never sees a certificate or a remote Basic-auth challenge,
this works without registry changes, without Administrator rights and without the
`BasicAuthLevel` policy — the usual causes of **error 67**.

```powershell
.\map-drive.ps1 -PhoneIP 192.168.1.100 -Key <API key>       # home Wi-Fi, HTTP 8080
.\map-drive.ps1 -Url https://<name>.trycloudflare.com -Key <API key>  # anywhere
# the WebDAV PIN also works: -Pin 123456
.\map-drive.ps1 -Unmount                                    # unmount and stop the bridge
```

It starts the WebClient service, picks a free drive letter, verifies the connection,
falls back to a direct `net use` if the bridge cannot start, and explains the `net use`
code when something fails. The API key/PIN is in the app: **Debrid Hub → Usar no VLC /
Stremio / Windows**. Options: `-Drive E:`, `-NoProxy` (networks with a proxy),
`-Direct` (skip the bridge), `-Verify` (validate the phone certificate).

#### Option B: RaiDrive (no 4 GB limit, visual interface)
Windows' built-in WebDAV driver caps single files at 4 GB. **RaiDrive** bypasses that limit
and is faster for big libraries.

1. Download and install [RaiDrive](https://www.raidrive.com/) (free).
2. Open RaiDrive and click **Add** ➔ select **WebDAV** (under the NAS tab).
3. Configure the connection:
   - **Drive**: choose a drive letter (e.g. `Z:`)
   - **Address**: `http://` (uncheck HTTPS), phone IP (e.g. `192.168.1.100`), Port `8080`, Path `/dav/`
   - **Account**: `nexshare`
   - **Password**: your API key (or the 6-digit PIN)
4. Click **Connect**. Your phone's storage will mount as a full network drive with **no file size limit**.

#### Option C: Windows native client, direct (`net use` / Explorer)
Use this only when you cannot run the bridge. Windows' built-in WebClient has a 4 GB
per-file limit.

1. **Manual Setup:**
   - Double-click `windows-webdav.reg` to set `BasicAuthLevel = 2` and `FileSizeLimitInBytes = 0xffffffff`.
   - Restart the WebClient service (PowerShell as Admin):
     ```powershell
     Set-Service -Name WebClient -StartupType Automatic
     Restart-Service -Name WebClient -Force
     ```
   - Map via command line:
     ```cmd
     net use Z: http://192.168.1.100:8080/dav/ /user:nexshare *
     ```

### 3. Alternative UNC Path
You can also access the drive directly from Windows File Explorer or the Run dialog (`Win + R`):
```text
\\192.168.1.100@8080\DavWWWRoot\dav
```
*(Always use the phone's IP address instead of `nexshare.local` because Windows WebClient does not reliably resolve mDNS `.local` hostnames).*

### 4. Windows says "network name cannot be found" (error 67)

Error 67 from `net use` means Windows never got a usable WebDAV answer. **Run
`map-drive.ps1` first** — its local bridge removes the three most common causes at once
(certificate, Basic-over-HTTP policy and proxy). If you still want the direct route:

| Cause | Fix |
|---|---|
| Windows does not trust the phone's self-signed certificate | Use the bridge (default) or the public tunnel URL, whose certificate is from a public CA |
| Windows refuses Basic auth over plain HTTP (`BasicAuthLevel`, default policy) | Use the bridge (localhost, no auth challenge) or HTTPS |
| The network blocks peer-to-peer traffic or port 8080 | Use the public HTTPS tunnel URL (port 443) |
| Corporate proxy intercepts the request | `map-drive.ps1 ... -NoProxy` (the bridge then connects directly) |
| Stale credentials for the host | `net use * /delete /y` and retry |
| WebClient service stopped | `Start-Service WebClient` (the script does this for you) |

With a tunnel running, the whole thing becomes one command:

```powershell
.\map-drive.ps1 -Url https://<name>.trycloudflare.com -Key <API key>
# direct, no bridge (may need the registry/BasicAuthLevel):
net use Z: https://<name>.trycloudflare.com/dav/ /user:nexshare <API key> /persistent:no
```

The Control Panel and the Android app show this public WebDAV URL (tap to copy) whenever
WebDAV and Remote Access are both on. The API key is accepted as the WebDAV password
(in addition to the PIN), so the same key works in VLC, Stremio/Nuvio, the desktop app and
the network drive.

## Known limitations

- **HTTP without encryption**: WebDAV runs over plain HTTP on your local Wi-Fi network. Always keep PIN protection enabled, especially on shared Wi-Fi networks. Over the internet always use the tunnel's HTTPS URL.
- **No SMB**: Pure Java implementation without external native SMB/Samba libraries. WebDAV is used because it runs on standard HTTP/1.1 and integrates natively with Windows File Explorer.
- **Windows WebClient file size limit**: The native Windows WebClient service has a hard architectural cap of 4 GB (`0xffffffff` bytes) for single file transfers over WebDAV. To transfer files larger than 4 GB, use **RaiDrive** or the NexShare web interface (File Station).
- **Small files performance**: Because WebDAV issues HTTP round-trips for metadata (`PROPFIND`) on each file/folder, browsing directories with thousands of small files will be slower than a local disk.
- **Tunnel limits**: Cloudflare Quick Tunnels are free but get a new random URL on every restart and cap request bodies (uploads over WebDAV) at 100 MB. Large downloads/streams are fine; for big uploads use the LAN or a named tunnel on your own domain.
- **Debrid is local, not a cloud cache**: NexShare streams what the phone itself downloads from the swarm — it does not mirror files to a datacenter or unlock premium hosters. Speed and start-up time depend on seeders, and files stay on the phone until you delete them.
- **Live conversion is arm64-only and forward-only**: the bundled FFmpeg real-time path
  is included for `arm64-v8a` phones. On 32-bit devices the player falls back to downloading
  the selected file and remuxing it. Seeking inside the live stream restarts the conversion
  from the beginning; the full-file path keeps normal seeking. HEVC/H.265 is copied, so a
  browser without HEVC support can still refuse it (VLC remains the last resort).

## Build from source (Windows)

No Android Studio needed — the scripts fetch a private JDK + Android SDK into
`.tools/` and the libtorrent4j jars into `libs/`.

```powershell
./setup.ps1          # one-time: downloads JDK 17 + Android SDK + libtorrent4j + cloudflared (~620 MB)
./build.ps1          # builds both editions into dist/
./build.ps1 -Flavor root     # or just one edition: normal | root
```

Output: `dist/NexShare-v<version>.apk` and `dist/NexShare-Root-v<version>.apk`.

The build runs `aapt2 → javac → d8 → zipalign → apksigner` directly. A signing
keystore is generated on first build and kept in `.tools/` (git-ignored).

## Project layout

```
app/AndroidManifest.xml      app manifest (package/label rewritten per edition at build time)
app/src/com/nexshare/*.java  HTTP server, mDNS, file/download/root APIs, Android glue
app/assets/web/              the web desktop (index.html, style.css, app.js)
app/res/                     launcher icon
build.ps1 / setup.ps1        Gradle-less build + toolchain bootstrap
desktop/                     Windows debrid client (PowerShell, no install)
libs/                        libtorrent4j jars + cloudflared + busybox (fetched by setup.ps1)
```

## Notes & safety

- `usesCleartextTraffic` is on because the server is plain HTTP on the LAN; keep the
  PIN enabled on untrusted networks. Routers with AP/client isolation block
  device-to-device access.
- The Root edition executes shell commands as uid 0 at your request. The terminal,
  app manager and installer act on the device with full privileges — use them as
  carefully as any root tool.
