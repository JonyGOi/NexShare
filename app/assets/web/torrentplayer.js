/* Torrent Player: plays video straight from a torrent, downloading only the pieces needed. */
function TorrentPlayer(body, win) {
  const st = {
    tasks: [], id: null, data: null, loading: false, timer: null, busy: false,
    playing: null, videoEl: null, playError: '', showAll: false, destroyed: false, autoPicked: false
  };
  const pane = el('div', { class: 'pane tp-pane' });
  body.append(pane);

  const input = el('textarea', {
    class: 'tp-input',
    rows: 2,
    placeholder: 'Cole um link magnet (magnet:?xt=urn:btih:...)'
  });
  const fileInput = el('input', {
    type: 'file', accept: '.torrent,application/x-bittorrent', class: 'hidden',
    onchange: () => uploadTorrent()
  });
  const addBtn = el('button', { class: 'btn primary', onclick: () => addMagnet() }, icon('play'), ' Abrir torrent');
  const uploadBtn = el('button', { class: 'btn', onclick: () => fileInput.click() }, icon('download'), ' Arquivo .torrent');
  const addStatus = el('div', { class: 'hint tp-add-status', text: 'O vídeo começa assim que os primeiros pedaços chegam — não precisa baixar o torrent inteiro.' });
  const tasksBox = el('div', { class: 'tp-tasks' });
  const detail = el('div', { class: 'tp-detail' });

  pane.append(
    el('div', { class: 'card tp-add' },
      el('div', { class: 'tp-add-head' },
        el('div', null,
          el('h3', { style: { margin: '0 0 4px' }, text: 'Torrent Player' }),
          el('div', { class: 'hint', text: 'Assista enquanto baixa. Para MKV, HEVC, AVI e outros formatos use também “Abrir no VLC”.' })),
        el('span', { class: 'tp-live', text: 'streaming' })),
      input,
      el('div', { class: 'tp-add-row' }, addBtn, uploadBtn, fileInput, addStatus)),
    el('div', { class: 'tp-body' },
      el('div', { class: 'card tp-side' },
        el('div', { class: 'tp-side-head' },
          el('span', { text: 'Na biblioteca' }),
          el('span', { class: 'hint', id: 'tp-count', text: '0' })),
        tasksBox),
      el('div', { class: 'card tp-main' }, detail))
  );

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

  function renderTasks() {
    tasksBox.replaceChildren();
    const count = document.getElementById('tp-count');
    if (count) count.textContent = String(st.tasks.length);
    if (!st.tasks.length) {
      tasksBox.append(el('div', { class: 'tp-empty', text: 'Nenhum torrent ainda. Cole um magnet acima para começar.' }));
      return;
    }
    for (const t of st.tasks.slice(0, 100)) {
      const row = el('button', {
        class: 'tp-task' + (t.id === st.id ? ' active' : ''),
        onclick: () => selectTask(t.id)
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
    if (st.videoEl) {
      try { st.videoEl.pause(); st.videoEl.removeAttribute('src'); st.videoEl.load(); } catch (e) { /* ignore */ }
    }
    st.videoEl = null;
    st.playing = null;
    st.playError = '';
  }

  async function refreshTasks() {
    try {
      const r = await api('dl/list');
      if (st.destroyed) return;
      st.tasks = (r.tasks || []).filter(t => t && t.type === 'torrent');
      renderTasks();
      if (!st.id && !st.autoPicked && st.tasks.length) {
        st.autoPicked = true;
        await selectTask(st.tasks[0].id);
        return;
      }
      if (st.id) await loadTask(st.id);
    } catch (e) {
      if (!st.destroyed) setAddStatus('Não foi possível listar os torrents: ' + e.message, true);
    }
  }

  async function loadTask(id) {
    if (!id || st.loading || st.destroyed) return;
    st.loading = true;
    try {
      const d = await api('debrid/files', { id });
      if (st.destroyed || st.id !== id) return;
      st.data = d;
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

  async function selectTask(id) {
    stopPlayback();
    st.id = id;
    st.data = null;
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

  function fallbackBox(f) {
    return el('div', { class: 'tp-warn' },
      el('div', { text: st.playError }),
      el('div', { class: 'tp-warn-actions' },
        el('button', { class: 'btn primary', onclick: () => openExternal(f) }, icon('play'), ' Abrir no VLC do celular'),
        el('button', { class: 'btn', onclick: () => copyLink(f) }, icon('link'), ' Copiar link'),
        el('a', { class: 'btn', href: f.streamUrl + (f.streamUrl.indexOf('?') >= 0 ? '&' : '?') + 'dl=1', download: '' }, icon('download'), ' Baixar arquivo')),
      el('div', { class: 'hint', text: 'O VLC recebe o mesmo stream local e baixa os pedaços sob demanda; o torrent não precisa terminar antes.' }));
  }

  function renderDetail() {
    detail.replaceChildren();
    const t = st.data && st.data.task;
    if (!st.id) {
      detail.append(el('div', { class: 'tp-empty' },
        el('div', { class: 'tp-empty-ico' }, icon('play')),
        el('h3', { text: 'Escolha um torrent para assistir' }),
        el('p', { class: 'hint', text: 'Cole um magnet ou envie um .torrent. A lista de vídeos aparece assim que os metadados chegarem.' })));
      return;
    }
    if (!t) {
      detail.append(el('div', { class: 'tp-empty', text: 'Carregando metadados do torrent…' }));
      return;
    }
    const files = (st.data.files || []);
    const vids = files.filter(videoFile);
    const show = st.showAll || !vids.length ? files : vids;
    const pct = percentOf(t);

    detail.append(
      el('div', { class: 'tp-head' },
        el('div', { class: 'tp-head-main' },
          el('h3', { text: t.name || 'Torrent' }),
          el('div', { class: 'hint' }, statusLabel(t), ' · ', fmtSize(t.done), ' / ', fmtSize(t.total),
            t.speed > 0 ? ' · ' + fmtRate(t.speed) : '',
            t.eta > 0 ? ' · ETA ' + fmtDur(t.eta) : '')),
        el('span', { class: 'tp-state' + (t.status === 'error' ? ' err' : ''), text: statusLabel(t) })),
      bar(pct, 'big'),
      el('div', { class: 'tp-sub' },
        el('span', { text: Math.round(pct) + '% baixado' }),
        el('span', { text: (vids.length ? vids.length + ' vídeo(s)' : files.length + ' arquivo(s)') })),
      ...(st.playing ? [el('div', { class: 'tp-player' },
        st.playError ? fallbackBox(st.playing.file) :
          el('div', { class: 'tp-video-wrap' },
            (() => {
              const v = el('video', {
                class: 'tp-video', controls: true, autoplay: true, playsinline: true,
                src: st.playing.file.streamUrl
              });
              st.videoEl = v;
              v.addEventListener('error', () => {
                if (st.playing && st.playing.index === st.playing.file.id) {
                  st.playError = 'O navegador não abriu este formato (container/codec não suportado). O torrent continua baixando normalmente.';
                  renderDetail();
                }
              });
              setTimeout(() => { if (st.videoEl === v) v.play().catch(() => { /* autoplay blocked */ }); }, 80);
              return v;
            })(),
            el('div', { class: 'tp-player-bar' },
              el('span', { id: 'tp-playing-stat', class: 'hint', text: statusLabel(t) + ' · ' + fmtSize(t.done) + ' / ' + fmtSize(t.total) }),
              el('span', { class: 'tp-player-acts' },
                el('button', { class: 'btn', onclick: () => stopPlayback() || renderDetail() }, 'Parar'),
                el('button', { class: 'btn', onclick: () => openExternal(st.playing.file) }, 'Abrir no VLC'),
                el('button', { class: 'btn', onclick: () => copyLink(st.playing.file) }, 'Copiar link')))))] : []),
      el('div', { class: 'tp-files-head' },
        el('span', { text: 'Arquivos do torrent' }),
        ...(files.length > vids.length ? [el('button', { class: 'btn', onclick: () => { st.showAll = !st.showAll; renderDetail(); } }, st.showAll ? 'Só vídeos' : 'Mostrar todos')] : [])),
      ...(!files.length ? [el('div', { class: 'tp-empty', text: 'Buscando a lista de arquivos no enxame…' })] : []),
      el('div', { class: 'tp-files' }, ...show.map(f => fileRow(f)))
    );
  }

  function fileRow(f) {
    const name = baseName(f.path || f.name);
    const folder = dirName(f.path || f.name);
    const isPlaying = st.playing && st.playing.file && st.playing.file.id === f.id && !st.playError;
    return el('div', { class: 'tp-file' + (isPlaying ? ' playing' : '') },
      el('div', { class: 'tp-file-main' },
        el('div', { class: 'tp-file-name', text: name }),
        el('div', { class: 'hint', text: (folder ? folder + ' · ' : '') + fmtSize(f.size) + ' · ' + Math.round(Number(f.progress) || 0) + '%' })),
      el('div', { class: 'tp-file-acts' },
        el('button', { class: 'btn primary', onclick: () => playFile(f) }, icon('play'), ' Assistir'),
        el('button', { class: 'btn', onclick: () => openExternal(f), title: 'Abrir no VLC do celular' }, 'VLC')));
  }

  async function playFile(f) {
    if (!f || !f.streamUrl) return toast('Link de stream indisponível para este arquivo.');
    stopPlayback();
    try {
      await api('debrid/prioritize', null, { method: 'POST', body: JSON.stringify({ id: st.id, fileIndex: f.id }) });
      st.playing = { task: st.id, index: f.id, file: f };
      st.playError = '';
      setAddStatus('Reproduzindo enquanto baixa. O player busca os pedaços conforme você assiste.', false);
      renderDetail();
    } catch (e) {
      toast('Falha ao iniciar o streaming: ' + e.message, 5000);
      setAddStatus('Falha ao iniciar o streaming: ' + e.message, true);
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

  async function addMagnet() {
    const url = input.value.trim();
    if (!url) return toast('Cole um link magnet primeiro.');
    if (!/^magnet:/i.test(url)) return toast('Para o Torrent Player use um link magnet. Para links HTTP use o Download Station.', 6000);
    st.busy = true;
    addBtn.disabled = true;
    setAddStatus('Buscando metadados e lista de arquivos…', false);
    try {
      const r = await api('dl/add', null, { method: 'POST', body: JSON.stringify({ urls: url, paused: true }) });
      const id = r.ids && r.ids[0];
      if (!id) throw new Error('o servidor não retornou a tarefa');
      input.value = '';
      st.autoPicked = true;
      await refreshTasks();
      await selectTask(id);
      setAddStatus('Lista pronta. Clique em Assistir para começar a baixar e tocar.');
    } catch (e) {
      setAddStatus('Falha ao adicionar o magnet: ' + e.message, true);
    } finally {
      st.busy = false;
      addBtn.disabled = false;
    }
  }

  async function uploadTorrent() {
    const f = fileInput.files && fileInput.files[0];
    if (!f) return;
    st.busy = true;
    uploadBtn.disabled = true;
    setAddStatus('Enviando o .torrent e buscando metadados…', false);
    try {
      const r = await fetch('/api/dl/torrent?paused=1', { method: 'POST', body: f, credentials: 'same-origin' });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(j.error || ('HTTP ' + r.status));
      const id = j.ids && j.ids[0];
      if (!id) throw new Error('o servidor não retornou a tarefa');
      st.autoPicked = true;
      await refreshTasks();
      await selectTask(id);
      setAddStatus('Lista pronta. Clique em Assistir para começar a baixar e tocar.');
    } catch (e) {
      setAddStatus('Falha ao enviar o .torrent: ' + e.message, true);
    } finally {
      st.busy = false;
      uploadBtn.disabled = false;
      fileInput.value = '';
    }
  }

  win.setTitle('Torrent Player');
  renderTasks();
  renderDetail();
  refreshTasks();
  st.timer = setInterval(() => {
    if (st.destroyed || st.busy) return;
    refreshTasks();
  }, 1800);

  return {
    destroy() {
      st.destroyed = true;
      clearInterval(st.timer);
      stopPlayback();
      if (st.data) st.data = null;
    },
    handle(arg) {
      if (!arg) return;
      if (arg.task) selectTask(arg.task);
      if (arg.magnet) {
        input.value = arg.magnet;
        addMagnet();
      }
    }
  };
}
