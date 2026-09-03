<#
模块：流媒体协议组 / 设备注册检查。
职责：读取 WVP 设备目录并展示 SIP 目标，供实体相机或软件模拟器验收使用。
#>
[CmdletBinding()]
param(
    [string]$WvpBaseUrl = 'http://127.0.0.1:18080',
    [int]$Count = 100,
    [string]$WslDistro = 'Ubuntu-22.04'
)

$ErrorActionPreference = 'Stop'

if ($Count -lt 1 -or $Count -gt 1000) {
    throw 'Count must be between 1 and 1000.'
}

$baseUrl = $WvpBaseUrl.TrimEnd('/')
$endpoint = "$baseUrl/api/device/query/devices?page=1&count=$Count"

try {
    $response = Invoke-RestMethod -Uri $endpoint -Method Get -TimeoutSec 10
}
catch {
    Write-Error "Cannot query WVP device list at $endpoint. $_"
    exit 1
}

if ($response.PSObject.Properties['code']) {
    if ($response.code -ne 0 -and $response.code -ne 200) {
        Write-Error "WVP returned code $($response.code): $($response.msg)"
        exit 1
    }
    $page = $response.data
}
else {
    $page = $response
}

if (-not $page -or -not $page.PSObject.Properties['list']) {
    Write-Error 'WVP response does not contain a paged device list.'
    exit 1
}

if (-not $PSBoundParameters.ContainsKey('WslDistro') -and $env:EASYSVA_WSL_DISTRO) {
    $WslDistro = $env:EASYSVA_WSL_DISTRO
}

$hostIp = (& wsl.exe -d $WslDistro -- bash -lc "ip route get 1.1.1.1 | sed -n 's/.* src \([^ ]*\).*/\1/p' | head -n1").Trim()
if ($LASTEXITCODE -ne 0 -or -not $hostIp) {
    throw "Cannot determine the GB28181 host IP from WSL distribution '$WslDistro'."
}
$devices = @($page.list)
$total = if ($page.PSObject.Properties['total']) { [int]$page.total } else { $devices.Count }

Write-Host "WVP endpoint : $endpoint"
Write-Host "SIP target   : ${hostIp}:5060"
Write-Host "Device count : $total"

if ($devices.Count -eq 0) {
    Write-Host 'WAITING: no GB28181 IPC is registered yet.' -ForegroundColor Yellow
    exit 0
}

$devices |
    Select-Object @{ Name = 'DeviceId'; Expression = { $_.deviceId } },
                  @{ Name = 'Name'; Expression = { $_.name } },
                  @{ Name = 'Online'; Expression = { $_.onLine } },
                  @{ Name = 'IP'; Expression = { $_.ip } },
                  @{ Name = 'Port'; Expression = { $_.port } },
                  @{ Name = 'Manufacturer'; Expression = { $_.manufacturer } } |
    Format-Table -AutoSize

if ($total -gt $devices.Count) {
    Write-Host "Only the first $Count devices are displayed." -ForegroundColor Yellow
}
