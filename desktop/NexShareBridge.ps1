# NexShare - ponte WebDAV local (HTTP -> HTTPS) para mapear a unidade no Windows.
#
# Por que existe: o cliente WebDAV do Windows (MiniRedir/WebClient) costuma falhar
# com "erro 67 - rede nao encontrada" em HTTPS com certificado proprio, atras de
# proxy de instituicao ou quando a URL tem barra/porta. Esta ponte escuta em
# 127.0.0.1 (sem exigir administrador), fala HTTPS com o celular usando o proxy do
# sistema e injeta o login, entao o Windows so precisa falar HTTP com o localhost.
#
# Uso:
#   .\NexShareBridge.ps1 -Url https://xxxx.trycloudflare.com -Key <API key ou PIN>
#   .\NexShareBridge.ps1 -Url https://192.168.1.100:8443 -Key <...> -Port 8099
#
# Normalmente voce nao chama este script direto: o map-drive.ps1 inicia e para a
# ponte sozinho. Rode com -Quiet -PortFile/-PidFile para uso automatizado.
param(
    [Parameter(Mandatory = $true)][string]$Url,
    [Parameter(Mandatory = $true)][string]$Key,
    [int]$Port = 0,
    [string]$Listen = '127.0.0.1',
    [string]$User = 'nexshare',
    [switch]$Insecure,
    [switch]$NoProxy,
    [switch]$Quiet,
    [string]$PortFile,
    [string]$PidFile,
    [string]$LogFile
)

$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Net.Http

