/* Torrent Player: plays video straight from a torrent, downloading only the pieces needed. */
function TorrentPlayer(body, win) {
  const st = {
    tasks: [], id: null, data: null, loading: false, timer: null, busy: false,
    playing: null, videoEl: null, playError: '', showAll: false, destroyed: false,
    ephemeral: false, discarding: false, refreshing: false, listError: false,
    prep: null, prepTimer: null, liveTimer: null,
    // start -> pick a file -> player. The initial screen intentionally shows only
    // the magnet/.torrent field and one confirmation button.
    stage: 'start', torrentFile: null, starting: false, startingFile: null
  };
  const versionMeta = document.querySelector('meta[name="nexshare-version"]');
  const buildVersion = versionMeta ? versionMeta.getAttribute('content') : '';
  const pane = el('div', { class: 'pane tp-pane' });
  body.append(pane);

  const input = el('textarea', {
    class: 'tp-input',
    rows: 3,
    placeholder: 'Cole um link magnet (magnet:?xt=urn:btih:...)'
  });
  const fileInput = el('input', {
    type: 'file', accept: '.torrent,application/x-bittorrent', class: 'hidden',
    onchange: () => chooseTorrentFile()
  });
  const filePick = el('span', { class: 'hint tp-file-picked', text: 'Nenhum .torrent selecionado' });
  const addBtn = el('button', { class: 'btn primary', onclick: () => confirmStart() }, icon('play'), ' Assistir');
  const uploadBtn = el('button', { class: 'btn', onclick: () => fileInput.click() }, icon('download'), ' Escolher .torrent');
  const addStatus = el('div', { class: 'hint tp-add-status', text: 'Nada fica salvo: o torrent baixa só enquanto você assiste. Ao parar ou fechar, os arquivos temporários são apagados.' });
  const tasksBox = el('div', { class: 'tp-tasks' });
  const detail = el('div', { class: 'tp-detail' });

  const startCard = el('div', { class: 'card tp-add tp-add-start' },
    el('div', { class: 'tp-add-head' },
      el('div', null,
        el('h3', { style: { margin: '0 0 4px' }, text: 'Torrent Player' }),
        el('div', { class: 'hint', text: 'Cole um magnet ou escolha um arquivo .torrent e clique em Assistir. Depois você escolhe o vídeo.' }),
        buildVersion ? el('div', { class: 'hint', text: 'Player v' + buildVersion }) : null),
      el('span', { class: 'tp-live', text: 'streaming' })),
    input,
    el('div', { class: 'tp-add-row' },
      addBtn,
      uploadBtn,
      fileInput,
      filePick),
    addStatus);

  const bodyBox = el('div', { class: 'tp-body tp-single' },
    el('div', { class: 'card tp-main' }, detail));

  pane.append(startCard, bodyBox);

  input.addEventListener('input', () => {
    // Typing a magnet replaces a previously chosen .torrent (one source at a time).
    if (input.value.trim() && st.torrentFile) {
      st.torrentFile = null;
      fileInput.value = '';
      updateFilePick();
      setAddStatus('Nada fica salvo: o torrent baixa só enquanto você assiste. Ao parar ou fechar, os arquivos temporários são apagados.', false);
    }
  });

  function videoFile(f) {
    return /\.(mp4|m4v|webm|mkv|3gp|mov|avi|ts|m2ts|mpg|mpeg|wmv|flv|vob|ogv)$/i.test((f && (f.name || f.path)) || '');
  }

  function baseName(p) {
    const s = String(p || '');
    const i = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
    return i >= 0 ? s.slice(i + 1) : s;
  }

  function dirName(p) {
    const s = String(p || '');
    const i = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
    return i > 0 ? s.slice(0, i) : '';
  }

  function statusLabel(t) {
    if (!t) return '';
    if (t.status === 'metadata') return 'Buscando metadados';
    if (t.status === 'choose') return 'Escolha um arquivo';
    if (t.status === 'downloading') return 'Baixando';
    if (t.status === 'seeding') return 'Semeando';
    if (t.status === 'done') return 'Completo';
    if (t.status === 'paused') return 'Pausado';
    if (t.status === 'checking') return 'Verificando';
    if (t.status === 'error') return 'Erro';
    return t.status || 'Aguardando';
  }

  function percentOf(t) {
    if (!t || !t.total) return t && t.status === 'done' ? 100 : 0;
    return Math.max(0, Math.min(100, (Number(t.done) || 0) * 100 / t.total));
  }

  function fmtDur(sec) {
    const s = Math.max(0, Math.floor(Number(sec) || 0));
    const h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, r = String(s % 60).padStart(2, '0');
    return h ? h + ':' + String(m).padStart(2, '0') + ':' + r : m + ':' + r;
  }

  function bar(pct, cls) {
    return el('div', { class: 'tp-bar' + (cls ? ' ' + cls : '') },
      el('i', { style: { width: Math.max(0, Math.min(100, pct)) + '%' } }));
  }

  function setAddStatus(text, error) {
    addStatus.textContent = text;
    addStatus.style.color = error ? 'var(--danger)' : '';
  }

  function updateFilePick() {
    const f = st.torrentFile;
    filePick.textContent = f ? f.name + ' selecionado' : 'Nenhum .torrent selecionado';
    filePick.style.color = f ? 'var(--text)' : '';
    uploadBtn.textContent = f ? ' Trocar .torrent' : ' Escolher .torrent';
    uploadBtn.prepend(icon('download'));
  }

  function chooseTorrentFile() {
    const f = fileInput.files && fileInput.files[0];
    if (!f) return;
    st.torrentFile = f;
    // Clear the native input so picking the same file again still fires change.
    fileInput.value = '';
    input.value = '';
    updateFilePick();
    setAddStatus('Arquivo .torrent selecionado. Clique em Assistir para abrir a lista de vídeos.', false);
  }

  function renderTasks() {
    tasksBox.replaceChildren();
    const count = document.getElementById('tp-count');
    if (count) count.textContent = String(st.tasks.length);
    if (!st.tasks.length) {
      tasksBox.append(el('div', { class: 'tp-empty', text: 'Nenhum vídeo aberto. Cole um magnet acima; nada fica salvo depois.' }));
      return;
    }
    for (const t of st.tasks.slice(0, 100)) {
      const row = el('button', {
        class: 'tp-task' + (t.id === st.id ? ' active' : ''),
        onclick: () => selectTask(t.id, t.ephemeral)
      },
        el('div', { class: 'tp-task-top' },
          el('span', { class: 'tp-task-name', text: t.name || 'Torrent' }),
          el('span', { class: 'tp-task-pct', text: Math.round(percentOf(t)) + '%' })),
        el('div', { class: 'tp-task-meta' },
          el('span', { text: statusLabel(t) }),
          el('span', { text: fmtSize(t.total) })),
        bar(percentOf(t)));
      tasksBox.append(row);
    }
  }

  function stopPlayback() {
    if (st.liveTimer) { clearTimeout(st.liveTimer); st.liveTimer = null; }
    if (st.videoEl) {
      try { st.videoEl.pause(); st.videoEl.removeAttribute('src'); st.videoEl.load(); } catch (e) { /* ignore */ }
    }
    st.videoEl = null;
    st.playing = null;
    st.playError = '';
  }

  /** MKV/AVI/etc. need a container change before Chrome can play them in the page. */
  function needsBrowserPrep(f) {
    return /\.(mkv|avi|mov|ts|m2ts|mpg|mpeg|wmv|flv|vob)$/i.test((f && (f.name || f.path)) || '');
  }

  /** Same stream URL, but through the live ffmpeg endpoint. */
  function liveUrl(f) {
    const u = (f && f.streamUrl) || '';
    if (u.indexOf('/api/file?') >= 0) return u.replace('/api/file?', '/api/live?');
    const key = State.apiKey ? '&token=' + encodeURIComponent(State.apiKey) : '';
    return '/api/live?path=' + encodeURIComponent((f && (f.fullPath || f.path)) || '') + key;
  }

  /**
   * Starts the real-time path: ffmpeg converts the container/audio while the torrent is still
   * downloading and Chrome plays the first MP4 fragment. If the endpoint or codec fails, the
   * video error handler falls back to the full-file remux (and, last, VLC).
   */
  function startLive(f) {
    if (!f || !st.id) return;
    const id = st.id;
    st.stage = 'player';
    st.starting = false;
    st.startingFile = f;
    st.playError = '';
    st.playing = { task: id, index: f.id, file: f, live: true, src: liveUrl(f) };
    setAddStatus('Conversão ao vivo: o vídeo começa antes de o download terminar.', false);
    // If ffmpeg cannot produce a first fragment, do not leave the user on a silent spinner.
    if (st.liveTimer) clearTimeout(st.liveTimer);
    st.liveTimer = setTimeout(() => {
      if (st.playing && st.playing.live && st.videoEl && st.videoEl.readyState < 2) {
        beginPrepare(st.playing.file);
      }
    }, 22000);
    renderDetail();
  }

  function removeRemux(id) {
    if (!id) return;
    api('remux/remove', null, { method: 'POST', body: JSON.stringify({ id }) }).catch(() => { });
  }

  function remuxBeacon(id) {
    if (!id) return;
    try {
      const key = State.apiKey ? '&token=' + encodeURIComponent(State.apiKey) : '';
      navigator.sendBeacon('/api/remux/remove?id=' + encodeURIComponent(id) + key);
    } catch (e) { /* ignore */ }
  }

  /** Stops a pending download/remux. Never deletes a task here; the caller owns the torrent. */
  function stopPrep(removeOutput) {
    if (st.prepTimer) { clearTimeout(st.prepTimer); st.prepTimer = null; }
    const p = st.prep;
    st.prep = null;
    if (removeOutput && p && p.jobId) removeRemux(p.jobId);
  }

  /** Ends the current video and any remux work, without touching the torrent task. */
  function endPlayback() {
    const remuxId = st.playing && st.playing.remuxId;
    stopPlayback();
    stopPrep(true);
    if (remuxId) removeRemux(remuxId);
  }

  /** Deletes the current temporary torrent immediately (used by stop/close/page unload). */
  function discardBeacon(id) {
    if (!id) return;
    try {
      const key = State.apiKey ? '&token=' + encodeURIComponent(State.apiKey) : '';
      navigator.sendBeacon('/api/dl/remove?id=' + encodeURIComponent(id) + '&files=1' + key);
    } catch (e) { /* ignore */ }
  }

  /**
   * Stops the video and, for a Torrent Player session, removes the task and all temporary
   * files. A torrent borrowed from Torrent Downloader is never deleted here.
   */
  async function discardCurrent(quiet) {
    if (st.discarding) return;
    const id = st.id;
    const ephemeral = !!st.ephemeral;
    st.discarding = true;
    endPlayback();
    st.id = null;
    st.data = null;
    st.ephemeral = false;
    st.showAll = false;
    st.stage = 'start';
    st.starting = false;
    st.startingFile = null;
    if (id) st.tasks = st.tasks.filter(t => t.id !== id);
    renderTasks();
    renderDetail();
    if (id && ephemeral) {
      try { await api('dl/remove', { id, files: 1 }, { method: 'POST' }); } catch (e) { /* already gone */ }
    }
    st.discarding = false;
    if (!quiet) setAddStatus('Sessão encerrada. Torrent e arquivos temporários apagados.', false);
  }

  async function refreshTasks() {
    // The player polls while a torrent is active. Without this guard a slow response can
    // stack requests until the phone/browser starts reporting "Failed to fetch".
    if (st.discarding || st.refreshing) return;
    st.refreshing = true;
    try {
      const r = await api('dl/list', { ephemeral: 1 });
      if (st.destroyed) return;
      // Only live player sessions belong here. Torrents from Torrent Downloader stay in
      // their own app and are never copied into this temporary list.
      st.tasks = (r.tasks || []).filter(t => t && t.type === 'torrent' && t.ephemeral);
      renderTasks();
      if (st.id && st.ephemeral && !st.tasks.some(t => t.id === st.id)) {
        st.id = null;
        st.data = null;
        st.stage = 'start';
        st.starting = false;
        st.startingFile = null;
        endPlayback();
        renderDetail();
      }
      if (st.id) await loadTask(st.id);
      if (st.listError) {
        st.listError = false;
        setAddStatus('Nada fica salvo: o torrent baixa só enquanto você assiste. Ao parar ou fechar, os arquivos temporários são apagados.', false);
      }
    } catch (e) {
      if (st.destroyed) return;
      st.listError = true;
      const network = /failed to fetch|networkerror|load failed|fetch/i.test(e.message || '');
      setAddStatus(network
        ? 'Sem conexão com o servidor do celular. Vou tentar de novo automaticamente…'
        : 'Não foi possível listar os torrents: ' + e.message, true);
    } finally {
      st.refreshing = false;
    }
  }

  async function loadTask(id) {
    if (!id || st.loading || st.destroyed) return;
    st.loading = true;
    try {
      const d = await api('debrid/files', { id });
      if (st.destroyed || st.id !== id) return;
      st.data = d;
      st.ephemeral = !!(d.task && d.task.ephemeral);
      if (st.playing) updatePlayingStats();
      else renderDetail();
    } catch (e) {
      if (!st.destroyed && st.id === id && !st.data) {
        detail.replaceChildren(el('div', { class: 'tp-empty', text: 'Carregando metadados do torrent…' }));
      }
    } finally {
      st.loading = false;
    }
  }

  async function selectTask(id, ephemeral) {
    if (st.discarding) return;
    if (st.id && st.id !== id) await discardCurrent(true);
    endPlayback();
    st.stage = 'pick';
    st.starting = false;
    st.startingFile = null;
    st.id = id;
    st.data = null;
    st.ephemeral = !!ephemeral;
    st.showAll = false;
    renderTasks();
    renderDetail();
    await loadTask(id);
  }

  function updatePlayingStats() {
    const t = st.data && st.data.task;
    const elStat = document.getElementById('tp-playing-stat');
    if (elStat && t) {
      elStat.textContent = statusLabel(t) + ' · ' + fmtSize(t.done) + ' / ' + fmtSize(t.total)
        + (t.speed > 0 ? ' · ' + fmtRate(t.speed) : '');
    }
  }

  function fallbackBox(f, retry) {
    return el('div', { class: 'tp-warn' },
      el('div', { text: st.playError }),
      el('div', { class: 'tp-warn-actions' },
        // Conversion is the first choice: it keeps playback inside this page. The old
        // fallback only offered VLC, so a MKV/AC-3 file looked impossible to watch here.
        el('button', { class: 'btn primary', onclick: () => { endPlayback(); beginPrepare(f); } },
          icon('play'), ' Converter para MP4 e assistir'),
        el('button', { class: 'btn', onclick: () => openExternal(f) }, icon('play'), ' Abrir no VLC do celular'),
        el('button', { class: 'btn', onclick: () => copyLink(f) }, icon('link'), ' Copiar link'),
        el('a', { class: 'btn', href: f.streamUrl + (f.streamUrl.indexOf('?') >= 0 ? '&' : '?') + 'attach=1', download: '' }, icon('download'), ' Baixar arquivo')),
      el('div', { class: 'hint', text: 'A conversão copia o vídeo sem perder qualidade e troca o áudio AC3/DTS por AAC. Tudo acontece no celular e toca nesta página; o VLC é só uma alternativa.' }));
  }

  function cancelPrep() {
    const p = st.prep;
    endPlayback();
    st.starting = false;
    st.startingFile = null;
    st.stage = 'pick';
    if (p && p.task) api('dl/pause', { id: p.task }, { method: 'POST' }).catch(() => { });
    setAddStatus('Preparação cancelada. O torrent continua na sessão; escolha um arquivo para tentar de novo.', false);
    renderDetail();
  }

  function renderPrepare() {
    const p = st.prep;
    if (!p) return null;
    if (p.phase === 'error') {
      st.playError = p.error || 'Não foi possível preparar este vídeo para o navegador.';
      return el('div', { class: 'tp-player' }, fallbackBox(p.file, true));
    }
    const downloading = p.phase === 'download';
    const pct = downloading ? (Number(p.file && p.file.progress) || 0) : (Number(p.progress) || 0);
    return el('div', { class: 'tp-player tp-prep' },
      el('div', { class: 'tp-prep-box' },
        el('h4', { text: downloading ? 'Baixando o arquivo para preparar o navegador' : 'Convertendo para MP4 (vídeo sem reencode)' }),
        el('div', { class: 'hint', text: downloading
          ? 'MKV, AVI e TS não tocam diretamente no navegador. O player precisa completar este arquivo uma vez; o download continua sob demanda e você pode cancelar.'
          : 'O vídeo é copiado sem reencode; se o áudio for AC3/DTS, ele é convertido para AAC para tocar no navegador.' }),
        bar(pct, 'big'),
        el('div', { class: 'tp-sub' },
          el('span', { text: Math.round(pct) + (downloading ? '% do arquivo' : '% convertido') }),
          el('span', { text: downloading ? (fmtSize(p.file && p.file.done) + ' / ' + fmtSize(p.file && p.file.size)) : 'preparando para tocar no navegador' })),
        el('div', { class: 'tp-prep-acts' },
          el('button', { class: 'btn', onclick: cancelPrep }, 'Cancelar preparação'))));
  }

  function renderDetail() {
    const start = st.stage === 'start';
    startCard.classList.toggle('hidden', !start);
    bodyBox.classList.toggle('hidden', start);
    detail.replaceChildren();
    if (start) return;
    const t = st.data && st.data.task;
    if (!st.id) {
      st.stage = 'start';
      startCard.classList.remove('hidden');
      bodyBox.classList.add('hidden');
      return;
    }
    if (!t) {
      detail.append(el('div', { class: 'tp-empty', text: 'Carregando metadados do torrent…' }));
      return;
    }
    if (st.stage === 'pick') renderPick(t);
    else renderPlayer(t);
  }

  function taskHeader(t, actions) {
    return el('div', { class: 'tp-head' },
      el('div', { class: 'tp-head-main' },
        el('h3', { text: t.name || 'Torrent' }),
        el('div', { class: 'hint' }, statusLabel(t), ' · ', fmtSize(t.done), ' / ', fmtSize(t.total),
          t.speed > 0 ? ' · ' + fmtRate(t.speed) : '',
          t.eta > 0 ? ' · ETA ' + fmtDur(t.eta) : '')),
      el('div', { class: 'tp-head-acts' }, ...actions));
  }

  function renderPick(t) {
    const files = (st.data.files || []);
    const vids = files.filter(videoFile);
    const show = st.showAll || !vids.length ? files : vids;
    const pct = percentOf(t);
    detail.append(
      taskHeader(t, [
        el('button', { class: 'btn', onclick: () => discardCurrent(false) }, 'Cancelar'),
      ]),
      bar(pct, 'big'),
      el('div', { class: 'tp-sub' },
        el('span', { text: Math.round(pct) + '% baixado' }),
        el('span', { text: (vids.length ? vids.length + ' vídeo(s)' : files.length + ' arquivo(s)') })),
      el('div', { class: 'tp-pick-note' },
        icon('play'),
        el('span', { text: 'Escolha um arquivo. Só um por vez: ao clicar, o player abre e começa a baixar os pedaços necessários.' })),
      ...(files.length > vids.length && vids.length
        ? [el('div', { class: 'tp-files-head' },
            el('span', { text: 'Vídeos do torrent' }),
            el('button', { class: 'btn', onclick: () => { st.showAll = !st.showAll; renderDetail(); } }, st.showAll ? 'Só vídeos' : 'Mostrar todos'))]
        : []),
      ...(!files.length ? [el('div', { class: 'tp-empty', text: 'Buscando a lista de arquivos no enxame…' })] : []),
      el('div', { class: 'tp-files' }, ...show.map(f => pickRow(f)))
    );
  }

  function pickRow(f) {
    const name = baseName(f.path || f.name);
    const folder = dirName(f.path || f.name);
    return el('button', {
      class: 'tp-file tp-file-pick', type: 'button', title: 'Assistir ' + name,
      onclick: () => pickFile(f)
    },
      el('span', { class: 'tp-file-main' },
        el('span', { class: 'tp-file-name' }, name),
        el('span', { class: 'hint' }, (folder ? folder + ' · ' : '') + fmtSize(f.size) + ' · ' + Math.round(Number(f.progress) || 0) + '%')),
      el('span', { class: 'tp-file-acts' },
        el('span', { class: 'btn primary' }, icon('play'), ' Assistir')));
  }

  async function pickFile(f) {
    if (st.busy) return;
    st.stage = 'player';
    st.starting = true;
    st.startingFile = f;
    st.playError = '';
    renderDetail();
    await playFile(f);
  }

  function chooseAnother() {
    const id = st.id;
    endPlayback();
    // Stop payload traffic while the user is back on the picker; metadata stays.
    if (id) api('dl/pause', { id }, { method: 'POST' }).catch(() => { });
    st.starting = false;
    st.startingFile = null;
    st.stage = 'pick';
    renderDetail();
  }

  function renderPlayer(t) {
    const pct = percentOf(t);
    const actions = [
      el('button', { class: 'btn', onclick: chooseAnother }, 'Escolher outro arquivo'),
      el('button', { class: 'btn', onclick: () => discardCurrent(false) }, 'Parar e apagar'),
    ];
    detail.append(taskHeader(t, actions), bar(pct, 'big'),
      el('div', { class: 'tp-sub' },
        el('span', { text: Math.round(pct) + '% baixado' }),
        el('span', { text: st.playing && st.playing.file ? baseName(st.playing.file.name || st.playing.file.path) : 'preparando o vídeo' })));

    if (st.prep) {
      detail.append(renderPrepare());
    } else if (st.playing) {
      if (st.playError) {
        detail.append(el('div', { class: 'tp-player' }, fallbackBox(st.playing.file)));
      } else {
        if (st.playing.live) {
          detail.append(el('div', { class: 'tp-live-note' },
            icon('play'), el('span', { text: 'Conversão ao vivo: o vídeo toca enquanto o restante do arquivo baixa. Arrastar a barra pode reiniciar a conversão.' })));
        }
        detail.append(el('div', { class: 'tp-player' },
          el('div', { class: 'tp-video-wrap' },
            (() => {
              const v = el('video', {
                class: 'tp-video', controls: true, autoplay: true, playsinline: true,
                src: st.playing.src || st.playing.file.streamUrl
              });
              st.videoEl = v;
              v.addEventListener('error', () => {
                if (st.videoEl !== v) return;
                if (st.playing && st.playing.index === st.playing.file.id) {
                  const f = st.playing.file;
                  if (st.playing.live) {
                    // Live conversion failed (old ABI, unsupported video codec or no fragments).
                    // Fall back to the full-file MP4 conversion, which also handles AC-3.
                    beginPrepare(f);
                  } else if (!st.playing.remuxId) {
                    // Direct play failed: first retry through the live converter, so an MP4 with
                    // AC-3/DTS audio does not have to wait for the whole file.
                    startLive(f);
                  } else {
                    st.playError = 'O navegador não abriu este vídeo (codec não suportado). O torrent continua baixando normalmente.';
                    renderDetail();
                  }
                }
              });
              v.addEventListener('playing', () => {
                if (st.videoEl !== v) return;
                if (st.liveTimer) { clearTimeout(st.liveTimer); st.liveTimer = null; }
              });
              v.addEventListener('ended', () => {
                if (st.videoEl === v) discardCurrent(true);
              });
              setTimeout(() => { if (st.videoEl === v) v.play().catch(() => { /* autoplay blocked */ }); }, 80);
              return v;
            })(),
            el('div', { class: 'tp-player-bar' },
              el('span', { id: 'tp-playing-stat', class: 'hint', text: statusLabel(t) + ' · ' + fmtSize(t.done) + ' / ' + fmtSize(t.total) }),
              el('span', { class: 'tp-player-acts' },
                el('button', { class: 'btn', onclick: chooseAnother }, 'Escolher outro'),
                el('button', { class: 'btn', onclick: () => discardCurrent(false) }, 'Parar e apagar'),
                el('button', { class: 'btn', onclick: () => openExternal(st.playing.file) }, 'Abrir no VLC'),
                el('button', { class: 'btn', onclick: () => copyLink(st.playing.file) }, 'Copiar link'))))));
      }
    } else if (st.playError && st.startingFile) {
      detail.append(el('div', { class: 'tp-player' }, fallbackBox(st.startingFile)));
    } else {
      detail.append(el('div', { class: 'tp-empty' },
        el('div', { class: 'tp-empty-ico' }, icon('play')),
        el('h3', { text: 'Iniciando o player…' }),
        el('p', { class: 'hint', text: 'Preparando o stream e a lista de pedaços do vídeo.' })));
    }
  }

  function beginPrepare(f) {
    const id = st.id;
    if (!id) return;
    const remuxId = st.playing && st.playing.remuxId;
    stopPlayback();
    if (remuxId) removeRemux(remuxId);
    st.stage = 'player';
    st.starting = false;
    st.startingFile = f;
    st.playError = '';
    st.prep = { task: id, index: f.id, file: f, phase: 'download', progress: 0, jobId: null, error: '' };
    setAddStatus('Preparando o arquivo para tocar dentro do navegador…', false);
    renderDetail();
    pollPrep();
  }

  async function playFile(f) {
    if (!f || !f.streamUrl) {
      st.starting = false;
      st.playError = 'Link de stream indisponível para este arquivo.';
      if (st.stage === 'player') renderDetail();
      return toast('Link de stream indisponível para este arquivo.');
    }
    const id = st.id;
    endPlayback();
    st.stage = 'player';
    st.starting = true;
    st.startingFile = f;
    st.playError = '';
    renderDetail();
    try {
      await api('debrid/prioritize', null, { method: 'POST', body: JSON.stringify({ id, fileIndex: f.id, confirm: true }) });
      if (st.destroyed || st.id !== id) return;
      st.playError = '';
      // MKV/AVI/TS and AC-3 audio cannot go straight to Chrome. Try the live MP4 fragment
      // converter first; a browser error then falls back to the full-file remux.
      if (needsBrowserPrep(f)) {
        startLive(f);
        return;
      }
      st.starting = false;
      st.playing = { task: id, index: f.id, file: f };
      setAddStatus('Reproduzindo enquanto baixa. O player busca os pedaços conforme você assiste.', false);
      renderDetail();
    } catch (e) {
      st.starting = false;
      st.playError = 'Falha ao iniciar o streaming: ' + e.message;
      toast('Falha ao iniciar o streaming: ' + e.message, 5000);
      setAddStatus(st.playError, true);
      if (st.stage === 'player') renderDetail();
    }
  }

  async function pollPrep() {
    const p = st.prep;
    if (!p || st.destroyed) return;
    if (st.prepTimer) { clearTimeout(st.prepTimer); st.prepTimer = null; }
    try {
      if (p.phase === 'download') {
        const d = await api('debrid/files', { id: p.task });
        if (st.prep !== p) return;
        if (st.id === p.task) st.data = d;
        const file = (d.files || []).find(x => Number(x.id) === Number(p.index));
        if (!file) throw new Error('arquivo não encontrado no torrent');
        p.file = file;
        p.progress = Number(file.progress) || 0;
        const done = p.progress >= 99.9 || (Number(file.size) > 0 && Number(file.done) >= Number(file.size));
        if (done) {
          p.phase = 'remux';
          p.progress = 0;
          setAddStatus('Arquivo completo. Convertendo o container para MP4…', false);
          renderDetail();
          const job = await api('remux', null, { method: 'POST', body: JSON.stringify({ path: file.fullPath }) });
          if (st.prep !== p) return;
          p.jobId = job.id;
          p.progress = Number(job.progress) || 0;
          if (job.state === 'error') throw new Error(job.error || 'falha na conversão');
        }
      } else if (p.phase === 'remux') {
        if (!p.jobId) throw new Error('conversão não iniciou');
        const job = await api('remux/status', { id: p.jobId });
        if (st.prep !== p) return;
        p.progress = Number(job.progress) || 0;
        if (job.state === 'error' || job.state === 'cancelled') throw new Error(job.error || 'conversão cancelada');
        if (job.state === 'done') {
          const remuxId = p.jobId;
          st.prep = null;
          st.playing = {
            task: p.task, index: p.index, file: p.file, remuxId,
            src: '/api/remux/file' + qs({ id: remuxId, token: State.apiKey || null })
          };
          st.playError = '';
          setAddStatus('Pronto: convertido para MP4 e tocando dentro do navegador.', false);
          renderDetail();
          return;
        }
      }
      if (st.prep === p) renderDetail();
    } catch (e) {
      if (st.prep === p) {
        p.phase = 'error';
        p.error = 'Não foi possível preparar este vídeo: ' + e.message;
        renderDetail();
        setAddStatus(p.error, true);
      }
    } finally {
      if (st.prep === p && p.phase !== 'error') st.prepTimer = setTimeout(pollPrep, 1500);
    }
  }

  async function openExternal(f) {
    try {
      await api('player/open', { id: st.id, index: f.id });
      toast('Abrindo no player do celular… Se o VLC não estiver instalado, escolha outro player.', 6000);
    } catch (e) {
      toast('Não foi possível abrir o player: ' + e.message, 5000);
    }
  }

  function copyLink(f) {
    if (!f || !f.streamUrl) return;
    copyText(f.streamUrl, 'Link do stream copiado');
  }

  function setStartBusy(busy) {
    st.busy = busy;
    addBtn.disabled = busy;
    uploadBtn.disabled = busy;
    if (addBtn.lastChild && addBtn.lastChild.nodeType === 3) {
      addBtn.lastChild.nodeValue = busy ? ' Abrindo...' : ' Assistir';
    }
  }

  async function addMagnetTask(url, signal) {
    const r = await api('dl/add', null, { method: 'POST', body: JSON.stringify({ urls: url, paused: true, ephemeral: true }), signal });
    const id = r.ids && r.ids[0];
    if (!id) throw new Error('o servidor não retornou a tarefa');
    return id;
  }

  async function addTorrentTask(f, signal) {
    const headers = State.apiKey ? { 'X-Api-Key': State.apiKey } : undefined;
    const r = await fetch('/api/dl/torrent?paused=1&ephemeral=1', { method: 'POST', body: f, headers, credentials: 'same-origin', signal });
    const j = await r.json().catch(() => ({}));
    if (r.status === 401) {
      setApiKey('');
      Auth.show();
      throw new Error('sessão expirada');
    }
    if (!r.ok) throw new Error(j.error || ('HTTP ' + r.status));
    const id = j.ids && j.ids[0];
    if (!id) throw new Error('o servidor não retornou a tarefa');
    return id;
  }

  /** Confirms the single start form, creates the paused torrent and opens the file picker. */
  async function confirmStart() {
    if (st.busy) return;
    const url = input.value.trim();
    const file = st.torrentFile;
    if (!url && !file) return toast('Cole um magnet ou escolha um arquivo .torrent.');
    if (!file && !/^magnet:/i.test(url)) {
      return toast('Para o Torrent Player use um link magnet. Para links HTTP use o Download Station.', 6000);
    }

    setStartBusy(true);
    setAddStatus(file ? 'Enviando o .torrent e buscando metadados…' : 'Buscando metadados e lista de arquivos…', false);
    let controller = null;
    let timeout = null;
    try {
      await discardCurrent(true);
      controller = new AbortController();
      timeout = setTimeout(() => controller.abort(), 45000);
      const id = file ? await addTorrentTask(file, controller.signal) : await addMagnetTask(url, controller.signal);
      input.value = '';
      st.torrentFile = null;
      fileInput.value = '';
      updateFilePick();
      st.stage = 'pick';
      st.starting = false;
      st.startingFile = null;
      st.id = id;
      st.data = null;
      st.ephemeral = true;
      st.showAll = false;
      renderTasks();
      renderDetail();
      await loadTask(id);
      setAddStatus('Lista pronta. Escolha um dos arquivos para abrir o player.', false);
    } catch (e) {
      st.stage = 'start';
      renderDetail();
      setAddStatus(e && e.name === 'AbortError'
        ? 'O servidor demorou para responder. Nada foi baixado; tente de novo.'
        : 'Falha ao abrir o torrent: ' + e.message, true);
    } finally {
      if (timeout) clearTimeout(timeout);
      setStartBusy(false);
    }
  }

  const onUnload = () => {
    if (st.id && st.ephemeral) discardBeacon(st.id);
    if (st.playing && st.playing.remuxId) remuxBeacon(st.playing.remuxId);
    if (st.prep && st.prep.jobId) remuxBeacon(st.prep.jobId);
  };
  window.addEventListener('beforeunload', onUnload);

  win.setTitle('Torrent Player');
  renderTasks();
  renderDetail();
  refreshTasks();
  st.timer = setInterval(() => {
    if (st.destroyed || st.busy) return;
    refreshTasks();
  }, 3000);

  return {
    destroy() {
      st.destroyed = true;
      clearInterval(st.timer);
      window.removeEventListener('beforeunload', onUnload);
      if (st.playing && st.playing.remuxId) remuxBeacon(st.playing.remuxId);
      if (st.prep && st.prep.jobId) remuxBeacon(st.prep.jobId);
      stopPlayback();
      stopPrep(false);
      if (st.id && st.ephemeral) discardBeacon(st.id);
      st.id = null;
      st.data = null;
    },
    handle(arg) {
      if (!arg) return;
      if (arg.task) {
        selectTask(arg.task);
        return;
      }
      if (arg.magnet) {
        st.stage = 'start';
        st.torrentFile = null;
        input.value = arg.magnet;
        updateFilePick();
        confirmStart();
      }
    }
  };
}
