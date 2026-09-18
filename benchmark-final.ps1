param(
    [string]$Stamp = (Get-Date -Format "yyyyMMdd-final"),
    [int]$Passes = 6,
    [int]$Warmups = 2,
    [int]$Reps = 2
)
$ErrorActionPreference = "Continue"
$resultsDir = "benchmark-runs/$Stamp"
New-Item -ItemType Directory -Force -Path $resultsDir | Out-Null
$flagProps = @(
    "-PlucistarlinkExperimentalSectionFastPath=true",
    "-PlucistarlinkExperimentalSkySeedSkip=true",
    "-PlucistarlinkExperimentalDenseIncremental=true",
    "-PlucistarlinkExperimentalInlineRuntime=true",
    "-PlucistarlinkExperimentalRuntimeAdoption=true"
)
$workloads = @("block_toggle_border", "dense_chunk_patch", "edge_toggle", "roof_toggle", "sky_hole", "block_toggle_sparse")

foreach ($rep in 1..$Reps) {
    foreach ($wl in $workloads) {
        $out = (Resolve-Path $resultsDir).Path + "/vanilla_${wl}_$rep.jsonl"
        .\gradlew.bat runBenchmarkVanillaServer "-PbenchmarkWorkload=$wl" "-PbenchmarkPasses=$Passes" "-PbenchmarkWarmupPasses=$Warmups" "-PbenchmarkOutput=$out" --console=plain 2>&1 |
            Select-String -Pattern "BUILD FAILED" | Select-Object -First 1
        $out = (Resolve-Path $resultsDir).Path + "/lucistarlink_${wl}_$rep.jsonl"
        .\gradlew.bat runBenchmarkServer "-PbenchmarkWorkload=$wl" "-PbenchmarkPasses=$Passes" "-PbenchmarkWarmupPasses=$Warmups" "-PbenchmarkOutput=$out" $flagProps --console=plain 2>&1 |
            Select-String -Pattern "BUILD FAILED" | Select-Object -First 1
        Write-Output "done rep=$rep wl=$wl"
    }
}

Write-Output "=== SUMMARY ($Stamp) ==="
foreach ($wl in $workloads) {
    $vMed = @(); $lMed = @()
    foreach ($rep in 1..$Reps) {
        $vf = "$resultsDir/vanilla_${wl}_$rep.jsonl"
        $lf = "$resultsDir/lucistarlink_${wl}_$rep.jsonl"
        if (Test-Path $vf) { $j = (Get-Content $vf | Select-Object -Last 1) | ConvertFrom-Json; $vMed += [double]$j.nsPerChange }
        if (Test-Path $lf) { $j = (Get-Content $lf | Select-Object -Last 1) | ConvertFrom-Json; $lMed += [double]$j.nsPerChange }
    }
    $v = ($vMed | Sort-Object)[0]; $l = ($lMed | Sort-Object)[0]
    "{0,-22} vanilla={1,9:N0} lucistarlink={2,9:N0} speedup={3,5:N2}x" -f $wl, $v, $l, ($v / $l)
}
