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
    autoMove    = $true
    deletePhone = $true
    minFreeGb   = 5
}
if (Test-Path $script:ConfigPath) {
    try {
        $saved = Get-Content -Raw -Path $script:ConfigPath | ConvertFrom-Json
        if ($saved.url) { $script:Cfg.url = $saved.url }
        if ($saved.key) { $script:Cfg.key = $saved.key }
        if ($saved.downloadDir) { $script:Cfg.downloadDir = $saved.downloadDir }
        if ($null -ne $saved.autoMove) { $script:Cfg.autoMove = [bool]$saved.autoMove }
        if ($null -ne $saved.deletePhone) { $script:Cfg.deletePhone = [bool]$saved.deletePhone }
        if ($null -ne $saved.minFreeGb) { $script:Cfg.minFreeGb = [double]$saved.minFreeGb }
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

$chkAutoPick = New-Object System.Windows.Forms.CheckBox
$chkAutoPick.Text = 'Escolher arquivos antes de baixar'
$chkAutoPick.AutoSize = $true
$chkAutoPick.Checked = $true
$chkAutoPick.Location = New-Object System.Drawing.Point(612, 86)
$chkAutoPick.Add_CheckedChanged({ $script:AutoPick = $chkAutoPick.Checked })
$top.Controls.Add($chkAutoPick)

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

$btnPick = New-SmallButton 'Selecionar arquivos...' { Pick-Files-ForCurrent }
$btnQueue = New-SmallButton 'Fila' { Show-Queue }
$btnDownload = New-SmallButton 'Baixar para o PC' { Start-Download }
$btnCopyLink = New-SmallButton 'Copiar link' { Copy-Link }
$btnVlc = New-SmallButton 'Abrir no VLC' { Open-Vlc }
$btnOpenFolder = New-SmallButton 'Abrir pasta' { Open-Folder }
$chkAutoMove = New-Object System.Windows.Forms.CheckBox
$chkAutoMove.Text = 'Mandar pro PC automaticamente'
$chkAutoMove.AutoSize = $true
$chkAutoMove.Checked = [bool]$script:Cfg.autoMove
$chkAutoMove.Margin = New-Object System.Windows.Forms.Padding(10, 3, 10, 0)
$chkAutoMove.Add_CheckedChanged({ $script:Cfg.autoMove = $chkAutoMove.Checked; Save-Config })

$chkDeletePhone = New-Object System.Windows.Forms.CheckBox
$chkDeletePhone.Text = 'apagar do celular depois de verificar'
$chkDeletePhone.AutoSize = $true
$chkDeletePhone.Checked = [bool]$script:Cfg.deletePhone
$chkDeletePhone.Margin = New-Object System.Windows.Forms.Padding(0, 3, 10, 0)
$chkDeletePhone.Add_CheckedChanged({ $script:Cfg.deletePhone = $chkDeletePhone.Checked; Save-Config })

$barFiles.Controls.AddRange(@($btnPick, $btnDownload, $btnQueue, $btnCopyLink, $btnVlc, $btnOpenFolder, $chkAutoMove, $chkDeletePhone))

# ---------------------------------------------------------------- estado

$script:Torrents = @()
$script:Current = $null
$script:Files = @()
$script:DownloadState = Join-Path $script:AppDir 'dl-state.txt'
$script:DownloadProgress = Join-Path $script:AppDir 'dl-progress.txt'
$script:DownloadCancel = Join-Path $script:AppDir 'dl-cancel.txt'
$script:QueuePath = Join-Path $script:AppDir 'queue.json'
$script:Queue = New-Object System.Collections.ArrayList
$script:QueueJob = $null
$script:QueueItem = $null
$script:QueuePaused = $false
$script:QueueInfo = @{}
$script:QueueInfoAt = @{}
$script:QueueForm = $null
$script:QueueList = $null
$script:QueueLabel = $null
$script:QueueSig = ''
$script:ScanDone = @{}
$script:PhoneFree = -1
$script:PhoneTotal = -1
$script:PhoneFreeAt = [DateTime]::MinValue
$script:Refreshing = $false
$script:Ticks = 0
$script:SelectId = $null
$script:PendingSelect = $null
$script:Picking = $false
$script:TreeGuard = $false
$script:AutoPick = $true

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
            $ignored = ($null -ne $f.selected -and [int]$f.selected -eq 0)
            $st = 'pronto'
            if ([double]$f.progress -lt 99.9) { $st = 'aguardando download' }
            if ($ignored) { $st = 'ignorado (nao baixa)' }
            $item = New-Object System.Windows.Forms.ListViewItem($name)
            [void]$item.SubItems.Add((Format-Size $f.bytes))
            [void]$item.SubItems.Add((Format-Size $f.done))
            [void]$item.SubItems.Add($st)
            if ($ignored) { $item.ForeColor = [System.Drawing.Color]::FromArgb(150, 158, 170) }
            $item.Tag = $f
            [void]$lvFiles.Items.Add($item)
        }
        $lvFiles.EndUpdate()
        if ($keepIndex -ge 0 -and $keepIndex -lt $lvFiles.Items.Count) {
            $lvFiles.Items[$keepIndex].Selected = $true
            $lvFiles.Items[$keepIndex].Focused = $true
        }
        $ign = @($script:Files | Where-Object { $null -ne $_.selected -and [int]$_.selected -eq 0 }).Count
        $msg = "{0} arquivo(s) no torrent" -f $script:Files.Count
        if ($ign -gt 0) { $msg += " - $ign ignorado(s)" }
        Set-Status $msg
    } catch {
        Set-Status "Erro ao abrir torrent: $($_.Exception.Message)" 'error'
    }
}

function Set-NodeChecked($nodes, [bool]$value) {
    foreach ($n in $nodes) {
        $n.Checked = $value
        if ($n.Nodes.Count) { Set-NodeChecked $n.Nodes $value }
    }
}

function Get-CheckedIds($nodes) {
    $ids = New-Object System.Collections.Generic.List[int]
    foreach ($n in $nodes) {
        if ($n.Tag -and $n.Tag.kind -eq 'file' -and $n.Checked) { $ids.Add([int]$n.Tag.index) }
        if ($n.Nodes.Count) { foreach ($id in @(Get-CheckedIds $n.Nodes)) { $ids.Add($id) } }
    }
    return , $ids
}

function Update-Parents($nodes) {
    foreach ($n in $nodes) {
        if ($n.Nodes.Count) {
            Update-Parents $n.Nodes
            $all = $true
            foreach ($c in $n.Nodes) { if (-not $c.Checked) { $all = $false; break } }
            $n.Checked = $all
        }
    }
}

