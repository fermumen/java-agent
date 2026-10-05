$ErrorActionPreference = 'Stop'

$repoRoot = $PSScriptRoot
# A downloaded distribution has the jars beside this script; a checkout has them in target.
$jarRoot = $repoRoot
if (-not (Test-Path -LiteralPath (Join-Path $jarRoot 'java-agent.jar') -PathType Leaf)) {
    $jarRoot = Join-Path $repoRoot 'target'
}
$sourceJar = Join-Path $jarRoot 'java-agent.jar'
if (-not (Test-Path -LiteralPath $sourceJar -PathType Leaf)) {
    [Console]::Error.WriteLine('java-agent: java-agent.jar was not found beside this script or in target. Build the project with Maven first.')
    exit 2
}
if ([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
    [Console]::Error.WriteLine('java-agent: LOCALAPPDATA is not set; cannot choose a per-user install folder.')
    exit 2
}

$installRoot = Join-Path $env:LOCALAPPDATA 'java-agent\bin'
New-Item -ItemType Directory -Path $installRoot -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $repoRoot 'java-agent.cmd') -Destination (Join-Path $installRoot 'java-agent.cmd') -Force
Copy-Item -LiteralPath (Join-Path $repoRoot 'launch-java-agent.ps1') -Destination (Join-Path $installRoot 'launch-java-agent.ps1') -Force
Copy-Item -LiteralPath $sourceJar -Destination (Join-Path $installRoot 'java-agent.jar') -Force

$sourceProductivity = Join-Path $jarRoot 'productivity.jar'
$installedProductivity = Join-Path $installRoot 'productivity.jar'
if (Test-Path -LiteralPath $sourceProductivity -PathType Leaf) {
    Copy-Item -LiteralPath $sourceProductivity -Destination $installedProductivity -Force
} elseif (Test-Path -LiteralPath $installedProductivity -PathType Leaf) {
    Remove-Item -LiteralPath $installedProductivity -Force
}

$userPath = [Environment]::GetEnvironmentVariable('Path', [EnvironmentVariableTarget]::User)
$entries = @()
if (-not [string]::IsNullOrWhiteSpace($userPath)) {
    $entries = @($userPath -split ';' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}
$normalizedInstallRoot = $installRoot.TrimEnd([char[]]@('\', '/'))
$alreadyInstalled = $false
foreach ($entry in $entries) {
    $normalizedEntry = $entry.Trim().Trim('"').TrimEnd([char[]]@('\', '/'))
    if ($normalizedEntry -ieq $normalizedInstallRoot) {
        $alreadyInstalled = $true
        break
    }
}
if (-not $alreadyInstalled) {
    [Environment]::SetEnvironmentVariable('Path', (@($entries) + $installRoot) -join ';',
        [EnvironmentVariableTarget]::User)
}

Write-Output ("Installed java-agent to {0}" -f $installRoot)
Write-Output 'Open a new terminal to use java-agent from PATH. This script updates only your user PATH.'
