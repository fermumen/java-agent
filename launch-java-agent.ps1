$ErrorActionPreference = 'Stop'

$jarPath = Join-Path $PSScriptRoot 'java-agent.jar'
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    $jarPath = Join-Path $PSScriptRoot 'target\java-agent.jar'
}
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    [Console]::Error.WriteLine('java-agent: java-agent.jar was not found beside the launcher or in target. Build it with Maven or run install-user-path.ps1.')
    exit 2
}

$javaPath = $null
if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $javaFromHome = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (Test-Path -LiteralPath $javaFromHome -PathType Leaf) {
        $javaPath = $javaFromHome
    }
}
if (-not $javaPath) {
    $javaCommand = Get-Command java.exe -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($javaCommand) {
        $javaPath = $javaCommand.Source
    }
}
if (-not $javaPath) {
    [Console]::Error.WriteLine('java-agent: Java was not found. Set JAVA_HOME or add java.exe to PATH.')
    exit 9009
}

try {
    & $javaPath -jar $jarPath @args
    $javaExitCode = $LASTEXITCODE
} catch {
    [Console]::Error.WriteLine('java-agent: could not start Java: ' + $_.Exception.Message)
    exit 1
}

if ($null -eq $javaExitCode) {
    exit 1
}
exit ([int]$javaExitCode)