function Apply-Selection([string]$torrentId, $ids, [int]$total) {
    $q = @{}
    if ($ids.Count -eq 0) { $q['files'] = 'none' }
    elseif ($ids.Count -ge $total) { $q['files'] = 'all' }
    else { $q['files'] = ($ids -join ',') }
    Api -Method 'POST' -Path ("torrents/selectFiles/" + $torrentId) -Query $q | Out-Null
}

function Pick-Files-ForCurrent {
    if (-not $script:Current) { Set-Status 'Selecione um torrent na lista' 'error'; return }
    Show-FilePicker ([string]$script:Current.id)
}

function Show-FilePicker([string]$torrentId) {
    if ($script:Picking) { return }
    $script:Picking = $true
    try {
        $info = Api -Path ("torrents/info/" + $torrentId)
        $files = @($info.files)
        if (-not $files.Count) {
            Set-Status 'Metadados ainda nao chegaram (depende dos seeders) - tente outra vez em instantes' 'error'
            return
        }

        $pf = New-Object System.Windows.Forms.Form
        $pf.Text = 'Escolher o que baixar'
        $pf.Size = New-Object System.Drawing.Size(700, 600)
        $pf.MinimumSize = New-Object System.Drawing.Size(520, 400)
        $pf.StartPosition = 'CenterParent'
        $pf.BackColor = [System.Drawing.Color]::FromArgb(244, 247, 251)
        $pf.Font = New-Object System.Drawing.Font('Segoe UI', 9)

        $hint = New-Object System.Windows.Forms.Label
        $hint.Text = 'Marque as pastas/arquivos que voce quer baixar. O resto e ignorado (nao gasta dados nem espaco).'
        $hint.Dock = 'Top'
        $hint.Height = 28
        $hint.Padding = New-Object System.Windows.Forms.Padding(8, 7, 8, 0)
        $hint.ForeColor = [System.Drawing.Color]::FromArgb(60, 70, 85)
        $pf.Controls.Add($hint)

        $tv = New-Object System.Windows.Forms.TreeView
        $tv.Dock = 'Fill'
        $tv.CheckBoxes = $true
        $tv.FullRowSelect = $true
        $tv.HideSelection = $false
        $tv.BorderStyle = 'FixedSingle'
        $tv.BackColor = [System.Drawing.Color]::White
        $pf.Controls.Add($tv)

        $pbar = New-Object System.Windows.Forms.FlowLayoutPanel
        $pbar.Dock = 'Bottom'
        $pbar.Height = 46
        $pbar.Padding = New-Object System.Windows.Forms.Padding(8, 9, 8, 0)
        $pbar.BackColor = [System.Drawing.Color]::White
        $pf.Controls.Add($pbar)

        function New-PickButton([string]$text, [scriptblock]$onClick) {
            $b = New-Object System.Windows.Forms.Button
            $b.Text = $text
            $b.AutoSize = $true
            $b.FlatStyle = 'Flat'
            $b.Margin = New-Object System.Windows.Forms.Padding(0, 0, 8, 0)
            if ($onClick) { $b.Add_Click($onClick) }
            return $b
        }

        $pAll = New-PickButton 'Marcar tudo' { $script:TreeGuard = $true; try { Set-NodeChecked $tv.Nodes $true } finally { $script:TreeGuard = $false }; & $updateCount }
        $pbar.Controls.Add($pAll)

        $pNone = New-PickButton 'Desmarcar tudo' { $script:TreeGuard = $true; try { Set-NodeChecked $tv.Nodes $false } finally { $script:TreeGuard = $false }; & $updateCount }
        $pbar.Controls.Add($pNone)

        $pVideos = New-PickButton 'So videos' {
            $script:TreeGuard = $true
            try {
                Set-NodeChecked $tv.Nodes $false
                $stack = New-Object System.Collections.Stack
                foreach ($n in $tv.Nodes) { $stack.Push($n) }
                while ($stack.Count -gt 0) {
                    $n = $stack.Pop()
                    if ($n.Tag -and $n.Tag.kind -eq 'file') {
                        if ($n.Text -match '\.(mp4|mkv|avi|mov|webm|m4v|ts|mpg|mpeg|wmv|flv)$') {
                            $n.Checked = $true
                            $up = $n.Parent
                            while ($up) { $up.Checked = $true; $up = $up.Parent }
                        }
                    }
                    foreach ($c in $n.Nodes) { $stack.Push($c) }
                }
            } finally { $script:TreeGuard = $false }
            & $updateCount
        }
        $pbar.Controls.Add($pVideos)

        $pCount = New-Object System.Windows.Forms.Label
        $pCount.AutoSize = $true
        $pCount.Margin = New-Object System.Windows.Forms.Padding(6, 7, 10, 0)
        $pCount.ForeColor = [System.Drawing.Color]::FromArgb(110, 120, 135)
        $pbar.Controls.Add($pCount)

        $pOk = New-PickButton 'Aplicar' $null
        $pOk.DialogResult = 'OK'
        $pOk.BackColor = [System.Drawing.Color]::FromArgb(30, 111, 217)
        $pOk.ForeColor = [System.Drawing.Color]::White
        $pbar.Controls.Add($pOk)

        $pCancel = New-PickButton 'Cancelar' $null
        $pCancel.DialogResult = 'Cancel'
        $pbar.Controls.Add($pCancel)
        $pf.AcceptButton = $pOk
        $pf.CancelButton = $pCancel

        $updateCount = {
            $n = @(Get-CheckedIds $tv.Nodes).Count
            $pCount.Text = ("{0} de {1} marcados" -f $n, $files.Count)
        }

        $script:TreeGuard = $true
        try {
            $map = @{}
            foreach ($f in $files) {
                $full = ([string]$f.path).Trim()
                while ($full.StartsWith('/') -or $full.StartsWith('\')) { $full = $full.Substring(1) }
                $full = $full.Replace('\', '/')
                $segs = @($full -split '/')
                $leafName = $segs[$segs.Count - 1]
                $parentNodes = $tv.Nodes
                $acc = ''
                if ($segs.Count -gt 1) {
                    for ($i = 0; $i -lt ($segs.Count - 1); $i++) {
                        $d = $segs[$i]
                        if ($acc) { $acc = "$acc/$d" } else { $acc = $d }
                        if (-not $map.ContainsKey($acc)) {
                            $dn = New-Object System.Windows.Forms.TreeNode($d)
                            $dn.Tag = @{ kind = 'dir' }
                            $dn.NodeFont = New-Object System.Drawing.Font('Segoe UI', 9, [System.Drawing.FontStyle]::Bold)
                            [void]$parentNodes.Add($dn)
                            $map[$acc] = $dn
                        }
                        $parentNodes = $map[$acc].Nodes
                    }
                }
                $leaf = New-Object System.Windows.Forms.TreeNode($leafName)
                $leaf.Tag = @{ kind = 'file'; index = [int]$f.id }
                $leaf.Checked = ($null -eq $f.selected -or [int]$f.selected -ne 0)
                [void]$parentNodes.Add($leaf)
            }
            Update-Parents $tv.Nodes
        } finally { $script:TreeGuard = $false }

        & $updateCount

        $tv.Add_AfterCheck({
                param($sender, $e)
                if ($script:TreeGuard) { return }
                $script:TreeGuard = $true
                try {
                    if ($e.Node.Tag -and $e.Node.Tag.kind -eq 'dir') { Set-NodeChecked $e.Node.Nodes $e.Node.Checked }
                    $up = $e.Node.Parent
                    while ($up) {
                        $all = $true
                        foreach ($c in $up.Nodes) { if (-not $c.Checked) { $all = $false; break } }
                        $up.Checked = $all
                        $up = $up.Parent
                    }
                } finally { $script:TreeGuard = $false }
                & $updateCount
            })

        $tv.ExpandAll()

        $answer = $pf.ShowDialog()
        if ($answer -eq 'OK') {
            $ids = @(Get-CheckedIds $tv.Nodes)
            Apply-Selection $torrentId $ids $files.Count
            Set-Status ("Selecao aplicada: {0} de {1} arquivo(s) serao baixados" -f $ids.Count, $files.Count) 'ok'
            $script:SelectId = $torrentId
            Refresh-Torrents
            if ($script:Current -and [string]$script:Current.id -eq $torrentId) { Load-Files $script:Current }
        } else {
            Set-Status 'Selecao mantida como estava'
        }
    } catch {
        Set-Status "Falha ao escolher arquivos: $($_.Exception.Message)" 'error'
    } finally {
        $script:Picking = $false
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
        $script:PendingSelect = [string]$r.id
        Set-Status "Torrent adicionado - buscando metadados para voce escolher os arquivos" 'ok'
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

# ---------------------------------------------------------------- fila de transferencia

function Format-Eta([double]$sec) {
    if ($null -eq $sec -or $sec -lt 0 -or [double]::IsNaN($sec) -or [double]::IsInfinity($sec)) { return '-' }
    if ($sec -lt 60) { return ('{0}s' -f [int]$sec) }
    if ($sec -lt 3600) { return ('{0}min' -f [int][math]::Round($sec / 60)) }
    return ('{0}h{1:00}' -f [int][math]::Floor($sec / 3600), [int][math]::Floor(($sec % 3600) / 60))
}

function Queue-Save {
    try {
        $items = @()
        foreach ($q in $script:Queue) {
            $items += [ordered]@{
                id = $q.id; fileId = $q.fileId; name = $q.name; path = $q.path
                size = $q.size; bytes = $q.bytes; status = $q.status; dest = $q.dest
                torrentName = $q.torrentName; purged = [bool]$q.purged
            }
        }
        (@{ items = $items } | ConvertTo-Json -Depth 5) | Set-Content -Path $script:QueuePath -Encoding UTF8
    } catch { }
}

function Queue-Load {
    if (-not (Test-Path $script:QueuePath)) { return }
    try {
        $data = Get-Content -Raw -Path $script:QueuePath | ConvertFrom-Json
        foreach ($it in @($data.items)) {
            if (-not $it) { continue }
            $item = New-Object psobject -Property @{
                id = [string]$it.id; fileId = [int]$it.fileId; name = [string]$it.name
                path = [string]$it.path; size = [int64]$it.size; bytes = [int64]$it.bytes
                status = [string]$it.status; dest = [string]$it.dest
                torrentName = [string]$it.torrentName; speed = 0.0; eta = -1.0
                link = $null; note = ''; purged = [bool]$it.purged
            }
            if ($item.status -eq 'downloading' -or $item.status -eq 'verifying') { $item.status = 'queued' }
            $part = "$($item.dest).part"
            if (Test-Path -LiteralPath $part) {
                # continua exatamente de onde parou
                $item.bytes = (Get-Item -LiteralPath $part).Length
                $item.status = 'queued'
            }
            if ($item.status -eq 'done' -and -not (Test-Path -LiteralPath $item.dest)) {
                $item.status = 'queued'; $item.bytes = 0
            }
            [void]$script:Queue.Add($item)
        }
        if ($script:Queue.Count -gt 0) {
            Set-Status ("Fila retomada: {0} arquivo(s) - {1} já no PC" -f $script:Queue.Count, @($script:Queue | Where-Object { $_.status -eq 'done' }).Count)
        }
    } catch { }
}

function Queue-Contains([string]$id, [int]$fileId) {
    foreach ($q in $script:Queue) {
        if ($q.id -eq $id -and $q.fileId -eq $fileId -and $q.status -ne 'error') { return $true }
    }
    return $false
}

function Get-TorrentInfo([string]$id, [switch]$Force) {
    $now = [DateTime]::UtcNow
    if (-not $Force -and $script:QueueInfoAt.ContainsKey($id)) {
        if (($now - $script:QueueInfoAt[$id]).TotalSeconds -lt 3) { return $script:QueueInfo[$id] }
    }
    $info = Api -Path ('torrents/info/' + $id)
    $script:QueueInfo[$id] = $info
    $script:QueueInfoAt[$id] = $now
    return $info
}

function Queue-AddFile([string]$torrentId, [string]$torrentName, $file) {
    if ($null -eq $file) { return 0 }
    if ($null -ne $file.selected -and [int]$file.selected -eq 0) { return 0 }
    if (Queue-Contains $torrentId ([int]$file.id)) { return 0 }
    $rel = ([string]$file.path).TrimStart('/')
    $dest = Join-Path $script:Cfg.downloadDir ($rel.Replace('/', '\'))
    $item = New-Object psobject -Property @{
        id = $torrentId; fileId = [int]$file.id; name = (Split-Path $rel -Leaf); path = $rel
        size = [int64]$file.bytes; bytes = [int64]0; status = 'queued'; dest = $dest
        torrentName = $torrentName; speed = 0.0; eta = -1.0; link = $null; note = ''; purged = $false
    }
    if ((Test-Path -LiteralPath $dest) -and ((Get-Item -LiteralPath $dest).Length -eq $item.size) -and $item.size -gt 0) {
        $item.status = 'done'; $item.bytes = $item.size; $item.note = 'já estava no PC'
    }
    [void]$script:Queue.Add($item)
    return 1
}

function Queue-AddTorrent([string]$torrentId, [string]$torrentName) {
    $info = Get-TorrentInfo $torrentId -Force
    $n = 0
    foreach ($f in @($info.files)) { $n += Queue-AddFile $torrentId $torrentName $f }
    if ($n -gt 0) { Queue-Save; Queue-UpdateUi }
    return $n
}

function Get-FreeSpace([string]$path) {
    try {
        $dir = [System.IO.Path]::GetDirectoryName($path)
        while ($dir -and -not (Test-Path -LiteralPath $dir)) { $dir = [System.IO.Path]::GetDirectoryName($dir) }
        if (-not $dir) { return [int64]-1 }
        $root = [System.IO.Path]::GetPathRoot($dir)
        $di = New-Object System.IO.DriveInfo($root)
        return [int64]$di.AvailableFreeSpace
    } catch { return [int64]-1 }
}

function Queue-StartItem($item) {
    try {
        $parent = [System.IO.Path]::GetDirectoryName($item.dest)
        if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }

        # margem de seguranca: nao enche o disco do PC
        $free = Get-FreeSpace $item.dest
        $need = ($item.size - $item.bytes) + [int64]([double]$script:Cfg.minFreeGb * 1GB)
        if ($free -ge 0 -and $free -lt $need) {
            $item.status = 'error'
            $item.note = ('sem espaço no PC (precisa de {0} livres)' -f (Format-Size $need))
            $script:QueuePaused = $true
            Set-Status ("Fila pausada: $($item.note)") 'error'
            Queue-Save; Queue-UpdateUi
            return
        }

        $info = Api -Method 'POST' -Path 'unrestrict/link' -Query @{ id = $item.id; file = ($item.fileId - 1) }
        if (-not $info.download) { throw 'o celular não devolveu link de download' }
        $item.link = [string]$info.download

        $part = "$($item.dest).part"
        $offset = [int64]0
        if (Test-Path -LiteralPath $part) { $offset = [int64](Get-Item -LiteralPath $part).Length }
        if ($item.size -gt 0 -and $offset -gt $item.size) { Remove-Item -LiteralPath $part -Force; $offset = 0 }
        if ($item.size -gt 0 -and $offset -eq $item.size) { $offset = 0; Remove-Item -LiteralPath $part -Force }

        Remove-Item $script:DownloadState, $script:DownloadProgress, $script:DownloadCancel -ErrorAction SilentlyContinue
        'running' | Set-Content -Path $script:DownloadState -Encoding ASCII
        ("{0}|{1}|0" -f $offset, $item.size) | Set-Content -Path $script:DownloadProgress -Encoding ASCII

        $script:QueueJob = Start-Job -ArgumentList $item.link, $part, $offset, $item.size, $script:DownloadProgress, $script:DownloadState, $script:DownloadCancel -ScriptBlock {
            param($url, $tmp, $offset, $totalSize, $progFile, $stateFile, $cancelFile)
            $why = ''
            try {
                Add-Type -AssemblyName System.Net.Http
                $client = New-Object System.Net.Http.HttpClient
                $client.Timeout = [TimeSpan]::FromHours(6)
                if ($offset -gt 0) { [void]$client.DefaultRequestHeaders.Add('Range', "bytes=$offset-") }
                $resp = $client.GetAsync($url, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
                $resp.EnsureSuccessStatusCode() | Out-Null
                if ($offset -gt 0 -and [int]$resp.StatusCode -ne 206) { $offset = 0 }  # servidor ignorou o Range: recomeca
                $total = $totalSize
                if ($resp.Content.Headers.ContentLength) { $total = $offset + [int64]$resp.Content.Headers.ContentLength }
                $in = $resp.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
                if ($offset -gt 0) { $out = New-Object System.IO.FileStream($tmp, [System.IO.FileMode]::Append, [System.IO.FileAccess]::Write) }
                else { $out = New-Object System.IO.FileStream($tmp, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write) }
                $buf = New-Object byte[] 1048576
                $done = [int64]$offset
                $lastTicks = [DateTime]::UtcNow
                $lastBytes = [int64]$offset
                while (($n = $in.Read($buf, 0, $buf.Length)) -gt 0) {
                    $out.Write($buf, 0, $n)
                    $done += $n
                    $nowT = [DateTime]::UtcNow
                    $dt = ($nowT - $lastTicks).TotalSeconds
                    if ($dt -ge 0.5) {
                        $speed = [int64](($done - $lastBytes) / $dt)
                        ("{0}|{1}|{2}" -f $done, $total, $speed) | Set-Content -Path $progFile -Encoding ASCII
                        $lastTicks = $nowT; $lastBytes = $done
                    }
                    if (Test-Path $cancelFile) { break }
                }
                $out.Flush(); $out.Close(); $in.Close(); $client.Dispose()
                ("{0}|{1}|0" -f $done, $total) | Set-Content -Path $progFile -Encoding ASCII
                if (Test-Path $cancelFile) {
                    'cancel' | Set-Content -Path $stateFile -Encoding ASCII
                } elseif ($total -gt 0 -and (Get-Item -LiteralPath $tmp).Length -ne $total) {
                    'error|tamanho recebido diferente do esperado' | Set-Content -Path $stateFile -Encoding ASCII
                } else {
                    Move-Item -LiteralPath $tmp -Destination ($tmp -replace '\.part$', '') -Force
                    "done|$done|$total" | Set-Content -Path $stateFile -Encoding ASCII
                }
            } catch {
                'error|' + $_.Exception.Message | Set-Content -Path $stateFile -Encoding ASCII
            }
        }

        $item.status = 'downloading'
        $item.note = if ($offset -gt 0) { 'retomando' } else { 'baixando' }
        $script:QueueItem = $item
        $prog.Visible = $true
        if ($item.size -gt 0) { $prog.Value = [int][math]::Min(100, [math]::Round($offset * 100 / $item.size)) } else { $prog.Value = 0 }
        Set-Status ("Mandando pro PC: {0}{1}" -f $item.name, $(if ($offset -gt 0) { ' (retomando de ' + (Format-Size $offset) + ')' } else { '' }))
        Queue-Save
        Queue-UpdateUi
    } catch {
        $item.status = 'error'
        $item.note = $_.Exception.Message
        Set-Status ("Falha ao transferir {0}: {1}" -f $item.name, $_.Exception.Message) 'error'
        Queue-Save; Queue-UpdateUi
    }
}

function Raw-Get([string]$pathAndQuery) {
    $base = $script:Cfg.url.Trim().TrimEnd('/')
    $sep = if ($pathAndQuery.Contains('?')) { '&' } else { '?' }
    $uri = "$base$pathAndQuery" + $sep + 'token=' + [uri]::EscapeDataString([string]$script:Cfg.key)
    return Invoke-RestMethod -Uri $uri -Headers @{ Authorization = "Bearer $($script:Cfg.key)" } -TimeoutSec 900
}

function Get-PhonePath($item) {
    try {
        $list = Raw-Get '/api/dl/list'
        $task = @($list.tasks) | Where-Object { [string]$_.id -eq [string]$item.id } | Select-Object -First 1
        if (-not $task -or -not $task.dest) { return $null }
        $sep = if ($task.dest.EndsWith('/')) { '' } else { '/' }
        return ($task.dest + $sep + ([string]$item.path).TrimStart('/'))
    } catch { return $null }
}

function Test-QueueItem($item) {
    try {
        if (-not (Test-Path -LiteralPath $item.dest)) { return $false }
        $len = [int64](Get-Item -LiteralPath $item.dest).Length
        if ($item.size -gt 0 -and $len -ne $item.size) { return $false }

        # 1) garantia forte: SHA-256 do celular x SHA-256 do arquivo no PC
        $phonePath = Get-PhonePath $item
        if ($phonePath) {
            try {
                $h = Raw-Get ('/api/hash?path=' + [uri]::EscapeDataString($phonePath))
                if ($h -and $h.sha256 -and $h.size -eq $len) {
                    $localHash = (Get-FileHash -LiteralPath $item.dest -Algorithm SHA256).Hash.ToLower()
                    if ([string]$h.sha256 -ne $localHash) {
                        $item.note = 'sha-256 diferente'
                        return $false
                    }
                    $item.note = 'verificado (sha-256)'
                    return $true
                }
            } catch {
                # celular antigo sem /api/hash: cai para a conferencia por amostragem
            }
        }
        if (-not $item.link) {
            $info = Api -Method 'POST' -Path 'unrestrict/link' -Query @{ id = $item.id; file = ($item.fileId - 1) }
            $item.link = [string]$info.download
        }
        if (-not $item.link) { return $false }
        Add-Type -AssemblyName System.Net.Http
        $client = New-Object System.Net.Http.HttpClient
        $client.Timeout = [TimeSpan]::FromMinutes(3)
        try {
            $fs = [System.IO.File]::OpenRead($item.dest)
            try {
                # confere 3 pedacos do arquivo contra o celular (nao baixa o arquivo de novo)
                foreach ($frac in @(0.02, 0.2, 0.38, 0.56, 0.74, 0.95)) {
                    $blockLen = [int64][math]::Min(16384, $len)
                    if ($blockLen -le 0) { break }
                    $off = [int64][math]::Floor($len * $frac)
                    if ($off + $blockLen -gt $len) { $off = $len - $blockLen }
                    $req = New-Object System.Net.Http.HttpRequestMessage('GET', $item.link)
                    [void]$req.Headers.Add('Range', ("bytes={0}-{1}" -f $off, ($off + $blockLen - 1)))
                    $resp = $client.SendAsync($req).GetAwaiter().GetResult()
                    if (-not $resp.IsSuccessStatusCode) { return $false }
                    $remote = $resp.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
                    $resp.Dispose(); $req.Dispose()
                    if ($remote.Length -ne $blockLen) { return $false }
                    [void]$fs.Seek($off, [System.IO.SeekOrigin]::Begin)
                    $local = New-Object byte[] $blockLen
                    $read = 0
                    while ($read -lt $blockLen) {
                        $n = $fs.Read($local, $read, $blockLen - $read)
                        if ($n -le 0) { break }
                        $read += $n
                    }
                    for ($i = 0; $i -lt $blockLen; $i++) { if ($local[$i] -ne $remote[$i]) { return $false } }
                }
            } finally { $fs.Close() }
        } finally { $client.Dispose() }
        return $true
    } catch {
        try { Add-Content -Path $script:LogPath -Value ("[{0}] verificação {1}: {2}" -f (Get-Date), $item.name, $_.Exception.Message) } catch { }
        return $false
    }
}

function Queue-StartNext {
    if ($script:QueuePaused -or $script:QueueJob) { return }
    foreach ($item in @($script:Queue)) {
        if ($item.status -ne 'queued') { continue }
        $info = $null
        try { $info = Get-TorrentInfo $item.id } catch { continue }
        $f = @($info.files) | Where-Object { [int]$_.id -eq $item.fileId } | Select-Object -First 1
        if (-not $f) {
            $item.status = 'error'; $item.note = 'torrent não está mais no celular'
            Queue-Save; Queue-UpdateUi
            continue
        }
        $item.size = [int64]$f.bytes
        if ([double]$f.progress -lt 99.9 -and [int64]$f.done -lt $item.size) {
            $phoneErr = [string]$info.error
            if ($phoneErr) { $item.note = 'celular: ' + $phoneErr }
            else { $item.note = 'esperando o celular terminar' }
            continue
        }
        Queue-StartItem $item
        return
    }
}

function Queue-Poll {
    if (-not $script:QueueJob) { return }
    try {
        if (Test-Path $script:DownloadProgress) {
            $raw = (Get-Content -Raw -Path $script:DownloadProgress -ErrorAction SilentlyContinue)
            if ($raw -and $script:QueueItem) {
                $parts = $raw.Trim().Split('|')
                if ($parts.Count -ge 2) {
                    $done = [int64]$parts[0]; $total = [int64]$parts[1]
                    $speed = if ($parts.Count -ge 3) { [double]$parts[2] } else { 0 }
                    $script:QueueItem.bytes = $done
                    $script:QueueItem.speed = $speed
                    if ($speed -gt 0 -and $total -gt $done) { $script:QueueItem.eta = ($total - $done) / $speed } else { $script:QueueItem.eta = -1 }
                    if ($total -gt 0) {
                        $pct = [int][math]::Min(100, [math]::Round($done * 100 / $total))
                        $prog.Value = $pct
                        Set-Status ("{0}: {1} de {2} ({3}%) · {4}/s · faltam {5}" -f $script:QueueItem.name, (Format-Size $done), (Format-Size $total), $pct, (Format-Size $speed), (Format-Eta $script:QueueItem.eta))
                    }
                    Queue-UpdateUi
                }
            }
        }
        if (Test-Path $script:DownloadState) {
            $state = (Get-Content -Raw -Path $script:DownloadState -ErrorAction SilentlyContinue)
            if ($state) {
                $state = $state.Trim()
                if ($state.StartsWith('done') -or $state.StartsWith('error') -or $state -eq 'cancel' -or $state.StartsWith('stop')) {
                    Queue-Finish $state
                }
            }
        }
    } catch { }
}

function Queue-Finish([string]$state) {
    $item = $script:QueueItem
    try {
        if ($script:QueueJob) { Wait-Job $script:QueueJob -Timeout 5 | Out-Null; Remove-Job $script:QueueJob -Force -ErrorAction SilentlyContinue }
    } catch { }
    $script:QueueJob = $null
    $prog.Visible = $false
    Remove-Item $script:DownloadState, $script:DownloadProgress, $script:DownloadCancel -ErrorAction SilentlyContinue
    if ($null -eq $item) { return }

    if ($state.StartsWith('done')) {
        $item.status = 'verifying'
        Queue-UpdateUi
        $ok = Test-QueueItem $item
        if ($ok) {
            $item.status = 'done'; $item.bytes = $item.size; $item.speed = 0; $item.eta = 0; $item.note = 'verificado'
            Set-Status ("Chegou no PC e confere: {0}" -f $item.name) 'ok'
        } else {
            $item.status = 'error'; $item.note = 'verificação falhou — não apaguei do celular'
            Set-Status ("Verificação falhou em {0} (nada foi apagado do celular)" -f $item.name) 'error'
        }
    } elseif ($state -eq 'cancel' -or $state.StartsWith('stop')) {
        $item.status = 'queued'
        $item.speed = 0
        Set-Status 'Transferência pausada'
    } else {
        $msg = $state -replace '^error\|', ''
        $item.status = 'error'; $item.note = $msg
        Set-Status ("Falha em {0}: {1}" -f $item.name, $msg) 'error'
    }
    $script:QueueItem = $null
    Queue-Save
    Queue-AfterFinish
    Queue-UpdateUi
    if ($script:Current) { Load-Files $script:Current }
}

function Test-TorrentFullyOnPc([string]$id) {
    try { $info = Get-TorrentInfo $id -Force } catch { return $false }
    $files = @($info.files)
    if (-not $files.Count) { return $false }
    foreach ($f in $files) {
        if ($null -ne $f.selected -and [int]$f.selected -eq 0) { continue }   # já saiu do celular
        $q = @($script:Queue | Where-Object { $_.id -eq $id -and $_.fileId -eq [int]$f.id -and $_.status -eq 'done' })
        if ($q.Count -eq 0) { return $false }
    }
    return $true
}

function Queue-DropTorrent([string]$id) {
    try {
        $info = Get-TorrentInfo $id -Force
        Api -Method 'POST' -Path ('torrents/delete/' + $id) -Query @{ files = '1' } | Out-Null
        foreach ($q in @($script:Queue)) { if ($q.id -eq $id) { [void]$script:Queue.Remove($q) } }
        Queue-Save
        Set-Status ("Tudo verificado no PC — '{0}' saiu do celular e o espaço foi liberado" -f $info.filename) 'ok'
        Refresh-Torrents
    } catch { }
}

function Queue-AfterFinish {
    if (-not $script:Cfg.deletePhone) { return }
    $doneIds = @($script:Queue | Where-Object { $_.status -eq 'done' } | Select-Object -ExpandProperty id -Unique)
    foreach ($id in $doneIds) {
        # 1) o torrent inteiro já está no PC e verificado -> sai do celular de uma vez
        if (Test-TorrentFullyOnPc $id) { Queue-DropTorrent $id; continue }

        # 2) janela deslizante: libera cada arquivo já verificado, para um torrent maior
        #    que o espaço do celular continuar baixando o resto
        $info = $null
        try { $info = Get-TorrentInfo $id -Force } catch { continue }
        $files = @($info.files)
        if (-not $files.Count) { continue }
        $pending = @($script:Queue | Where-Object { $_.id -eq $id -and $_.status -ne 'done' })
        $outside = @($files | Where-Object { ($null -eq $_.selected -or [int]$_.selected -ne 0) -and -not (Queue-Contains $id ([int]$_.id)) })

        if ($pending.Count -eq 0 -and $outside.Count -eq 0) { Queue-DropTorrent $id; continue }

        foreach ($q in @($script:Queue | Where-Object { $_.id -eq $id -and $_.status -eq 'done' -and -not $_.purged })) {
            try {
                $r = Api -Method 'POST' -Path ('torrents/purgeFile/' + $id) -Query @{ file = $q.fileId }
                if ($r.ok) {
                    $q.purged = $true
                    if ($null -ne $r.freeSpace) { $script:PhoneFree = [double]$r.freeSpace }
                    $livre = if ($script:PhoneFree -gt 0) { (Format-Size $script:PhoneFree) + ' livres' } else { 'liberado' }
                    Set-Status ("'{0}' já está no PC — apagado do celular ({1})" -f $q.name, $livre) 'ok'
                    if ($script:PhoneFree -gt (2GB)) {
                        try { Api -Method 'POST' -Path ('torrents/resume/' + $id) | Out-Null } catch { }
                    }
                } else {
                    $q.note = 'não consegui apagar do celular'
                }
            } catch { }
        }
        Queue-Save
        # depois de liberar, se não sobrou nada no celular, remove o torrent também
        if (Test-TorrentFullyOnPc $id) { Queue-DropTorrent $id }
    }
}

function Queue-AutoScan {
    if (-not $script:Cfg.autoMove -or -not $script:Cfg.key) { return }
    $added = 0
    $scanned = 0
    foreach ($t in @($script:Torrents)) {
        if (-not $t.id) { continue }
        if ($script:ScanDone.ContainsKey([string]$t.id)) { continue }
        if ($scanned -ge 3) { break }   # no maximo 3 torrents por varredura
        $scanned++
        try {
            $info = Get-TorrentInfo ([string]$t.id) -Force
            $files = @($info.files)
            if (-not $files.Count) { continue }
            $pending = 0
            foreach ($f in $files) {
                if ($null -ne $f.selected -and [int]$f.selected -eq 0) { continue }
                if ([double]$f.progress -lt 99.9) { $pending++; continue }
                $added += Queue-AddFile ([string]$t.id) ([string]$t.filename) $f
            }
            if ($pending -eq 0) { $script:ScanDone[[string]$t.id] = $true }   # nada mais a transferir
        } catch { }
    }
    if ($added -gt 0) {
        Queue-Save
        Queue-UpdateUi
    }
}

function Queue-Tick {
    if ($script:QueueJob) { Queue-Poll; return }
    if ($script:QueuePaused) { return }
    if ($script:Queue.Count -gt 0) { Queue-StartNext }
}

function Queue-UpdateUi {
    if (-not $btnQueue) { return }
    $total = $script:Queue.Count
    $done = @($script:Queue | Where-Object { $_.status -eq 'done' }).Count
    $btnQueue.Text = if ($total -gt 0) { "Fila ($done/$total)" } else { 'Fila' }
    if (-not $script:QueueForm -or $script:QueueForm.IsDisposed -or -not $script:QueueList) { return }
    try {
        $sig = ($script:Queue | ForEach-Object { "$($_.name)|$($_.status)" }) -join ';'
        if ($sig -ne $script:QueueSig) {
            $script:QueueSig = $sig
            $script:QueueList.BeginUpdate()
            $script:QueueList.Items.Clear()
            foreach ($q in $script:Queue) {
                $pct = if ($q.size -gt 0) { [int][math]::Min(100, [math]::Round($q.bytes * 100 / $q.size)) } else { 0 }
                $st = switch ($q.status) {
                    'queued' { if ($q.note) { $q.note } else { 'na fila' } }
                    'downloading' { 'baixando' }
                    'verifying' { 'verificando' }
                    'done' { 'no PC ✓' }
                    default { if ($q.note) { $q.note } else { 'falhou' } }
                }
                $it = New-Object System.Windows.Forms.ListViewItem($q.path)
                [void]$it.SubItems.Add((Format-Size $q.size))
                [void]$it.SubItems.Add(("{0} ({1}%)" -f (Format-Size $q.bytes), $pct))
                [void]$it.SubItems.Add((Format-Eta $q.eta))
                [void]$it.SubItems.Add($st)
                if ($q.status -eq 'error') { $it.ForeColor = [System.Drawing.Color]::FromArgb(190, 60, 60) }
                elseif ($q.status -eq 'done') { $it.ForeColor = [System.Drawing.Color]::FromArgb(25, 130, 75) }
                [void]$script:QueueList.Items.Add($it)
            }
            $script:QueueList.EndUpdate()
        }
        if ($script:QueueLabel) {
            $pend = $total - $done
            $eta = -1.0
            foreach ($q in $script:Queue) { if ($q.status -eq 'downloading' -and $q.eta -gt 0) { $eta = $q.eta } }
            $phone = if ($script:PhoneFree -ge 0) {
                $p = ' · celular: ' + (Format-Size $script:PhoneFree) + ' livres'
                if ($script:PhoneTotal -gt 0) { $p += ' de ' + (Format-Size $script:PhoneTotal) }
                $p
            } else { '' }
            $script:QueueLabel.Text = if ($total -eq 0) { 'Fila vazia.' + $phone } else {
                ("{0} arquivo(s) na fila · {1} no PC · {2} faltando{3}{4}{5}" -f $total, $done, $pend,
                    $(if ($eta -gt 0) { ' · faltam ~' + (Format-Eta $eta) } else { '' }),
                    $(if ($script:QueuePaused) { ' · PAUSADA' } else { '' }), $phone)
            }
        }
    } catch { }
}

function Show-Queue {
    if ($script:QueueForm -and -not $script:QueueForm.IsDisposed) { $script:QueueForm.Activate(); return }

    $qf = New-Object System.Windows.Forms.Form
    $qf.Text = 'Fila de transferência (celular → PC)'
    $qf.Size = New-Object System.Drawing.Size(820, 480)
    $qf.MinimumSize = New-Object System.Drawing.Size(620, 360)
    $qf.StartPosition = 'CenterParent'
    $qf.Font = New-Object System.Drawing.Font('Segoe UI', 9)
    $qf.BackColor = [System.Drawing.Color]::FromArgb(244, 247, 251)

    $lbl = New-Object System.Windows.Forms.Label
    $lbl.Dock = 'Top'
    $lbl.Height = 34
    $lbl.Padding = New-Object System.Windows.Forms.Padding(10, 9, 10, 0)
    $lbl.Text = 'Fila vazia.'
    $qf.Controls.Add($lbl)

    $lv = New-Object System.Windows.Forms.ListView
    $lv.Dock = 'Fill'
    $lv.View = 'Details'
    $lv.FullRowSelect = $true
    $lv.MultiSelect = $false
    [void]$lv.Columns.Add('Arquivo', 330)
    [void]$lv.Columns.Add('Tamanho', 95)
    [void]$lv.Columns.Add('No PC', 130)
    [void]$lv.Columns.Add('Falta', 80)
    [void]$lv.Columns.Add('Status', 170)
    $qf.Controls.Add($lv)

    $qb = New-Object System.Windows.Forms.FlowLayoutPanel
    $qb.Dock = 'Bottom'
    $qb.Height = 50
    $qb.Padding = New-Object System.Windows.Forms.Padding(8, 10, 8, 0)
    $qb.BackColor = [System.Drawing.Color]::White
    $qf.Controls.Add($qb)

    $bPause = New-Object System.Windows.Forms.Button
    $bPause.Text = if ($script:QueuePaused) { 'Retomar fila' } else { 'Pausar fila' }
    $bPause.AutoSize = $true
    $bPause.FlatStyle = 'Flat'
    $bPause.Margin = New-Object System.Windows.Forms.Padding(0, 0, 8, 0)
    $bPause.Add_Click({
            $script:QueuePaused = -not $script:QueuePaused
            $bPause.Text = if ($script:QueuePaused) { 'Retomar fila' } else { 'Pausar fila' }
            if ($script:QueuePaused -and $script:QueueJob) { 'stop' | Set-Content -Path $script:DownloadCancel -Encoding ASCII }
            Set-Status ($(if ($script:QueuePaused) { 'Fila pausada' } else { 'Fila retomada' })) 'ok'
            Queue-UpdateUi
        })
    $qb.Controls.Add($bPause)

    $bRetry = New-Object System.Windows.Forms.Button
    $bRetry.Text = 'Tentar de novo os que falharam'
    $bRetry.AutoSize = $true
    $bRetry.FlatStyle = 'Flat'
    $bRetry.Margin = New-Object System.Windows.Forms.Padding(0, 0, 8, 0)
    $bRetry.Add_Click({
            foreach ($q in $script:Queue) {
                if ($q.status -eq 'error') { $q.status = 'queued'; $q.note = ''; $q.bytes = 0 }
            }
            $script:QueuePaused = $false
            Queue-Save; Queue-UpdateUi
        })
    $qb.Controls.Add($bRetry)

    $bClear = New-Object System.Windows.Forms.Button
    $bClear.Text = 'Limpar concluídos'
    $bClear.AutoSize = $true
    $bClear.FlatStyle = 'Flat'
    $bClear.Margin = New-Object System.Windows.Forms.Padding(0, 0, 8, 0)
    $bClear.Add_Click({
            foreach ($q in @($script:Queue)) { if ($q.status -eq 'done') { [void]$script:Queue.Remove($q) } }
            Queue-Save; Queue-UpdateUi
        })
    $qb.Controls.Add($bClear)

    $lblFree = New-Object System.Windows.Forms.Label
    $lblFree.Text = 'Espaço livre mínimo no PC:'
    $lblFree.AutoSize = $true
    $lblFree.Margin = New-Object System.Windows.Forms.Padding(12, 6, 4, 0)
    $qb.Controls.Add($lblFree)

    $numFree = New-Object System.Windows.Forms.NumericUpDown
    $numFree.Minimum = 0
    $numFree.Maximum = 500
    $numFree.DecimalPlaces = 0
    $numFree.Width = 60
    $numFree.Value = [decimal][double]$script:Cfg.minFreeGb
    $numFree.Margin = New-Object System.Windows.Forms.Padding(0, 3, 4, 0)
    $numFree.Add_ValueChanged({ $script:Cfg.minFreeGb = [double]$numFree.Value; Save-Config })
    $qb.Controls.Add($numFree)

    $lblGb = New-Object System.Windows.Forms.Label
    $lblGb.Text = 'GB'
    $lblGb.AutoSize = $true
    $lblGb.Margin = New-Object System.Windows.Forms.Padding(0, 6, 8, 0)
    $qb.Controls.Add($lblGb)

    $bClose = New-Object System.Windows.Forms.Button
    $bClose.Text = 'Fechar'
    $bClose.AutoSize = $true
    $bClose.FlatStyle = 'Flat'
    $bClose.Margin = New-Object System.Windows.Forms.Padding(0, 0, 0, 0)
    $bClose.Add_Click({ $qf.Close() })
    $qb.Controls.Add($bClose)

    $qf.Add_FormClosed({ $script:QueueForm = $null; $script:QueueList = $null; $script:QueueLabel = $null; $script:QueueSig = '' })

    $script:QueueForm = $qf
    $script:QueueList = $lv
    $script:QueueLabel = $lbl
    $script:QueueSig = ''
    $qf.Show()
    Queue-UpdateUi
}

function Start-Download {
    if (-not $script:Current) { Set-Status 'Selecione um torrent' 'error'; return }
    $n = 0
    if ($lvFiles.SelectedItems.Count -gt 0) {
        foreach ($si in $lvFiles.SelectedItems) { $n += Queue-AddFile ([string]$script:Current.id) ([string]$script:Current.filename) $si.Tag }
    } else {
        $n = Queue-AddTorrent ([string]$script:Current.id) ([string]$script:Current.filename)
    }
    if ($n -eq 0) { Set-Status 'Nada novo: já está na fila ou já está no PC' ; return }
    $script:QueuePaused = $false
    Queue-Save
    Queue-UpdateUi
    Set-Status ("{0} arquivo(s) na fila - vão sendo passados pro PC conforme o celular termina" -f $n) 'ok'
    Queue-Tick
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
                $script:PendingSelect = [string]$r.id
                Set-Status 'Torrent enviado - buscando metadados para voce escolher os arquivos' 'ok'
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
$btnQueue.Add_Click({ Show-Queue })
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
        if ($script:QueueJob) {
            'stop' | Set-Content -Path $script:DownloadCancel -Encoding ASCII
            Stop-Job $script:QueueJob -ErrorAction SilentlyContinue
            Remove-Job $script:QueueJob -Force -ErrorAction SilentlyContinue
        }
        Queue-Save
        Save-Config
    })

$timerTorrents = New-Object System.Windows.Forms.Timer
$timerTorrents.Interval = 3000
$timerTorrents.Add_Tick({
        if (-not $form.Visible) { return }
        if (-not $script:Cfg.key) { return }
        Refresh-Torrents
        Queue-AutoScan
        Update-PhoneSpace
        if (-not $script:QueueJob) { Queue-Tick }
        if ($script:PendingSelect -and -not $script:Picking) {
            try {
                $pendingId = [string]$script:PendingSelect
                $pinfo = Api -Path ("torrents/info/" + $pendingId)
                $pfiles = @($pinfo.files)
                if ($pfiles.Count -gt 0) {
                    $script:PendingSelect = $null
                    $script:SelectId = $pendingId
                    Refresh-Torrents
                    if ($script:AutoPick) {
                        Show-FilePicker $pendingId
                    } else {
                        Set-Status ("{0} arquivo(s) no torrent - use 'Selecionar arquivos...' para escolher" -f $pfiles.Count) 'ok'
                    }
                }
            } catch { }
        }
        $script:Ticks++
        if ($script:Current -and ($script:Ticks % 2) -eq 0) {
            $sel = -1
            if ($lvFiles.SelectedIndices.Count -gt 0) { $sel = $lvFiles.SelectedIndices[0] }
            Load-Files $script:Current $sel
        }
    })
$timerTorrents.Start()

$timerDownload = New-Object System.Windows.Forms.Timer
$timerDownload.Interval = 800
$timerDownload.Add_Tick({ Queue-Tick })
$timerDownload.Start()

$form.Add_Shown({
        try {
            $cue = Add-Type -MemberDefinition '[DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern IntPtr SendMessage(IntPtr hWnd, int msg, IntPtr wParam, string lParam);' -Name Cue -Namespace NexShare -PassThru -ErrorAction Stop
            [void]$cue::SendMessage($txtMagnet.Handle, 0x1501, [IntPtr]1, 'magnet:?xt=urn:btih:... ou URL .torrent')
        } catch { }
        Queue-Load
        Queue-UpdateUi
        if ($script:Cfg.key) { Connect-Server }
        else { Set-Status 'Informe o servidor e a API key (Debrid Hub -> Usar no VLC / Stremio / Windows).' }
    })

[void]$form.ShowDialog()
