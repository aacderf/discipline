param([Parameter(Mandatory=$true)][string]$Apk)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Apk).Path)
$bad = @()
$checked = 0
try {
    foreach ($entry in $archive.Entries | Where-Object { $_.FullName -match '^lib/(arm64-v8a|x86_64)/.+\.so$' }) {
        $stream = $entry.Open()
        $memory = [IO.MemoryStream]::new()
        try { $stream.CopyTo($memory); $bytes = $memory.ToArray() }
        finally { $stream.Dispose(); $memory.Dispose() }
        if ($bytes.Length -lt 64 -or $bytes[4] -ne 2 -or $bytes[5] -ne 1) {
            throw "Invalid 64-bit little-endian ELF: $($entry.FullName)"
        }
        $offset = [BitConverter]::ToInt64($bytes, 32)
        $size = [BitConverter]::ToUInt16($bytes, 54)
        $count = [BitConverter]::ToUInt16($bytes, 56)
        if ($size -lt 56 -or $count -eq 0 -or $offset -lt 0 -or $offset + $size * $count -gt $bytes.Length) {
            throw "Invalid ELF program header: $($entry.FullName)"
        }
        $loads = 0
        for ($i = 0; $i -lt $count; $i++) {
            $header = [int]($offset + $i * $size)
            if ([BitConverter]::ToInt32($bytes, $header) -eq 1) {
                $loads++
                $alignment = [BitConverter]::ToInt64($bytes, $header + 48)
                if ($alignment -lt 16384) { $bad += "$($entry.FullName): LOAD alignment $alignment" }
            }
        }
        if ($loads -eq 0) { throw "No LOAD segments: $($entry.FullName)" }
        $checked++
    }
} finally { $archive.Dispose() }
if ($bad.Count -gt 0) { throw ($bad -join "`n") }
Write-Output "16 KB ELF check passed: $checked native libraries. APK ZIP alignment and device runtime require separate checks."
