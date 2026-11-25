param(
    [Parameter(Mandatory = $true)][string]$Go,
    [Parameter(Mandatory = $true)][string]$Ndk,
    [string[]]$Abis = @('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')
)
$ErrorActionPreference = 'Stop'
$targets = @{
    'arm64-v8a' = @('arm64', 'aarch64-linux-android28-clang.cmd')
    'armeabi-v7a' = @('arm', 'armv7a-linux-androideabi28-clang.cmd')
    'x86' = @('386', 'i686-linux-android28-clang.cmd')
    'x86_64' = @('amd64', 'x86_64-linux-android28-clang.cmd')
}
$old = @{}
foreach ($key in @('GOOS', 'GOARCH', 'GOARM', 'CGO_ENABLED', 'CC', 'GOTOOLCHAIN')) { $old[$key] = [Environment]::GetEnvironmentVariable($key) }
Push-Location $PSScriptRoot
try {
    $env:GOOS = 'android'
    $env:CGO_ENABLED = '1'
    $env:GOARM = '7'
    $env:GOTOOLCHAIN = 'local'
    foreach ($abi in $Abis) {
        if (!$targets.ContainsKey($abi)) { throw "Unsupported ABI: $abi" }
        $target = $targets[$abi]
        $env:GOARCH = $target[0]
        $env:CC = Join-Path $Ndk ('toolchains\llvm\prebuilt\windows-x86_64\bin\' + $target[1])
        $out = Join-Path $PSScriptRoot ('..\app\libs\' + $abi + '\libandrong-soar.so')
        & $Go build -mod=readonly -buildmode=pie -trimpath '-ldflags=-s -w' -o $out .
        if ($LASTEXITCODE -ne 0) { throw "Soar build failed: $abi" }
        Write-Host "Soar ready: $abi"
    }
} finally {
    Pop-Location
    foreach ($key in $old.Keys) { [Environment]::SetEnvironmentVariable($key, $old[$key]) }
}
