# NexShare Debrid (Windows) - usa o celular como servidor de debrid e baixa direto no PC.
#
# Requer apenas o PowerShell 5.1 (já vem no Windows 10/11). Nada para instalar.
# Abra pelo NexShareDebrid.cmd ou: powershell -ExecutionPolicy Bypass -File NexShareDebrid.ps1
#
# A "API key" aparece no celular em: Debrid Hub -> aba "Usar no VLC / Stremio / Windows".
# O servidor pode ser o IP da rede (http://192.168.1.50:8080) ou a URL pública do túnel.

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()
[System.Windows.Forms.Application]::SetCompatibleTextRenderingDefault($false)

$ErrorActionPreference = 'Stop'

$script:AppDir = Join-Path $env:APPDATA 'NexShareDebrid'
New-Item -ItemType Directory -Force -Path $script:AppDir | Out-Null
$script:ConfigPath = Join-Path $script:AppDir 'config.json'
$script:LogPath = Join-Path $script:AppDir 'error.log'

trap {
    $msg = "Erro: $($_.Exception.Message)`n`n$($_.ScriptStackTrace)"
    try { Add-Content -Path $script:LogPath -Value ("[{0}] {1}" -f (Get-Date), $msg) } catch { }
    [System.Windows.Forms.MessageBox]::Show($msg, 'NexShare Debrid', 'OK', 'Error') | Out-Null
    exit 1
}

$script:Cfg = @{
    url         = 'http://nexshare.local:8080'
    key         = ''
    downloadDir = (Join-Path $env:USERPROFILE 'Downloads\NexShare')
}
if (Test-Path $script:ConfigPath) {
    try {
        $saved = Get-Content -Raw -Path $script:ConfigPath | ConvertFrom-Json
        if ($saved.url) { $script:Cfg.url = $saved.url }
        if ($saved.key) { $script:Cfg.key = $saved.key }
        if ($saved.downloadDir) { $script:Cfg.downloadDir = $saved.downloadDir }
    } catch { }
}

function Save-Config {
    $script:Cfg | ConvertTo-Json | Set-Content -Path $script:ConfigPath -Encoding UTF8
}

function Format-Size([double]$bytes) {
    if ($null -eq $bytes -or $bytes -lt 0) { return '-' }
    $units = 'B', 'KB', 'MB', 'GB', 'TB'
    $i = 0
    while ($bytes -ge 1024 -and $i -lt 4) { $bytes /= 1024; $i++ }
    if ($i -eq 0) { return ('{0} B' -f [int]$bytes) }
    return ('{0:N1} {1}' -f $bytes, $units[$i])
}

function Format-Rate([double]$bytes) {
    if ($bytes -le 0) { return '-' }
    return (Format-Size $bytes) + '/s'
}

# ---------------------------------------------------------------- API

function Api {
    param(
        [string]$Method = 'GET',
        [string]$Path,
        [hashtable]$Query,
        $Body,
        [string]$ContentType
    )
    $base = $script:Cfg.url.Trim().TrimEnd('/')
    if (-not $base) { throw 'Informe o servidor (ex.: http://192.168.1.50:8080).' }
    if (-not $script:Cfg.key) { throw 'Informe a API key (Debrid Hub -> Usar no VLC / Stremio / Windows).' }
    $url = "$base/rest/1.0/" + $Path.TrimStart('/')
    if ($Query -and $Query.Count -gt 0) {
        $pairs = foreach ($k in $Query.Keys) {
            '{0}={1}' -f $k, [uri]::EscapeDataString([string]$Query[$k])
        }
        $url += '?' + ($pairs -join '&')
    }
    $params = @{
        Uri        = $url
        Method     = $Method
        Headers    = @{ Authorization = "Bearer $($script:Cfg.key)" }
        TimeoutSec = 30
    }
    if ($null -ne $Body) {
        $params.Body = $Body
        if ($ContentType) { $params.ContentType = $ContentType }
    }
    return Invoke-RestMethod @params
}

# ---------------------------------------------------------------- UI

$form = New-Object System.Windows.Forms.Form
$form.Text = 'NexShare Debrid'
$form.Size = New-Object System.Drawing.Size(1060, 720)
$form.MinimumSize = New-Object System.Drawing.Size(900, 560)
$form.StartPosition = 'CenterScreen'
$form.BackColor = [System.Drawing.Color]::FromArgb(244, 247, 251)
$form.Font = New-Object System.Drawing.Font('Segoe UI', 9)

