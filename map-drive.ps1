# Mapeia o armazenamento do celular com NexShare como unidade de rede no Windows via WebDAV.
# Uso: .\map-drive.ps1 [-PhoneIP <IP>] [-Pin <PIN>] [-Drive <Letra>]
param(
    [string]$PhoneIP,
    [string]$Pin,
    [string]$Drive = 'Z:'
)

$ErrorActionPreference = 'Continue'

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "        NexShare - Mapeamento de Unidade de Rede          " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# Verifica privilégios de Administrador para ajustar o serviço WebClient e o Registro
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if ($isAdmin) {
    Write-Host "[1/3] Configurando parametros do Windows WebClient no Registro..." -ForegroundColor Yellow
    $regPath = "HKLM:\SYSTEM\CurrentControlSet\Services\WebClient\Parameters"
    
    # BasicAuthLevel = 2: permite Basic Auth em HTTP sem SSL
    Set-ItemProperty -Path $regPath -Name "BasicAuthLevel" -Value 2 -Type DWord
    # FileSizeLimitInBytes = 0xffffffff: aumenta o limite maximo de arquivo para 4 GB
    Set-ItemProperty -Path $regPath -Name "FileSizeLimitInBytes" -Value ([uint32]::MaxValue) -Type DWord

    Write-Host "[2/3] Reiniciando servico WebClient do Windows..." -ForegroundColor Yellow
    Set-Service -Name WebClient -StartupType Automatic
    Restart-Service -Name WebClient -Force
} else {
    Write-Host "[AVISO] Nao executado como Administrador." -ForegroundColor Yellow
    Write-Host "Se esta e a primeira vez, execute o PowerShell como Administrador ou aplique 'windows-webdav.reg'." -ForegroundColor Gray
}

# Solicita IP se nao informado
if (-not $PhoneIP) {
    $PhoneIP = Read-Host "Digite o IP do celular exibido no app NexShare (ex: 192.168.1.100)"
}
$PhoneIP = $PhoneIP.Trim()
if (-not $PhoneIP) {
    Write-Error "O endereco IP do celular e obrigatorio."
    exit 1
}

# Solicita PIN se nao informado
if (-not $Pin) {
    $Pin = Read-Host "Digite o PIN de 6 digitos do NexShare"
}
$Pin = $Pin.Trim()
if (-not $Pin) {
    Write-Error "O PIN e obrigatorio."
    exit 1
}

if (-not $Drive.EndsWith(":")) {
    $Drive = "$Drive`:"
}

$webdavUrl = "http://${PhoneIP}:8080/dav/"
Write-Host "[3/3] Conectando unidade $Drive a $webdavUrl ..." -ForegroundColor Cyan

# Desconecta unidade antiga se ja existir
try {
    net use $Drive /delete /y 2>$null | Out-Null
} catch { }

# Executa net use para mapear a unidade
$netUseCmd = "net use $Drive $webdavUrl /user:nexshare $Pin /persistent:no"
Write-Host "> $netUseCmd" -ForegroundColor Gray
cmd.exe /c $netUseCmd

if ($LASTEXITCODE -eq 0) {
    Write-Host "`n[SUCESSO] Unidade $Drive mapeada com sucesso para o NexShare ($PhoneIP)!" -ForegroundColor Green
    Write-Host "Abrindo Explorador de Arquivos..." -ForegroundColor Green
    Start-Process explorer.exe $Drive
} else {
    Write-Host "`n[FALHA] Nao foi possivel mapear com net use." -ForegroundColor Red
    Write-Host "`nAlternativa UNC no Windows Explorer / Executar (Win + R):" -ForegroundColor Yellow
    Write-Host "   \\${PhoneIP}@8080\DavWWWRoot\dav" -ForegroundColor White
    Write-Host "`nVerificacoes importantes:" -ForegroundColor Yellow
    Write-Host "1. O interruptor 'WebDAV' deve estar ativado no app NexShare."
    Write-Host "2. O PC e o celular devem estar conectados a mesma rede Wi-Fi / Hotspot."
    Write-Host "3. O Windows WebClient precisa que 'BasicAuthLevel' seja 2 (execute windows-webdav.reg como admin e reinicie o servico WebClient)."
    Write-Host "4. O WebClient do Windows nao resolve nomes '.local' de forma confiavel, use sempre o IP do celular."
}