if (-not ('NexShareBridge' -as [type])) {
    $csharp = @'
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Net.Sockets;
using System.Text;
using System.Threading;

public sealed class NexShareBridge
{
    private readonly string upstream;
    private readonly string auth;
    private readonly TcpListener listener;
    private readonly HttpClient client;
    private volatile bool running;
    private Thread accept;

    public string LogFile;

    public NexShareBridge(string listen, int port, string upstream, string user, string pass, bool insecure, bool noProxy)
    {
        this.upstream = upstream.TrimEnd('/');
        this.auth = "Basic " + Convert.ToBase64String(Encoding.UTF8.GetBytes(user + ":" + pass));
        HttpClientHandler handler = new HttpClientHandler();
        handler.AllowAutoRedirect = false;
        handler.UseCookies = false;
        handler.UseProxy = !noProxy;
        if (insecure)
        {
            handler.ServerCertificateCustomValidationCallback = delegate { return true; };
        }
        client = new HttpClient(handler);
        client.Timeout = TimeSpan.FromMinutes(30);
        listener = new TcpListener(IPAddress.Parse(listen), port);
    }

    public int Port
    {
        get { return ((IPEndPoint)listener.LocalEndpoint).Port; }
    }

    public void Start()
    {
        listener.Start(50);
        running = true;
        accept = new Thread(AcceptLoop);
        accept.IsBackground = true;
        accept.Start();
    }

    public void Stop()
    {
        running = false;
        try { listener.Stop(); } catch { }
        try { client.Dispose(); } catch { }
    }

    private void AcceptLoop()
    {
        while (running)
        {
            TcpClient c;
            try { c = listener.AcceptTcpClient(); }
            catch { return; }
            ThreadPool.QueueUserWorkItem(Handle, c);
        }
    }

    private void Handle(object state)
    {
        TcpClient c = (TcpClient)state;
        try
        {
            c.NoDelay = true;
            using (c)
            using (NetworkStream net = c.GetStream())
            {
                try { HandleOne(net); }
                catch (Exception ex) { TryFail(net, ex); }
            }
        }
        catch { }
    }

    private void HandleOne(NetworkStream net)
    {
        BufferedStream input = new BufferedStream(net, 16384);
        string requestLine = ReadLine(input);
        if (string.IsNullOrEmpty(requestLine)) return;
        string[] parts = requestLine.Split(' ');
        if (parts.Length < 3)
        {
            Write(net, "HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
            return;
        }

        string method = parts[0];
        string target = parts[1];
        List<string[]> headers = new List<string[]>();
        long length = -1;
        bool chunked = false;
        bool expect = false;
        string line;
        while (!string.IsNullOrEmpty(line = ReadLine(input)))
        {
            int colon = line.IndexOf(':');
            if (colon <= 0) continue;
            string name = line.Substring(0, colon).Trim();
            string value = line.Substring(colon + 1).Trim();
            headers.Add(new string[] { name, value });
            if (name.Equals("Content-Length", StringComparison.OrdinalIgnoreCase))
            {
                long.TryParse(value, out length);
            }
            else if (name.Equals("Transfer-Encoding", StringComparison.OrdinalIgnoreCase)
                     && value.IndexOf("chunked", StringComparison.OrdinalIgnoreCase) >= 0)
            {
                chunked = true;
            }
            else if (name.Equals("Expect", StringComparison.OrdinalIgnoreCase)
                     && value.IndexOf("100-continue", StringComparison.OrdinalIgnoreCase) >= 0)
            {
                expect = true;
            }
        }

        if (target.StartsWith("http://", StringComparison.OrdinalIgnoreCase)
            || target.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
        {
            try { target = new Uri(target).PathAndQuery; } catch { }
        }
        if (target == "*") target = "/";
        if (!target.StartsWith("/")) target = "/" + target;

        string url = upstream + target;
        HttpRequestMessage req = new HttpRequestMessage(new HttpMethod(method), url);

        Stream body = null;
        if (chunked) body = new ChunkedStream(input);
        else if (length > 0) body = new LimitedStream(input, length);
        if (body != null)
        {
            req.Content = new StreamContent(body);
            if (!chunked) req.Content.Headers.ContentLength = length;
        }

        if (expect) Write(net, "HTTP/1.1 100 Continue\r\n\r\n");

        foreach (string[] h in headers)
        {
            string name = h[0];
            string value = h[1];
            if (SkipRequest(name)) continue;
            if (name.Equals("Destination", StringComparison.OrdinalIgnoreCase))
            {
                if (value.StartsWith("http://", StringComparison.OrdinalIgnoreCase)
                    || value.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
                {
                    try { value = upstream + new Uri(value).PathAndQuery; } catch { }
                }
            }
            if (name.Equals("Content-Length", StringComparison.OrdinalIgnoreCase)) continue;
            if (!req.Headers.TryAddWithoutValidation(name, value) && req.Content != null)
            {
                req.Content.Headers.TryAddWithoutValidation(name, value);
            }
        }
        req.Headers.TryAddWithoutValidation("Authorization", auth);

        using (HttpResponseMessage resp = client.SendAsync(req, HttpCompletionOption.ResponseHeadersRead).GetAwaiter().GetResult())
        {
            StringBuilder sb = new StringBuilder(512);
            sb.Append("HTTP/1.1 ").Append((int)resp.StatusCode).Append(' ')
              .Append(string.IsNullOrEmpty(resp.ReasonPhrase) ? "OK" : resp.ReasonPhrase).Append("\r\n");
            AppendHeaders(sb, resp.Headers);
            AppendHeaders(sb, resp.Content.Headers);
            sb.Append("Connection: close\r\n\r\n");
            Write(net, sb.ToString());
            if (!method.Equals("HEAD", StringComparison.OrdinalIgnoreCase))
            {
                using (Stream rs = resp.Content.ReadAsStreamAsync().GetAwaiter().GetResult())
                {
                    rs.CopyTo(net, 65536);
                    net.Flush();
                }
            }
            Log(method + " " + target + " -> " + (int)resp.StatusCode);
        }
    }

    private static bool SkipRequest(string name)
    {
        switch (name.ToLowerInvariant())
        {
            case "host":
            case "connection":
            case "keep-alive":
            case "proxy-connection":
            case "proxy-authorization":
            case "transfer-encoding":
            case "content-length":
            case "expect":
            case "te":
            case "trailer":
            case "upgrade":
            case "authorization":
            case "accept-encoding":
                return true;
            default:
                return false;
        }
    }

    private static bool SkipResponse(string name)
    {
        switch (name.ToLowerInvariant())
        {
            case "connection":
            case "keep-alive":
            case "proxy-authenticate":
            case "transfer-encoding":
            case "trailer":
            case "upgrade":
            case "www-authenticate":
                return true;
            default:
                return false;
        }
    }

    private static void AppendHeaders(StringBuilder sb, HttpHeaders headers)
    {
        foreach (KeyValuePair<string, IEnumerable<string>> h in headers)
        {
            if (SkipResponse(h.Key)) continue;
            foreach (string v in h.Value)
            {
                sb.Append(h.Key).Append(": ").Append(v).Append("\r\n");
            }
        }
    }

    private void Log(string text)
    {
        if (LogFile == null) return;
        try
        {
            File.AppendAllText(LogFile, DateTime.Now.ToString("HH:mm:ss") + " " + text + Environment.NewLine);
        }
        catch { }
    }

    private static void TryFail(Stream s, Exception ex)
    {
        try
        {
            byte[] body = Encoding.UTF8.GetBytes("NexShare bridge error: " + ex.Message);
            string head = "HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: "
                + body.Length + "\r\nConnection: close\r\n\r\n";
            byte[] hb = Encoding.ASCII.GetBytes(head);
            s.Write(hb, 0, hb.Length);
            s.Write(body, 0, body.Length);
            s.Flush();
        }
        catch { }
    }

    private static void Write(Stream s, string text)
    {
        byte[] b = Encoding.UTF8.GetBytes(text);
        s.Write(b, 0, b.Length);
        s.Flush();
    }

    private static string ReadLine(Stream s)
    {
        StringBuilder sb = new StringBuilder(128);
        int b;
        while ((b = s.ReadByte()) >= 0)
        {
            if (b == 10) return sb.ToString();
            if (b != 13) sb.Append((char)b);
            if (sb.Length > 65536) throw new IOException("header too large");
        }
        return sb.Length == 0 ? null : sb.ToString();
    }

    private sealed class LimitedStream : Stream
    {
        private readonly Stream inner;
        private long left;

        public LimitedStream(Stream inner, long length)
        {
            this.inner = inner;
            left = length;
        }

        public override int Read(byte[] buffer, int offset, int count)
        {
            if (left <= 0) return 0;
            int n = inner.Read(buffer, offset, (int)Math.Min(count, left));
            if (n > 0) left -= n;
            return n;
        }

        public override bool CanRead { get { return true; } }
        public override bool CanSeek { get { return false; } }
        public override bool CanWrite { get { return false; } }
        public override long Length { get { throw new NotSupportedException(); } }
        public override long Position
        {
            get { throw new NotSupportedException(); }
            set { throw new NotSupportedException(); }
        }
        public override void Flush() { }
        public override long Seek(long offset, SeekOrigin origin) { throw new NotSupportedException(); }
        public override void SetLength(long value) { throw new NotSupportedException(); }
        public override void Write(byte[] buffer, int offset, int count) { throw new NotSupportedException(); }
    }

    private sealed class ChunkedStream : Stream
    {
        private readonly Stream inner;
        private long chunkLeft;
        private bool done;

        public ChunkedStream(Stream inner)
        {
            this.inner = inner;
        }

        private bool NextChunk()
        {
            if (done) return false;
            string line = ReadLine(inner);
            if (line == null) { done = true; return false; }
            int semi = line.IndexOf(';');
            if (semi >= 0) line = line.Substring(0, semi);
            long size;
            if (!long.TryParse(line.Trim(), NumberStyles.HexNumber, CultureInfo.InvariantCulture, out size))
            {
                done = true;
                return false;
            }
            if (size <= 0)
            {
                while (!string.IsNullOrEmpty(ReadLine(inner))) { }
                done = true;
                return false;
            }
            chunkLeft = size;
            return true;
        }

        public override int Read(byte[] buffer, int offset, int count)
        {
            if (chunkLeft <= 0 && !NextChunk()) return 0;
            int n = inner.Read(buffer, offset, (int)Math.Min(count, chunkLeft));
            if (n <= 0) { done = true; return 0; }
            chunkLeft -= n;
            if (chunkLeft == 0) ReadLine(inner);
            return n;
        }

        public override bool CanRead { get { return true; } }
        public override bool CanSeek { get { return false; } }
        public override bool CanWrite { get { return false; } }
        public override long Length { get { throw new NotSupportedException(); } }
        public override long Position
        {
            get { throw new NotSupportedException(); }
            set { throw new NotSupportedException(); }
        }
        public override void Flush() { }
        public override long Seek(long offset, SeekOrigin origin) { throw new NotSupportedException(); }
        public override void SetLength(long value) { throw new NotSupportedException(); }
        public override void Write(byte[] buffer, int offset, int count) { throw new NotSupportedException(); }
    }
}
'@
    Add-Type -TypeDefinition $csharp -ReferencedAssemblies 'System.Net.Http', 'System.Core', 'System.dll'
}

$Url = $Url.Trim().TrimEnd('/')
if ($Url -notmatch '^https?://') { throw "URL invalida: $Url" }
if ($Url -match '/dav$') { $Url = $Url.Substring(0, $Url.Length - 4) }
if (-not $Key) { throw 'Informe a API key (ou o PIN) do NexShare.' }

if ($LogFile) {
    try {
        $dir = Split-Path -Parent $LogFile
        if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
        if ((Test-Path $LogFile) -and (Get-Item $LogFile).Length -gt 1MB) { Remove-Item $LogFile -Force }
    } catch { }
}

$bridge = $null
$lastError = $null
$tryPort = $Port
for ($i = 0; $i -lt 12; $i++) {
    try {
        $bridge = New-Object NexShareBridge -ArgumentList $Listen, $tryPort, $Url, $User, $Key, $Insecure.IsPresent, $NoProxy.IsPresent
        $bridge.LogFile = $LogFile
        $bridge.Start()
        break
    } catch {
        $lastError = $_
        $bridge = $null
        if ($tryPort -eq 0) { throw }
        $tryPort++
    }
}
if (-not $bridge) { throw "Nao consegui abrir a porta local: $($lastError.Exception.Message)" }

$actual = $bridge.Port
$localUrl = "http://$Listen`:$actual"
if ($PortFile) { Set-Content -LiteralPath $PortFile -Value $actual -Encoding ASCII }
if ($PidFile) { Set-Content -LiteralPath $PidFile -Value $PID -Encoding ASCII }
if (-not $Quiet) {
    Write-Host ""
    Write-Host "  NexShare bridge" -ForegroundColor Cyan
    Write-Host "  $localUrl/dav/  ->  $Url/dav/" -ForegroundColor Gray
    Write-Host "  Proxy do sistema: $(if ($NoProxy) { 'desligado' } else { 'ligado' })" -ForegroundColor DarkGray
    Write-Host "  Pressione Ctrl+C para parar." -ForegroundColor DarkGray
    Write-Host ""
}

try {
    while ($true) { Start-Sleep -Seconds 1 }
} finally {
    try { $bridge.Stop() } catch { }
    if ($PortFile -and (Test-Path -LiteralPath $PortFile)) { Remove-Item -LiteralPath $PortFile -Force -ErrorAction SilentlyContinue }
    if ($PidFile -and (Test-Path -LiteralPath $PidFile)) { Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue }
}