$split = New-Object System.Windows.Forms.SplitContainer
$split.Dock = 'Fill'
$split.Orientation = 'Horizontal'
$split.SplitterDistance = 330
$split.Panel1.Padding = New-Object System.Windows.Forms.Padding(12, 8, 12, 4)
$split.Panel2.Padding = New-Object System.Windows.Forms.Padding(12, 4, 12, 10)
$form.Controls.Add($split)

$top = New-Object System.Windows.Forms.Panel
$top.Dock = 'Top'
$top.Height = 112
$top.BackColor = [System.Drawing.Color]::White
$form.Controls.Add($top)

$status = New-Object System.Windows.Forms.StatusStrip
$lblStatus = New-Object System.Windows.Forms.ToolStripStatusLabel
$lblStatus.Text = 'Pronto'
$lblStatus.Spring = $true
$lblStatus.TextAlign = 'MiddleLeft'
$prog = New-Object System.Windows.Forms.ToolStripProgressBar
$prog.Width = 230
$prog.Visible = $false
[void]$status.Items.Add($lblStatus)
[void]$status.Items.Add($prog)
$form.Controls.Add($status)

$lblServer = New-Object System.Windows.Forms.Label
$lblServer.Text = 'Servidor'
$lblServer.AutoSize = $true
$lblServer.Location = New-Object System.Drawing.Point(14, 18)
$top.Controls.Add($lblServer)

$txtUrl = New-Object System.Windows.Forms.TextBox
$txtUrl.Location = New-Object System.Drawing.Point(70, 15)
$txtUrl.Size = New-Object System.Drawing.Size(250, 24)
$txtUrl.Text = $script:Cfg.url
$top.Controls.Add($txtUrl)

$lblKey = New-Object System.Windows.Forms.Label
$lblKey.Text = 'API key'
$lblKey.AutoSize = $true
$lblKey.Location = New-Object System.Drawing.Point(334, 18)
$top.Controls.Add($lblKey)

$txtKey = New-Object System.Windows.Forms.TextBox
$txtKey.Location = New-Object System.Drawing.Point(386, 15)
$txtKey.Size = New-Object System.Drawing.Size(292, 24)
$txtKey.UseSystemPasswordChar = $true
$txtKey.Text = $script:Cfg.key
$top.Controls.Add($txtKey)

$btnShowKey = New-Object System.Windows.Forms.Button
$btnShowKey.Text = 'Mostrar'
$btnShowKey.Location = New-Object System.Drawing.Point(684, 14)
$btnShowKey.Size = New-Object System.Drawing.Size(64, 26)
$btnShowKey.FlatStyle = 'Flat'
$btnShowKey.Add_Click({
        $txtKey.UseSystemPasswordChar = -not $txtKey.UseSystemPasswordChar
        $btnShowKey.Text = if ($txtKey.UseSystemPasswordChar) { 'Mostrar' } else { 'Ocultar' }
    })
$top.Controls.Add($btnShowKey)

$btnConnect = New-Object System.Windows.Forms.Button
$btnConnect.Text = 'Conectar'
$btnConnect.Location = New-Object System.Drawing.Point(756, 13)
$btnConnect.Size = New-Object System.Drawing.Size(96, 28)
$btnConnect.BackColor = [System.Drawing.Color]::FromArgb(30, 111, 217)
$btnConnect.ForeColor = [System.Drawing.Color]::White
$btnConnect.FlatStyle = 'Flat'
$top.Controls.Add($btnConnect)

$lblConn = New-Object System.Windows.Forms.Label
$lblConn.Location = New-Object System.Drawing.Point(862, 19)
$lblConn.Size = New-Object System.Drawing.Size(170, 18)
$lblConn.Text = 'desconectado'
$lblConn.ForeColor = [System.Drawing.Color]::FromArgb(110, 120, 135)
$top.Controls.Add($lblConn)

$txtMagnet = New-Object System.Windows.Forms.TextBox
$txtMagnet.Location = New-Object System.Drawing.Point(14, 56)
$txtMagnet.Size = New-Object System.Drawing.Size(540, 24)
$top.Controls.Add($txtMagnet)

