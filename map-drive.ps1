# NexShare - mapeia o armazenamento do celular como unidade de rede no Windows (WebDAV).
#
# Uso:
#   .\map-drive.ps1 -PhoneIP 192.168.1.100 -Pin 123456 -Drive Z:
#   .\map-drive.ps1 -Url https://algum-nome.trycloudflare.com/dav/ -Pin 123456
#   .\map-drive.ps1 -Url https://192.168.1.100:8443/dav/ -Pin 123456
#
# Dica: em redes de faculdade/empresa (que bloqueiam portas e o WebDAV sem SSL),
# ligue o "Acesso Remoto" no app e use -Url com o endereco publico HTTPS.
param(
    [string]$PhoneIP,
    [string]$Url,
    [string]$Pin,
    [string]$User = 'nexshare',
    [string]$Drive = 'Z:'
)

$ErrorActionPreference = 'Continue'

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "        NexShare - Mapeamento de Unidade de Rede          " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# Monta a URL: o parametro -Url tem prioridade sobre -PhoneIP
if (-not $Url) {
    if (-not $PhoneIP) {
        $PhoneIP = Read-Host "IP do celular exibido no app NexShare (ex: 192.168.1.100)"
    }
    $PhoneIP = ($PhoneIP -as [string]).Trim()
    if (-not $PhoneIP) {
        Write-Host "O IP do celular e obrigatorio (ou use -Url)." -ForegroundColor Red
        exit 1
    }
    $Url = "http://${PhoneIP}:8080/dav/"
}
$Url = $Url.Trim()
if ($Url -notmatch '^https?://') { $Url = "http://$Url" }
if ($Url -notmatch '/dav/?$') { $Url = $Url.TrimEnd('/') + '/dav/' }
$secure = $Url.StartsWith('https://')

if (-not $Pin) {
    $Pin = Read-Host "PIN de 6 digitos do NexShare"
}
$Pin = ($Pin -as [string]).Trim()
if (-not $Pin) {
    Write-Host "O PIN e obrigatorio." -ForegroundColor Red
    exit 1
}

if (-not $Drive.EndsWith(':')) { $Drive = "$Drive`:" }

# Se a letra pedida estiver ocupada (ex: RaiDrive/OneDrive), escolhe outra livre
$requestedLetter = $Drive.Substring(0, 1).ToUpper()
$usedDrives = (Get-PSDrive -PSProvider FileSystem).Name
if ($usedDrives -contains $requestedLetter) {
    Write-Host "Aviso: $Drive ja esta em uso." -ForegroundColor Yellow
    $freeLetter = [char[]]([char]'D'..[char]'Z') | Where-Object { $usedDrives -notcontains [string]$_ } | Select-Object -First 1
    if ($freeLetter) {
        $Drive = "$freeLetter`:"
        Write-Host "Usando a unidade livre: $Drive" -ForegroundColor Cyan
    }
}

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

Write-Host "[1/3] Ajustando o cliente WebDAV do Windows..." -ForegroundColor Yellow
if ($isAdmin) {
    $regPath = 'HKLM:\SYSTEM\CurrentControlSet\Services\WebClient\Parameters'
    # 2 = Basic Auth tambem em HTTP. Com HTTPS o Windows ja aceita por padrao.
    Set-ItemProperty -Path $regPath -Name 'BasicAuthLevel' -Value 2 -Type DWord
    # Limite de 4 GB por arquivo do WebClient nativo.
    Set-ItemProperty -Path $regPath -Name 'FileSizeLimitInBytes' -Value ([uint32]::MaxValue) -Type DWord
    Set-Service -Name WebClient -StartupType Automatic -ErrorAction SilentlyContinue
    Restart-Service -Name WebClient -Force -ErrorAction SilentlyContinue
    Write-Host "      Registro e servico WebClient atualizados." -ForegroundColor Green
} else {
    Set-Service -Name WebClient -StartupType Manual -ErrorAction SilentlyContinue
    Start-Service -Name WebClient -ErrorAction SilentlyContinue
    if ($secure) {
        Write-Host "      Sem privilegios de administrador - ok, HTTPS nao precisa de ajustes no registro." -ForegroundColor Green
    } else {
        Write-Host "      [AVISO] Sem administrador: o Windows costuma recusar WebDAV HTTP sem SSL" -ForegroundColor Yellow
        Write-Host "      (erro 67). Prefira a URL HTTPS do Acesso Remoto ou rode como administrador." -ForegroundColor Yellow
    }
}

