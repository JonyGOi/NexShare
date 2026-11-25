# NexShare - mapeia o armazenamento do celular como unidade de rede no Windows (WebDAV).
#
# Uso rapido (recomendado):
#   .\map-drive.ps1 -PhoneIP 192.168.1.100 -Pin 123456
#   .\map-drive.ps1 -Url https://algum-nome.trycloudflare.com -Key <API key>
#
# Por padrao ele usa uma ponte local (NexShareBridge.ps1) que fala HTTP ou HTTPS
# com o celular e entrega HTTP em 127.0.0.1 para o Windows. Isso dispensa
# certificado, administrador, registro e login no Explorer e resolve o "erro 67"
# na maioria das redes de faculdade/empresa. Use -Direct para tentar o caminho antigo.
#
# Outros:
#   .\map-drive.ps1 -Unmount            # desmonta e para a ponte
#   .\map-drive.ps1 ... -Direct         # net use direto (sem ponte)
#   .\map-drive.ps1 ... -NoProxy        # ignora o proxy do sistema na ponte
#   .\map-drive.ps1 ... -Verify         # valida o certificado (com -Url https)
param(
    [string]$PhoneIP,
    [string]$Url,
    [string]$Pin,
    [string]$Key,
    [string]$User = 'nexshare',
    [string]$Drive = 'Z:',
    [switch]$Direct,
    [switch]$NoProxy,
    [switch]$Verify,
    [switch]$Unmount,
    [switch]$Quiet
)

$ErrorActionPreference = 'Continue'

$stateDir = Join-Path $env:LOCALAPPDATA 'NexShare'
if (-not (Test-Path $stateDir)) { New-Item -ItemType Directory -Force -Path $stateDir | Out-Null }
$stateFile = Join-Path $stateDir 'bridge.json'
$portFile = Join-Path $stateDir 'bridge.port'
$pidFile = Join-Path $stateDir 'bridge.pid'
$logFile = Join-Path $stateDir 'bridge.log'
$bridgeScript = Join-Path $PSScriptRoot 'desktop\NexShareBridge.ps1'
if (-not (Test-Path $bridgeScript)) { $bridgeScript = Join-Path $PSScriptRoot 'NexShareBridge.ps1' }

function Stop-BridgePid($bpid) {
    if (-not $bpid) { return }
    try {
        # Only kill the process if it is really our bridge (avoid PID reuse).
        $p = Get-CimInstance Win32_Process -Filter ("ProcessId=" + [int]$bpid) -ErrorAction SilentlyContinue
        if ($p -and $p.CommandLine -like '*NexShareBridge.ps1*') {
            Stop-Process -Id ([int]$bpid) -Force -ErrorAction SilentlyContinue
        }
    } catch { }
}

function Stop-Bridge {
    if (Test-Path $pidFile) {
        try { Stop-BridgePid ([int]((Get-Content $pidFile -Raw).Trim())) } catch { }
        Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
    }
    Remove-Item $portFile -Force -ErrorAction SilentlyContinue
}

function Show-BridgeLog {
    if (Test-Path $logFile) {
        Write-Host '  Ultimas linhas da ponte:' -ForegroundColor DarkGray
        Get-Content $logFile -Tail 8 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
    }
}

# ---------------------------------------------------------------- desmontar
if ($Unmount) {
    if (Test-Path $stateFile) {
        try {
            $st = Get-Content $stateFile -Raw | ConvertFrom-Json
            if ($st.drive) { net use $st.drive /delete /y 2>$null | Out-Null }
            if ($st.pid) { Stop-BridgePid ([int]$st.pid) }
        } catch { }
        Remove-Item $stateFile -Force -ErrorAction SilentlyContinue
    } else {
        net use $Drive /delete /y 2>$null | Out-Null
    }
    Stop-Bridge
    Write-Host "Unidade desmontada e ponte encerrada." -ForegroundColor Green
    exit 0
}

if (-not $Quiet) {
    Write-Host "==========================================================" -ForegroundColor Cyan
    Write-Host "        NexShare - Mapeamento de Unidade de Rede          " -ForegroundColor Cyan
    Write-Host "==========================================================" -ForegroundColor Cyan
}