$btnPaste = New-Object System.Windows.Forms.Button
$btnPaste.Text = 'Colar'
$btnPaste.Location = New-Object System.Drawing.Point(562, 55)
$btnPaste.Size = New-Object System.Drawing.Size(60, 26)
$btnPaste.FlatStyle = 'Flat'
$top.Controls.Add($btnPaste)

$btnAdd = New-Object System.Windows.Forms.Button
$btnAdd.Text = 'Adicionar magnet'
$btnAdd.Location = New-Object System.Drawing.Point(630, 54)
$btnAdd.Size = New-Object System.Drawing.Size(126, 27)
$btnAdd.FlatStyle = 'Flat'
$top.Controls.Add($btnAdd)

$btnTorrentFile = New-Object System.Windows.Forms.Button
$btnTorrentFile.Text = 'Abrir .torrent...'
$btnTorrentFile.Location = New-Object System.Drawing.Point(764, 54)
$btnTorrentFile.Size = New-Object System.Drawing.Size(118, 27)
$btnTorrentFile.FlatStyle = 'Flat'
$top.Controls.Add($btnTorrentFile)

$btnChooseDir = New-Object System.Windows.Forms.Button
$btnChooseDir.Text = 'Pasta...'
$btnChooseDir.Location = New-Object System.Drawing.Point(890, 54)
$btnChooseDir.Size = New-Object System.Drawing.Size(80, 27)
$btnChooseDir.FlatStyle = 'Flat'
$top.Controls.Add($btnChooseDir)

$lblDownloadDir = New-Object System.Windows.Forms.Label
$lblDownloadDir.AutoSize = $true
$lblDownloadDir.Text = 'Baixar em: ' + $script:Cfg.downloadDir
$lblDownloadDir.Location = New-Object System.Drawing.Point(14, 88)
$lblDownloadDir.ForeColor = [System.Drawing.Color]::FromArgb(110, 120, 135)
$top.Controls.Add($lblDownloadDir)

$btnOpenFolderTop = New-Object System.Windows.Forms.Button
$btnOpenFolderTop.Text = 'Abrir pasta'
$btnOpenFolderTop.Location = New-Object System.Drawing.Point(890, 83)
$btnOpenFolderTop.Size = New-Object System.Drawing.Size(100, 24)
$btnOpenFolderTop.FlatStyle = 'Flat'
$top.Controls.Add($btnOpenFolderTop)

# --- torrents grid
$dg = New-Object System.Windows.Forms.DataGridView
$dg.Dock = 'Fill'
$dg.AllowUserToAddRows = $false
$dg.AllowUserToDeleteRows = $false
$dg.ReadOnly = $true
$dg.SelectionMode = 'FullRowSelect'
$dg.MultiSelect = $false
$dg.RowHeadersVisible = $false
$dg.AutoSizeColumnsMode = 'Fill'
$dg.BackgroundColor = [System.Drawing.Color]::White
$dg.BorderStyle = 'None'
$dg.GridColor = [System.Drawing.Color]::FromArgb(230, 235, 242)
$dg.ColumnHeadersDefaultCellStyle.Font = New-Object System.Drawing.Font('Segoe UI', 9, [System.Drawing.FontStyle]::Bold)
foreach ($c in @(
        @{ n = 'Nome'; w = 50 },
        @{ n = 'Tamanho'; w = 11 },
        @{ n = 'Progresso'; w = 10 },
        @{ n = 'Velocidade'; w = 11 },
        @{ n = 'Seeders'; w = 8 },
        @{ n = 'Status'; w = 10 }
    )) {
    $col = New-Object System.Windows.Forms.DataGridViewTextBoxColumn
    $col.Name = $c.n
    $col.HeaderText = $c.n
    $col.FillWeight = $c.w
    [void]$dg.Columns.Add($col)
}
$split.Panel1.Controls.Add($dg)

$barTorrents = New-Object System.Windows.Forms.FlowLayoutPanel
$barTorrents.Dock = 'Bottom'
$barTorrents.Height = 42
$barTorrents.Padding = New-Object System.Windows.Forms.Padding(0, 6, 0, 0)
$split.Panel1.Controls.Add($barTorrents)

function New-SmallButton([string]$text, [scriptblock]$onClick) {
    $b = New-Object System.Windows.Forms.Button
    $b.Text = $text
    $b.AutoSize = $true
    $b.Height = 27
    $b.FlatStyle = 'Flat'
    $b.Margin = New-Object System.Windows.Forms.Padding(0, 0, 8, 0)
    $b.Add_Click($onClick)
    return $b
}

