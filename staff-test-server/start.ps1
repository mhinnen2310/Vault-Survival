[CmdletBinding()]
param(
    [string[]] $Staff = @(),
    [ValidateRange(1, 65535)]
    [int] $Port = 25566,
    [ValidateRange(1, 64)]
    [int] $MemoryGb = 4,
    [string] $BindAddress = "",
    [string] $ProductionHost = "127.0.0.1",
    [ValidateRange(1, 65535)]
    [int] $ProductionPort = 25565,
    [switch] $ProvisionOnly
)

$ErrorActionPreference = "Stop"
$projectDir = Split-Path -Parent $PSScriptRoot
$sourceRoot = Split-Path -Parent $projectDir
$runtimeDir = Join-Path $PSScriptRoot "runtime"
$paperSource = Join-Path $sourceRoot "paper-26.1.2-72.jar"
$pluginSource = Join-Path $projectDir "target\VaultSurvival.jar"

if (-not (Test-Path -LiteralPath $paperSource)) {
    throw "Paper jar not found: $paperSource"
}
if (-not (Test-Path -LiteralPath $pluginSource)) {
    throw "Plugin jar not found: $pluginSource. Run .\mvnw.cmd clean package first."
}

$profiles = @()
if ($Staff.Count -gt 0) {
    $profiles = foreach ($entry in $Staff) {
        $parts = $entry.Split(':', 2)
        if ($parts.Count -ne 2 -or [string]::IsNullOrWhiteSpace($parts[1])) {
            throw "Invalid staff entry '$entry'. Use UUID:MinecraftName."
        }
        try { $uuid = ([Guid]$parts[0]).ToString() }
        catch { throw "Invalid UUID in staff entry '$entry'." }
        [pscustomobject]@{ uuid = $uuid; name = $parts[1].Trim() }
    }
} else {
    $savedWhitelist = Join-Path $runtimeDir "whitelist.json"
    if (Test-Path -LiteralPath $savedWhitelist) {
        $profiles = @(Get-Content -Raw -LiteralPath $savedWhitelist | ConvertFrom-Json)
    }
}
if (@($profiles).Count -eq 0) {
    throw "At least one staff UUID is required on first provision. Use -Staff UUID:MinecraftName."
}

New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $runtimeDir "plugins") | Out-Null
Copy-Item -Force -LiteralPath $paperSource -Destination (Join-Path $runtimeDir "paper.jar")
Copy-Item -Force -LiteralPath $pluginSource -Destination (Join-Path $runtimeDir "plugins\VaultSurvival.jar")
Copy-Item -Force -LiteralPath (Join-Path $PSScriptRoot "eula.txt") -Destination (Join-Path $runtimeDir "eula.txt")

$properties = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot "server.properties")
$properties = $properties -replace '(?m)^server-port=.*$', "server-port=$Port"
$properties = $properties -replace '(?m)^server-ip=.*$', "server-ip=$BindAddress"
Set-Content -Encoding UTF8 -LiteralPath (Join-Path $runtimeDir "server.properties") -Value $properties

$whitelist = @($profiles | ForEach-Object { [pscustomobject]@{ uuid = $_.uuid; name = $_.name } })
$operators = @($profiles | ForEach-Object {
    [pscustomobject]@{ uuid = $_.uuid; name = $_.name; level = 4; bypassesPlayerLimit = $false }
})
ConvertTo-Json -Depth 3 -InputObject $whitelist | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $runtimeDir "whitelist.json")
ConvertTo-Json -Depth 3 -InputObject $operators | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $runtimeDir "ops.json")

$allowedUuids = ($profiles.uuid -join ',')
Write-Host "Staff sandbox provisioned at: $runtimeDir"
Write-Host "World: staff_test | Port: $Port | Database: plugins\VaultSurvival\staff_sandbox.db"
Write-Host "Allowed staff profiles: $(@($profiles).Count)"

if ($ProvisionOnly) { return }

Push-Location $runtimeDir
try {
    $javaArgs = @(
        "-Xms${MemoryGb}G",
        "-Xmx${MemoryGb}G",
        "-Dvaultsurvival.staffSandbox=true",
        "-Dvaultsurvival.staffSandbox.world=staff_test",
        "-Dvaultsurvival.staffSandbox.allowedUuids=$allowedUuids",
        "-Dvaultsurvival.staffSandbox.productionHost=$ProductionHost",
        "-Dvaultsurvival.staffSandbox.productionPort=$ProductionPort",
        "-jar",
        "paper.jar",
        "--nogui"
    )
    & java @javaArgs
    if ($LASTEXITCODE -ne 0) { throw "Paper exited with code $LASTEXITCODE." }
}
finally {
    Pop-Location
}