# ---------------------------------------------------------------- endereco
if (-not $Url) {
    if (-not $PhoneIP) { $PhoneIP = Read-Host "IP do celular exibido no app NexShare (ex: 192.168.1.100)" }
    $PhoneIP = ($PhoneIP -as [string]).Trim()
    if (-not $PhoneIP) { Write-Host "Informe o IP do celular ou use -Url." -ForegroundColor Red; exit 1 }
    $Url = "http://" + $PhoneIP + ":8080"
}
$Url = $Url.Trim()
if ($Url -notmatch '^https?://') { $Url = "https://" + $Url }
$Url = $Url -replace '/dav/?$', ''
$upstream = $Url.TrimEnd('/')
$secure = $upstream.StartsWith('https://')

$auth = if ($Key) { $Key } else { $Pin }
if (-not $auth) {
    Write-Host "A API key e o PIN ficam no app: Debrid Hub -> Usar no VLC / Stremio / Windows." -ForegroundColor Gray
    $auth = Read-Host "API key (ou PIN) do NexShare"
}
$auth = ($auth -as [string]).Trim()
if (-not $auth) { Write-Host "A API key/PIN e obrigatoria." -ForegroundColor Red; exit 1 }

if (-not $Drive.EndsWith(':')) { $Drive = $Drive + ':' }

$requestedLetter = $Drive.Substring(0, 1).ToUpper()
$usedDrives = (Get-PSDrive -PSProvider FileSystem).Name
if ($usedDrives -contains $requestedLetter) {
    Write-Host "Aviso: $Drive ja esta em uso; escolhendo outra letra." -ForegroundColor Yellow
    $freeLetter = [char[]]([char]'D'..[char]'Z') | Where-Object { $usedDrives -notcontains [string]$_ } | Select-Object -First 1
    if ($freeLetter) { $Drive = ([string]$freeLetter) + ':' }
}

# ---------------------------------------------------------------- WebClient
if (-not $Quiet) { Write-Host "[1/4] Preparando o cliente WebDAV do Windows..." -ForegroundColor Yellow }
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if ($isAdmin) {
    $regPath = 'HKLM:\SYSTEM\CurrentControlSet\Services\WebClient\Parameters'
    try {
        Set-ItemProperty -Path $regPath -Name 'BasicAuthLevel' -Value 2 -Type DWord -ErrorAction SilentlyContinue
        Set-ItemProperty -Path $regPath -Name 'FileSizeLimitInBytes' -Value ([uint32]::MaxValue) -Type DWord -ErrorAction SilentlyContinue
        # The WebClient reads these values only at startup; without a restart the old
        # 50 MB limit remains and Explorer reports large files as "moved".
        $wcNow = Get-Service -Name WebClient -ErrorAction SilentlyContinue
        if ($wcNow -and $wcNow.Status -eq 'Running') {
            Restart-Service -Name WebClient -Force -ErrorAction SilentlyContinue
            Start-Sleep -Milliseconds 800
        }
    } catch { }
}
$svc = Get-Service -Name WebClient -ErrorAction SilentlyContinue
if ($svc -and $svc.Status -ne 'Running') {
    if ($isAdmin) { Set-Service -Name WebClient -StartupType Automatic -ErrorAction SilentlyContinue }
    Start-Service -Name WebClient -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 500
    $svc = Get-Service -Name WebClient -ErrorAction SilentlyContinue
}
if (-not $svc -or $svc.Status -ne 'Running') {
    Write-Host "      [AVISO] O servico WebClient nao esta rodando; o Windows nao monta WebDAV sem ele." -ForegroundColor Yellow
    Write-Host "      Rode como administrador: Start-Service WebClient" -ForegroundColor Yellow
    if (-not $Quiet) { Write-Host "      Sem WebClient o erro 67 e inevitavel." -ForegroundColor DarkGray }
}