$btnRefresh = New-SmallButton 'Atualizar' { Refresh-Torrents }
$btnPause = New-SmallButton 'Pausar' { Torrent-Cmd 'pause' }
$btnResume = New-SmallButton 'Retomar' { Torrent-Cmd 'resume' }
$btnRemove = New-SmallButton 'Remover' { Remove-Torrent }
$barTorrents.Controls.AddRange(@($btnRefresh, $btnPause, $btnResume, $btnRemove))

# --- files list
$lvFiles = New-Object System.Windows.Forms.ListView
$lvFiles.Dock = 'Fill'
$lvFiles.View = 'Details'
$lvFiles.FullRowSelect = $true
$lvFiles.MultiSelect = $false
$lvFiles.HideSelection = $false
[void]$lvFiles.Columns.Add('Arquivo', 540)
[void]$lvFiles.Columns.Add('Tamanho', 100)
[void]$lvFiles.Columns.Add('Baixado', 110)
[void]$lvFiles.Columns.Add('Status', 140)
$split.Panel2.Controls.Add($lvFiles)

$barFiles = New-Object System.Windows.Forms.FlowLayoutPanel
$barFiles.Dock = 'Bottom'
$barFiles.Height = 42
$barFiles.Padding = New-Object System.Windows.Forms.Padding(0, 6, 0, 0)
$split.Panel2.Controls.Add($barFiles)

$btnDownload = New-SmallButton 'Baixar para o PC' { Start-Download }
$btnCopyLink = New-SmallButton 'Copiar link' { Copy-Link }
$btnVlc = New-SmallButton 'Abrir no VLC' { Open-Vlc }
$btnOpenFolder = New-SmallButton 'Abrir pasta' { Open-Folder }
$barFiles.Controls.AddRange(@($btnDownload, $btnCopyLink, $btnVlc, $btnOpenFolder))

# ---------------------------------------------------------------- estado

$script:Torrents = @()
$script:Current = $null
$script:Files = @()
$script:DownloadJob = $null
$script:DownloadState = $null
$script:DownloadProgress = $null
$script:DownloadCancel = $null
$script:DownloadName = ''
$script:Refreshing = $false
$script:Ticks = 0
$script:SelectId = $null

function Set-Status([string]$text, [string]$kind = 'info') {
    $lblStatus.Text = $text
    switch ($kind) {
        'error' { $lblStatus.ForeColor = [System.Drawing.Color]::FromArgb(200, 55, 55) }
        'ok' { $lblStatus.ForeColor = [System.Drawing.Color]::FromArgb(20, 135, 70) }
        default { $lblStatus.ForeColor = [System.Drawing.Color]::FromArgb(60, 70, 85) }
    }
}

function Connect-Server {
    $script:Cfg.url = $txtUrl.Text.Trim()
    $script:Cfg.key = $txtKey.Text.Trim()
    Save-Config
    try {
        Set-Status 'Conectando...'
        $user = Api -Path 'user'
        $name = if ($user.username) { $user.username } else { 'nexshare' }
        $lblConn.Text = "conectado · $name"
        $lblConn.ForeColor = [System.Drawing.Color]::FromArgb(20, 135, 70)
        Set-Status "Conectado em $($script:Cfg.url)" 'ok'
        Refresh-Torrents
    } catch {
        $lblConn.Text = 'falha na conexão'
        $lblConn.ForeColor = [System.Drawing.Color]::FromArgb(200, 55, 55)
        Set-Status "Falha: $($_.Exception.Message)" 'error'
    }
}

function Select-TorrentId([string]$id) {
    foreach ($row in $dg.Rows) {
        if ($row.Tag -and [string]$row.Tag.id -eq [string]$id) {
            $dg.CurrentCell = $row.Cells[0]
            $row.Selected = $true
            Load-Files $row.Tag
            return
        }
    }
}