Write-Host "[2/3] Testando $Url ..." -ForegroundColor Cyan
$reachable = $false
try {
    # OPTIONS e o primeiro pedido que o Windows faz; 401 significa "chegou no servidor".
    $resp = Invoke-WebRequest -Uri $Url -Method Options -UseBasicParsing -TimeoutSec 8 -ErrorAction Stop
    $reachable = $true
    Write-Host "      Servidor respondeu HTTP $($resp.StatusCode)." -ForegroundColor Green
} catch {
    $code = $null
    if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
    if ($code -eq 401 -or $code -eq 403) {
        $reachable = $true
        Write-Host "      Servidor respondeu HTTP $code (pede login) - ok." -ForegroundColor Green
    } else {
        Write-Host "      Falhou: $($_.Exception.Message)" -ForegroundColor Red
        Write-Host "      Verifique: celular ligado, servidor rodando, WebDAV ativado e mesma rede." -ForegroundColor Yellow
    }
}
if (-not $reachable) {
    Write-Host ""
    Write-Host "Continuando mesmo assim (o Windows pode alcancar por outro caminho)..." -ForegroundColor Yellow
}

Write-Host "[3/3] Conectando $Drive em $Url ..." -ForegroundColor Cyan
net use $Drive /delete /y 2>$null | Out-Null
Write-Host "> net use $Drive $Url /user:$User *** /persistent:no" -ForegroundColor Gray
net use $Drive $Url /user:$User $Pin /persistent:no
$rc = $LASTEXITCODE

if ($rc -eq 0) {
    Write-Host ""
    Write-Host "[SUCESSO] $Drive mapeada para o NexShare!" -ForegroundColor Green
    Start-Process explorer.exe $Drive
    exit 0
}

Write-Host ""
Write-Host "[FALHA] net use retornou o codigo $rc." -ForegroundColor Red
switch ($rc) {
    67  { Write-Host "  67 = 'Rede nao encontrada'. O Windows nao conseguiu falar WebDAV com esse endereco." -ForegroundColor White
          Write-Host "  Causas comuns: porta bloqueada pela rede, WebDAV HTTP sem SSL (o Windows recusa)," -ForegroundColor Gray
          Write-Host "  ou certificado nao confiavel. Use a URL publica HTTPS do Acesso Remoto." -ForegroundColor Gray }
    53  { Write-Host "  53 = caminho nao encontrado. Confira o IP e o /dav/ no final da URL." -ForegroundColor White }
    64  { Write-Host "  64 = nome de rede nao disponivel. Desconecte VPNs e tente de novo." -ForegroundColor White }
    1219 { Write-Host "  1219 = ja existem credenciais conflitantes para esse servidor." -ForegroundColor White
          Write-Host "  Rode: net use * /delete /y" -ForegroundColor Gray }
    1231 { Write-Host "  1231 = a rede nao esta acessivel (firewall/rota)." -ForegroundColor White }
    1326 { Write-Host "  1326 = usuario ou senha incorretos. Use /user:nexshare e o PIN." -ForegroundColor White }
    default { Write-Host "  Consulte a secao 'Erro 67' do README." -ForegroundColor Gray }
}
Write-Host ""
Write-Host "Alternativa pelo Explorador (Win + R):" -ForegroundColor Yellow
if ($secure) {
    Write-Host "   $Url" -ForegroundColor White
} else {
    $hostPart = ([Uri]$Url).Host
    Write-Host "   \\$hostPart@8080\DavWWWRoot\dav" -ForegroundColor White
    Write-Host "   $Url" -ForegroundColor White
}
Write-Host ""
Write-Host "Se o problema persistir:" -ForegroundColor Yellow
Write-Host "1. No app: WebDAV ativado e 'Acesso Remoto' ligado (gera URL HTTPS publica)."
Write-Host "2. Use a URL HTTPS: .\map-drive.ps1 -Url https://xxxx.trycloudflare.com/dav/ -Pin <PIN>"
Write-Host "3. No Windows: net use * /delete /y  (limpa credenciais antigas) e tente novamente."
exit 1