# ---------------------------------------------------------------- ponte
if (-not $Quiet) { Write-Host "[2/4] Abrindo o tunel local..." -ForegroundColor Yellow }
$target = $null
$bridgePort = $null
$usedBridge = $false

if (-not $Direct) {
    Stop-Bridge
    if (-not (Test-Path $bridgeScript)) {
        Write-Host "      Ponte nao encontrada ($bridgeScript); tentando net use direto." -ForegroundColor Yellow
    } else {
        Remove-Item $portFile, $pidFile -Force -ErrorAction SilentlyContinue
        $argList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $bridgeScript,
                     '-Url', $upstream, '-Key', $auth,
                     '-PortFile', $portFile, '-PidFile', $pidFile, '-LogFile', $logFile, '-Quiet')
        if ($NoProxy) { $argList += '-NoProxy' }
        if (-not $Verify) { $argList += '-Insecure' }
        $quoted = ($argList | ForEach-Object { '"' + ($_ -replace '"', '\"') + '"' }) -join ' '
        $bproc = Start-Process -FilePath 'powershell.exe' -ArgumentList $quoted -WindowStyle Hidden -PassThru
        for ($i = 0; $i -lt 60; $i++) {
            if (Test-Path $portFile) { break }
            if ($bproc.HasExited) { break }
            Start-Sleep -Milliseconds 250
        }
        if (Test-Path $portFile) {
            $bridgePort = (Get-Content $portFile -Raw).Trim()
            $target = "http://127.0.0.1:" + $bridgePort + "/dav/"
            $usedBridge = $true
            if (-not $Quiet) { Write-Host "      Ponte pronta em $target" -ForegroundColor Green }
        } else {
            Write-Host "      Nao consegui abrir a ponte local." -ForegroundColor Yellow
            Show-BridgeLog
            if (-not $bproc.HasExited) { Stop-Process -Id $bproc.Id -Force -ErrorAction SilentlyContinue }
        }
    }
}
if (-not $target) { $target = $upstream + '/dav/' }

# ---------------------------------------------------------------- teste
if (-not $Quiet) { Write-Host "[3/4] Testando $target ..." -ForegroundColor Cyan }
$reachable = $false
try {
    $resp = Invoke-WebRequest -Uri $target -Method Options -UseBasicParsing -TimeoutSec 12 -ErrorAction Stop
    $reachable = $true
    Write-Host "      Respondeu HTTP $($resp.StatusCode)." -ForegroundColor Green
} catch {
    $code = $null
    try { if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode } } catch { }
    if ($code -eq 401 -or $code -eq 403) {
        $reachable = $true
        Write-Host "      Respondeu HTTP $code (pede login) - o Windows autentica." -ForegroundColor Green
    } else {
        Write-Host "      Sem resposta: $($_.Exception.Message)" -ForegroundColor Yellow
        if ($usedBridge) { Show-BridgeLog }
    }
}
if (-not $reachable -and $usedBridge) {
    # A ponte pode ter subido mas o celular/tunel estar fora do ar.
    Write-Host "      Verifique: app aberto, WebDAV ligado, celular na rede e URL do Acesso Remoto." -ForegroundColor Yellow
}

# ---------------------------------------------------------------- montar
if (-not $Quiet) { Write-Host "[4/4] Montando $Drive em $target ..." -ForegroundColor Cyan }

function Mount-Drive([string]$path, [bool]$withCreds) {
    net use $Drive /delete /y 2>$null | Out-Null
    if ($withCreds) {
        net use $Drive $path /user:$User $auth /persistent:no 2>&1 | Out-Null
    } else {
        net use $Drive $path /persistent:no 2>&1 | Out-Null
    }
    return $LASTEXITCODE
}

$rc = Mount-Drive $target $false
if ($rc -ne 0 -and $usedBridge) {
    # Alguns Windows exigem credenciais mesmo com a ponte injetando o login.
    $rc = Mount-Drive $target $true
}
if ($rc -ne 0 -and $usedBridge) {
    Write-Host "      A ponte nao funcionou; tentando o caminho direto..." -ForegroundColor Yellow
    Stop-Bridge
    $usedBridge = $false
    $target = $upstream + '/dav/'
    $rc = Mount-Drive $target $true
}