function Refresh-Torrents {
    if (-not $script:Cfg.key) { return }
    if ($script:Refreshing) { return }
    $script:Refreshing = $true
    try {
        $list = @(Api -Path 'torrents')
        $script:Torrents = $list
        $keepId = if ($script:SelectId) { $script:SelectId } elseif ($script:Current) { $script:Current.id } else { $null }
        $script:SelectId = $null
        $dg.Rows.Clear()
        foreach ($t in $list) {
            $pct = 0
            if ($t.bytes -gt 0) { $pct = [math]::Round([double]$t.progress, 0) }
            $idx = $dg.Rows.Add($t.filename, (Format-Size $t.bytes), ("{0}%" -f $pct), (Format-Rate $t.speed), $t.seeders, $t.status)
            $dg.Rows[$idx].Tag = $t
        }
        if ($keepId) { Select-TorrentId $keepId }
        if (-not $dg.Rows.Count) { Set-Status 'Nenhum torrent ainda. Cole um magnet acima.' }
    } catch {
        Set-Status "Erro ao listar: $($_.Exception.Message)" 'error'
    } finally {
        $script:Refreshing = $false
    }
}

function Load-Files($torrent, [int]$keepIndex = -1) {
    if (-not $torrent) { return }
    $script:Current = $torrent
    try {
        $info = Api -Path ("torrents/info/" + $torrent.id)
        $script:Files = @($info.files)
        $lvFiles.BeginUpdate()
        $lvFiles.Items.Clear()
        foreach ($f in $script:Files) {
            $name = [string]$f.path
            if ($name.StartsWith('/')) { $name = $name.Substring(1) }
            $st = 'pronto'
            if ([double]$f.progress -lt 99.9) { $st = 'aguardando download' }
            $item = New-Object System.Windows.Forms.ListViewItem($name)
            [void]$item.SubItems.Add((Format-Size $f.bytes))
            [void]$item.SubItems.Add((Format-Size $f.done))
            [void]$item.SubItems.Add($st)
            $item.Tag = $f
            [void]$lvFiles.Items.Add($item)
        }
        $lvFiles.EndUpdate()
        if ($keepIndex -ge 0 -and $keepIndex -lt $lvFiles.Items.Count) {
            $lvFiles.Items[$keepIndex].Selected = $true
            $lvFiles.Items[$keepIndex].Focused = $true
        }
        Set-Status ("{0} arquivo(s) no torrent" -f $script:Files.Count)
    } catch {
        Set-Status "Erro ao abrir torrent: $($_.Exception.Message)" 'error'
    }
}

function Add-Magnet([string]$magnet) {
    $magnet = $magnet.Trim()
    if (-not $magnet) { Set-Status 'Cole um magnet link ou abra um arquivo .torrent' 'error'; return }
    try {
        Set-Status 'Adicionando torrent...'
        $body = 'magnet=' + [uri]::EscapeDataString($magnet)
        $r = Api -Method 'POST' -Path 'torrents/addMagnet' -Body $body -ContentType 'application/x-www-form-urlencoded'
        $txtMagnet.Text = ''
        $script:SelectId = [string]$r.id
        Set-Status "Torrent adicionado ($($r.id)) — buscando metadados" 'ok'
        Start-Sleep -Milliseconds 700
        Refresh-Torrents
    } catch {
        Set-Status "Falha ao adicionar: $($_.Exception.Message)" 'error'
    }
}

function Torrent-Cmd([string]$what) {
    if (-not $script:Current) { Set-Status 'Selecione um torrent' 'error'; return }
    try {
        Api -Method 'POST' -Path ("torrents/$what/" + $script:Current.id) | Out-Null
        Set-Status ($(if ($what -eq 'pause') { 'Download pausado' } else { 'Download retomado' })) 'ok'
        Refresh-Torrents
    } catch {
        Set-Status "Falha: $($_.Exception.Message)" 'error'
    }
}

function Remove-Torrent {
    if (-not $script:Current) { Set-Status 'Selecione um torrent' 'error'; return }
    $t = $script:Current
    $answer = [System.Windows.Forms.MessageBox]::Show(
        "Remover o torrent:`n`n$($t.filename)`n`nSim = remover e APAGAR os arquivos do celular.`nNão = remover só o torrent.`nCancelar = não fazer nada.",
        'Remover torrent',
        [System.Windows.Forms.MessageBoxButtons]::YesNoCancel,
        [System.Windows.Forms.MessageBoxIcon]::Warning)
    if ($answer -eq 'Cancel') { return }
    try {
        $q = @{}
        if ($answer -eq 'Yes') { $q['files'] = '1' }
        Api -Method 'POST' -Path ("torrents/delete/" + $t.id) -Query $q | Out-Null
        $script:Current = $null
        $script:Files = @()
        $lvFiles.Items.Clear()
        Set-Status ($(if ($answer -eq 'Yes') { 'Torrent e arquivos removidos' } else { 'Torrent removido' })) 'ok'
        Refresh-Torrents
    } catch {
        Set-Status "Falha ao remover: $($_.Exception.Message)" 'error'
    }
}

