$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    Write-Host "Checking local PostgreSQL on localhost:5432..."
    $pgReady = $false
    $pgIsReady = Get-Command pg_isready -ErrorAction SilentlyContinue
    if ($pgIsReady) {
        & $pgIsReady.Source -h 127.0.0.1 -p 5432 *> $null
        $pgReady = ($LASTEXITCODE -eq 0)
    } else {
        $client = New-Object System.Net.Sockets.TcpClient
        try {
            $async = $client.BeginConnect("127.0.0.1", 5432, $null, $null)
            if ($async.AsyncWaitHandle.WaitOne(2000, $false) -and $client.Connected) {
                $pgReady = $true
            }
        } finally {
            $client.Dispose()
        }
    }

    if (-not $pgReady) {
        throw "Local PostgreSQL is not reachable on 127.0.0.1:5432. Start your PostgreSQL 18 service and retry."
    }

    $envFile = Join-Path $root ".env"
    if (Test-Path $envFile) {
        Write-Host "Loading environment variables from .env..."
        Get-Content $envFile | ForEach-Object {
            $line = $_.Trim()
            if ($line -and -not $line.StartsWith("#") -and $line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$') {
                $key = $matches[1].Trim().ToUpperInvariant()
                $val = $matches[2].Trim()
                if (($val.StartsWith('"') -and $val.EndsWith('"')) -or ($val.StartsWith("'") -and $val.EndsWith("'"))) {
                    if ($val.Length -ge 2) {
                        $val = $val.Substring(1, $val.Length - 2)
                    }
                }
                [System.Environment]::SetEnvironmentVariable($key, $val, "Process")
            }
        }
    }

    Write-Host "Starting LawRAG with the local profile on http://localhost:8090 ..."
    & (Join-Path $root "mvnw.cmd") spring-boot:run "-Dspring-boot.run.profiles=local" "-Dspring-boot.run.arguments=--server.port=8090"
} finally {
    Pop-Location
}