if ($rc -eq 0) {
    if ($usedBridge) {
        try {
            $st = @{ drive = $Drive; port = $bridgePort; pid = [int]((Get-Content $pidFile -Raw).Trim()); upstream = $upstream; url = $target }
            $st | ConvertTo-Json | Set-Content -LiteralPath $stateFile -Encoding UTF8
        } catch { }
        $how = "ponte local (sem certificado, sem admin)"
    } else {
        Remove-Item $stateFile -Force -ErrorAction SilentlyContinue
        $how = "net use direto"
    }
    Write-Host ""
    Write-Host "[SUCESSO] $Drive montada via $how." -ForegroundColor Green
    if (-not $Quiet) {
        Write-Host "Para desmontar depois: .\map-drive.ps1 -Unmount" -ForegroundColor Gray
        Start-Process explorer.exe $Drive
    }
    exit 0
}

# ---------------------------------------------------------------- diagnostico
Write-Host ""
Write-Host "[FALHA] net use retornou o codigo $rc." -ForegroundColor Red
switch ($rc) {
    67   { Write-Host "  67 = 'Rede nao encontrada' (o Windows nao falou WebDAV com o endereco)." -ForegroundColor White
           Write-Host "  A ponte local deveria evitar isso. Causas restantes: WebClient parado," -ForegroundColor Gray
           Write-Host "  firewall/proxy bloqueando a saida HTTPS, ou o celular/tunel fora do ar." -ForegroundColor Gray }
    53   { Write-Host "  53 = caminho nao encontrado. Confira a URL e o sufixo /dav/." -ForegroundColor White }
    64   { Write-Host "  64 = nome de rede indisponivel. Desligue VPNs/proxies e tente de novo." -ForegroundColor White }
    1219 { Write-Host "  1219 = credenciais conflitantes para esse servidor." -ForegroundColor White
           Write-Host "  Rode: net use * /delete /y" -ForegroundColor Gray }
    1231 { Write-Host "  1231 = rede inacessivel (firewall/rota)." -ForegroundColor White }
    1326 { Write-Host "  1326 = usuario/senha incorretos. Use a API key atual ou o PIN." -ForegroundColor White }
    default { Write-Host "  Consulte a secao 'Erro 67' do README." -ForegroundColor Gray }
}

$hostName = $null
$portNum = 443
try {
    $u = [Uri]$upstream
    $hostName = $u.Host
    if ($u.Port -gt 0) { $portNum = $u.Port }
} catch { }
Write-Host ""
Write-Host "Diagnostico:" -ForegroundColor Cyan
Write-Host ("  WebClient: " + $(if ($svc) { $svc.Status } else { 'ausente' }))
if ($hostName) {
    $tcp = Test-NetConnection -ComputerName $hostName -Port $portNum -WarningAction SilentlyContinue -ErrorAction SilentlyContinue
    Write-Host ("  TCP " + $hostName + ":" + $portNum + " = " + $(if ($tcp.TcpTestSucceeded) { 'OK' } else { 'FALHOU' }))
}
try {
    $proxy = [System.Net.WebRequest]::DefaultWebProxy
    if ($proxy) {
        $pu = $proxy.GetProxy([Uri]$upstream)
        Write-Host ("  Proxy do sistema: " + $pu)
    }
} catch { }
if ($usedBridge) { Show-BridgeLog }

Write-Host ""
Write-Host "O que tentar:" -ForegroundColor Yellow
Write-Host "1. Use a URL HTTPS do Acesso Remoto (Cloudflare) e a API key atual."
Write-Host "2. Rode este script como administrador (o WebClient precisa iniciar)."
Write-Host "3. Se a rede tem proxy, tente: .\map-drive.ps1 ... -NoProxy"
Write-Host "4. Para testar sem a ponte: .\map-drive.ps1 ... -Direct"
Write-Host "5. Limpe conexoes antigas: net use * /delete /y"
exit 1