function Get-FileLink($file) {
    return Api -Method 'POST' -Path 'unrestrict/link' -Query @{ id = $script:Current.id; file = ([int]$file.id - 1) }
}

function Copy-Link {
    if (-not $script:Current -or $lvFiles.SelectedItems.Count -eq 0) { Set-Status 'Selecione um arquivo' 'error'; return }
    try {
        $info = Get-FileLink $lvFiles.SelectedItems[0].Tag
        [System.Windows.Forms.Clipboard]::SetText($info.link)
        Set-Status 'Link copiado (cole no VLC ou no navegador)' 'ok'
    } catch {
        Set-Status "Falha: $($_.Exception.Message)" 'error'
    }
}

function Open-Vlc {
    if (-not $script:Current -or $lvFiles.SelectedItems.Count -eq 0) { Set-Status 'Selecione um arquivo' 'error'; return }
    try {
        $info = Get-FileLink $lvFiles.SelectedItems[0].Tag
        $vlc = @(
            "$env:ProgramFiles\VideoLAN\VLC\vlc.exe",
            "${env:ProgramFiles(x86)}\VideoLAN\VLC\vlc.exe"
        ) | Where-Object { Test-Path $_ } | Select-Object -First 1
        if ($vlc) { Start-Process -FilePath $vlc -ArgumentList $info.link }
        else {
            [System.Windows.Forms.Clipboard]::SetText($info.link)
            Set-Status 'VLC não encontrado — link copiado' 'error'
        }
    } catch {
        Set-Status "Falha: $($_.Exception.Message)" 'error'
    }
}

function Open-Folder {
    $dir = $script:Cfg.downloadDir
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    Start-Process explorer.exe $dir
}

# ---------------------------------------------------------------- download

