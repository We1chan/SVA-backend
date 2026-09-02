#Requires -RunAsAdministrator
$ErrorActionPreference = 'Stop'

$vmCreatorId = '{40E0AC32-46A5-438A-A0B2-2B479E8F2E90}'
$rules = @(
    @{ Name = 'easySVA-GB28181-SIP-TCP'; Display = 'easySVA GB28181 SIP TCP'; Protocol = 'TCP'; Ports = '5060' },
    @{ Name = 'easySVA-GB28181-SIP-UDP'; Display = 'easySVA GB28181 SIP UDP'; Protocol = 'UDP'; Ports = '5060' },
    @{ Name = 'easySVA-GB28181-PREVIEW-TCP'; Display = 'easySVA GB28181 WebSocket preview'; Protocol = 'TCP'; Ports = '9996' },
    @{ Name = 'easySVA-GB28181-RTP-SINGLE-UDP'; Display = 'easySVA GB28181 RTP single port'; Protocol = 'UDP'; Ports = '10000' },
    @{ Name = 'easySVA-GB28181-RTP-TCP'; Display = 'easySVA GB28181 RTP TCP'; Protocol = 'TCP'; Ports = '40002-45000' },
    @{ Name = 'easySVA-GB28181-RTP-UDP'; Display = 'easySVA GB28181 RTP UDP'; Protocol = 'UDP'; Ports = '40002-45000' },
    @{ Name = 'easySVA-GB28181-RTSP-TCP'; Display = 'easySVA GB28181 RTSP TCP'; Protocol = 'TCP'; Ports = '9997' }
)

foreach ($rule in $rules) {
    if (-not (Get-NetFirewallRule -Name $rule.Name -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule `
            -Name $rule.Name `
            -DisplayName $rule.Display `
            -Direction Inbound `
            -Action Allow `
            -Profile Any `
            -Protocol $rule.Protocol `
            -LocalPort $rule.Ports `
            -RemoteAddress LocalSubnet | Out-Null
    }

    if (Get-Command New-NetFirewallHyperVRule -ErrorAction SilentlyContinue) {
        $hyperVRuleName = "$($rule.Name)-HyperV"
        if (-not (Get-NetFirewallHyperVRule -Name $hyperVRuleName -PolicyStore ActiveStore -ErrorAction SilentlyContinue)) {
            New-NetFirewallHyperVRule `
                -Name $hyperVRuleName `
                -DisplayName "$($rule.Display) (WSL Hyper-V)" `
                -PolicyStore ActiveStore `
                -VMCreatorId $vmCreatorId `
                -Direction Inbound `
                -Action Allow `
                -Enabled True `
                -Protocol $rule.Protocol `
                -LocalPorts $rule.Ports `
                -RemoteAddresses LocalSubnet | Out-Null
        }
    }
}

Write-Host 'GB28181 SIP, RTP, and preview ports are open to the local subnet.'
