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

# Some Windows JREs (IBM Semeru/OpenJ9) write the console with the ANSI code
# page while the console decodes its OEM code page, which garbles non-ASCII
# output. Switch this console to UTF-8 for the run and restore it afterwards.
# Set JAVA_AGENT_CONSOLE_UTF8=0 to keep the current code page.
$savedOutputEncoding = $null
$savedInputEncoding = $null
$javaOptions = @()
if ($env:JAVA_AGENT_CONSOLE_UTF8 -notin @('0', 'false', 'off')) {
    try {
        $utf8 = New-Object System.Text.UTF8Encoding $false
        $savedOutputEncoding = [Console]::OutputEncoding
        $savedInputEncoding = [Console]::InputEncoding
        [Console]::OutputEncoding = $utf8
        [Console]::InputEncoding = $utf8
        $javaOptions = @('-Dfile.encoding=UTF-8')
    } catch {
        # No attached console (redirected or headless): leave encodings alone.
        $savedOutputEncoding = $null
        $savedInputEncoding = $null
    }
}

# The interactive UI binds the Windows console through jdk.internal.le, the
# module jshell uses. A JRE without jshell ships it but never loads it, and a
# jar manifest cannot add modules, so request it here; only when the runtime's
# release file lists it, because naming a missing module aborts Java startup.
$moduleOptions = @()
if ($env:JAVA_AGENT_RAW_TERMINAL -notin @('0', 'false', 'off')) {
    $releaseFile = Join-Path (Split-Path (Split-Path $javaPath -Parent) -Parent) 'release'
    try {
        if ((Get-Content -LiteralPath $releaseFile -Raw -ErrorAction Stop) -match 'MODULES="[^"]*\bjdk\.internal\.le\b') {
            $moduleOptions = @('--add-modules', 'jdk.internal.le')
        }
    } catch {
        # No release file: keep the default module graph (JNA fallback still applies).
    }
}

try {
    & $javaPath @moduleOptions @javaOptions -jar $jarPath @args
    $javaExitCode = $LASTEXITCODE
} catch {
    [Console]::Error.WriteLine('java-agent: could not start Java: ' + $_.Exception.Message)
    exit 1
} finally {
    try {
        if ($null -ne $savedOutputEncoding) { [Console]::OutputEncoding = $savedOutputEncoding }
        if ($null -ne $savedInputEncoding) { [Console]::InputEncoding = $savedInputEncoding }
    } catch {
        # Best effort: the console may already be gone.
    }
}

if ($null -eq $javaExitCode) {
    exit 1
}
exit ([int]$javaExitCode)