function Start-Download {
    if ($script:DownloadJob) { Set-Status 'Já existe um download em andamento' 'error'; return }
    if (-not $script:Current -or $lvFiles.SelectedItems.Count -eq 0) { Set-Status 'Selecione um arquivo' 'error'; return }
    try {
        $file = $lvFiles.SelectedItems[0].Tag
        $info = Get-FileLink $file
        if (-not $info.download) { throw 'o servidor não devolveu um link de download' }

        $dir = $script:Cfg.downloadDir
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        $name = $info.filename
        if (-not $name) { $name = Split-Path ([string]$file.path) -Leaf }
        foreach ($c in [System.IO.Path]::GetInvalidFileNameChars()) { $name = $name.Replace([string]$c, '_') }
        $dest = Join-Path $dir $name

        $script:DownloadState = Join-Path $script:AppDir 'dl-state.txt'
        $script:DownloadProgress = Join-Path $script:AppDir 'dl-progress.txt'
        $script:DownloadCancel = Join-Path $script:AppDir 'dl-cancel.txt'
        Remove-Item $script:DownloadState, $script:DownloadProgress, $script:DownloadCancel -ErrorAction SilentlyContinue
        'running' | Set-Content -Path $script:DownloadState -Encoding ASCII
        ("0|{0}" -f $file.bytes) | Set-Content -Path $script:DownloadProgress -Encoding ASCII

        $script:DownloadName = $name
        $script:DownloadJob = Start-Job -ArgumentList $info.download, $dest, $script:DownloadProgress, $script:DownloadState, $script:DownloadCancel -ScriptBlock {
            param($url, $dest, $progFile, $stateFile, $cancelFile)
            $tmp = "$dest.part"
            try {
                Add-Type -AssemblyName System.Net.Http
                $client = New-Object System.Net.Http.HttpClient
                $client.Timeout = [TimeSpan]::FromMinutes(60)
                $resp = $client.GetAsync($url, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
                $resp.EnsureSuccessStatusCode() | Out-Null
                $total = [int64]0
                if ($resp.Content.Headers.ContentLength) { $total = [int64]$resp.Content.Headers.ContentLength }
                $in = $resp.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
                $out = [System.IO.File]::Create($tmp)
                $buf = New-Object byte[] 1048576
                $done = [int64]0
                $last = [int64]0
                while (($n = $in.Read($buf, 0, $buf.Length)) -gt 0) {
                    $out.Write($buf, 0, $n)
                    $done += $n
                    if (($done - $last) -ge 1048576) {
                        "$done|$total" | Set-Content -Path $progFile -Encoding ASCII
                        $last = $done
                    }
                    if (Test-Path $cancelFile) { break }
                }
                $out.Close(); $in.Close(); $client.Dispose()
                if (Test-Path $cancelFile) {
                    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
                    'cancel' | Set-Content -Path $stateFile -Encoding ASCII
                } else {
                    Move-Item -Force $tmp $dest
                    "done|$done|$total" | Set-Content -Path $stateFile -Encoding ASCII
                }
            } catch {
                'error|' + $_.Exception.Message | Set-Content -Path $stateFile -Encoding ASCII
            }
        }

        $btnDownload.Enabled = $false
        $prog.Visible = $true
        $prog.Value = 0
        Set-Status "Baixando $name ..."
    } catch {
        Set-Status "Falha ao baixar: $($_.Exception.Message)" 'error'
    }
}

function Poll-Download {
    if (-not $script:DownloadJob) { return }
    try {
        if (Test-Path $script:DownloadProgress) {
            $line = Get-Content -Raw -Path $script:DownloadProgress -ErrorAction SilentlyContinue
            if ($line) {
                $parts = $line.Trim().Split('|')
                if ($parts.Count -ge 2) {
                    $done = [double]$parts[0]
                    $total = [double]$parts[1]
                    if ($total -gt 0) {
                        $pct = [int][math]::Min(100, [math]::Round(($done * 100) / $total))
                        $prog.Value = $pct
                        Set-Status ("Baixando {0}: {1} de {2} ({3}%)" -f $script:DownloadName, (Format-Size $done), (Format-Size $total), $pct)
                    } else {
                        Set-Status ("Baixando {0}: {1}" -f $script:DownloadName, (Format-Size $done))
                    }
                }
            }
        }
        if (Test-Path $script:DownloadState) {
            $state = (Get-Content -Raw -Path $script:DownloadState).Trim()
            if ($state.StartsWith('done')) { Complete-Download 'done', $state }
            elseif ($state.StartsWith('error')) { Complete-Download 'error', $state }
            elseif ($state -eq 'cancel' -or $state -eq 'stop') { Complete-Download 'cancel', $state }
        }
    } catch { }
}

function Complete-Download([string]$result, [string]$raw) {
    try {
        if ($script:DownloadJob) {
            Wait-Job $script:DownloadJob -Timeout 3 | Out-Null
            Remove-Job $script:DownloadJob -Force -ErrorAction SilentlyContinue
        }
    } catch { }
    $script:DownloadJob = $null
    $btnDownload.Enabled = $true
    $prog.Visible = $false
    if ($result -eq 'done') {
        Set-Status ("Download concluído: {0} ({1})" -f $script:DownloadName, $script:Cfg.downloadDir) 'ok'
        if ([System.Windows.Forms.MessageBox]::Show("Download concluído:`n$($script:DownloadName)`n`nAbrir a pasta?", 'NexShare Debrid', 'YesNo', 'Information') -eq 'Yes') {
            Open-Folder
        }
    } elseif ($result -eq 'cancel') {
        Set-Status 'Download cancelado'
    } else {
        $msg = $raw -replace '^error\|', ''
        Set-Status "Falha no download: $msg" 'error'
        [System.Windows.Forms.MessageBox]::Show("O download falhou:`n$msg", 'NexShare Debrid', 'OK', 'Error') | Out-Null
    }
    Remove-Item $script:DownloadState, $script:DownloadProgress, $script:DownloadCancel -ErrorAction SilentlyContinue
    if ($script:Current) { Load-Files $script:Current }
}

# ---------------------------------------------------------------- eventos

$btnConnect.Add_Click({ Connect-Server })
$txtUrl.Add_KeyDown({ if ($_.KeyCode -eq 'Enter') { Connect-Server } })
$txtKey.Add_KeyDown({ if ($_.KeyCode -eq 'Enter') { Connect-Server } })

$btnPaste.Add_Click({
        $clip = Get-Clipboard -Format Text -ErrorAction SilentlyContinue
        if ($clip) { $txtMagnet.Text = $clip.Trim(); $txtMagnet.Focus() }
    })
$btnAdd.Add_Click({ Add-Magnet $txtMagnet.Text })
$txtMagnet.Add_KeyDown({ if ($_.KeyCode -eq 'Enter') { Add-Magnet $txtMagnet.Text } })

$btnTorrentFile.Add_Click({
        $ofd = New-Object System.Windows.Forms.OpenFileDialog
        $ofd.Filter = 'Torrent (*.torrent)|*.torrent|Todos os arquivos (*.*)|*.*'
        if ($ofd.ShowDialog() -eq 'OK') {
            try {
                $bytes = [System.IO.File]::ReadAllBytes($ofd.FileName)
                Set-Status 'Enviando .torrent...'
                $r = Api -Method 'POST' -Path 'torrents/addTorrent' -Body $bytes -ContentType 'application/x-bittorrent'
                $script:SelectId = [string]$r.id
                Set-Status "Torrent adicionado ($($r.id))" 'ok'
                Start-Sleep -Milliseconds 700
                Refresh-Torrents
            } catch {
                Set-Status "Falha ao enviar .torrent: $($_.Exception.Message)" 'error'
            }
        }
    })

$btnChooseDir.Add_Click({
        $fbd = New-Object System.Windows.Forms.FolderBrowserDialog
        $fbd.SelectedPath = $script:Cfg.downloadDir
        if ($fbd.ShowDialog() -eq 'OK') {
            $script:Cfg.downloadDir = $fbd.SelectedPath
            $lblDownloadDir.Text = 'Baixar em: ' + $script:Cfg.downloadDir
            Save-Config
        }
    })

$btnOpenFolderTop.Add_Click({ Open-Folder })
$btnOpenFolder.Add_Click({ Open-Folder })
$btnDownload.Add_Click({ Start-Download })
$btnCopyLink.Add_Click({ Copy-Link })
$btnVlc.Add_Click({ Open-Vlc })

$dg.Add_SelectionChanged({
        if ($script:Refreshing) { return }
        if ($dg.CurrentRow -and $dg.CurrentRow.Tag -and (-not $script:Current -or $script:Current.id -ne $dg.CurrentRow.Tag.id)) {
            Load-Files $dg.CurrentRow.Tag
        }
    })

$lvFiles.Add_DoubleClick({ Start-Download })

$form.Add_FormClosing({
        if ($script:DownloadJob) {
            'stop' | Set-Content -Path $script:DownloadCancel -Encoding ASCII -ErrorAction SilentlyContinue
            Stop-Job $script:DownloadJob -ErrorAction SilentlyContinue
            Remove-Job $script:DownloadJob -Force -ErrorAction SilentlyContinue
        }
        Save-Config
    })

$timerTorrents = New-Object System.Windows.Forms.Timer
$timerTorrents.Interval = 3000
$timerTorrents.Add_Tick({
        if (-not $form.Visible) { return }
        if (-not $script:Cfg.key) { return }
        Refresh-Torrents
        $script:Ticks++
        if ($script:Current -and ($script:Ticks % 2) -eq 0) {
            $sel = -1
            if ($lvFiles.SelectedIndices.Count -gt 0) { $sel = $lvFiles.SelectedIndices[0] }
            Load-Files $script:Current $sel
        }
    })
$timerTorrents.Start()

$timerDownload = New-Object System.Windows.Forms.Timer
$timerDownload.Interval = 700
$timerDownload.Add_Tick({ Poll-Download })
$timerDownload.Start()

$form.Add_Shown({
        try {
            $cue = Add-Type -MemberDefinition '[DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern IntPtr SendMessage(IntPtr hWnd, int msg, IntPtr wParam, string lParam);' -Name Cue -Namespace NexShare -PassThru -ErrorAction Stop
            [void]$cue::SendMessage($txtMagnet.Handle, 0x1501, [IntPtr]1, 'magnet:?xt=urn:btih:... ou URL .torrent')
        } catch { }
        if ($script:Cfg.key) { Connect-Server }
        else { Set-Status 'Informe o servidor e a API key (Debrid Hub -> Usar no VLC / Stremio / Windows).' }
    })

[void]$form.ShowDialog()
