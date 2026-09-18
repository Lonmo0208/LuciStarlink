param(
    [string[]]$Workloads = @("block_toggle_border", "dense_chunk_patch", "edge_toggle", "roof_toggle", "sky_hole"),
    [string]$Stamp = (Get-Date -Format "yyyyMMdd-HHmmss"),
    [int]$Passes = 6,
    [int]$Warmups = 2
)
$ErrorActionPreference = "Continue"
$resultsDir = "benchmark-runs/$Stamp"
New-Item -ItemType Directory -Force -Path $resultsDir | Out-Null
$flagProps = @("-PlucistarlinkExperimentalSectionFastPath=true","-PlucistarlinkExperimentalSkySeedSkip=true","-PlucistarlinkExperimentalDenseIncremental=true","-PlucistarlinkExperimentalInlineRuntime=true")

foreach ($wl in $Workloads) {
    $out = (Resolve-Path $resultsDir).Path + "/vanilla_${wl}.jsonl"
    $log = (Resolve-Path $resultsDir).Path + "/vanilla_${wl}.log"
    Write-Output "=== RUN vanilla workload=$wl ==="
    .\gradlew.bat runBenchmarkVanillaServer "-PbenchmarkWorkload=$wl" "-PbenchmarkPasses=$Passes" "-PbenchmarkWarmupPasses=$Warmups" "-PbenchmarkOutput=$out" --console=plain 2>&1 |
        Tee-Object -FilePath $log | Select-String -Pattern "benchmark complete" | Select-Object -First 1

    $out = (Resolve-Path $resultsDir).Path + "/lucistarlink_off_${wl}.jsonl"
    $log = (Resolve-Path $resultsDir).Path + "/lucistarlink_off_${wl}.log"
    Write-Output "=== RUN lucistarlink-off workload=$wl ==="
    .\gradlew.bat runBenchmarkServer "-PbenchmarkWorkload=$wl" "-PbenchmarkPasses=$Passes" "-PbenchmarkWarmupPasses=$Warmups" "-PbenchmarkOutput=$out" --console=plain 2>&1 |
        Tee-Object -FilePath $log | Select-String -Pattern "benchmark complete" | Select-Object -First 1

    $out = (Resolve-Path $resultsDir).Path + "/lucistarlink_on_${wl}.jsonl"
    $log = (Resolve-Path $resultsDir).Path + "/lucistarlink_on_${wl}.log"
    Write-Output "=== RUN lucistarlink-on workload=$wl ==="
    .\gradlew.bat runBenchmarkServer "-PbenchmarkWorkload=$wl" "-PbenchmarkPasses=$Passes" "-PbenchmarkWarmupPasses=$Warmups" "-PbenchmarkOutput=$out" $flagProps --console=plain 2>&1 |
        Tee-Object -FilePath $log | Select-String -Pattern "benchmark complete" | Select-Object -First 1
}

Write-Output "=== COLLECTED ($Stamp) ==="
$summary = @{}
foreach ($mode in @("vanilla", "lucistarlink_off", "lucistarlink_on")) {
    foreach ($wl in $Workloads) {
        $file = "$resultsDir/${mode}_${wl}.jsonl"
        if (-not (Test-Path $file)) { Write-Output "$mode $wl MISSING"; continue }
        $j = (Get-Content $file | Select-Object -Last 1) | ConvertFrom-Json
        $summary[$mode + "_" + $wl] = $j
    }
}
foreach ($wl in $Workloads) {
    $v = $summary["vanilla_" + $wl]
    $off = $summary["lucistarlink_off_" + $wl]
    $on = $summary["lucistarlink_on_" + $wl]
    $vN = [double]$v.nsPerChange; $offN = [double]$off.nsPerChange; $onN = [double]$on.nsPerChange
    "{0,-22} vanilla={1,9:N0} off={2,9:N0} on={3,9:N0}  xV(off)={4,5:N2} xV(on)={5,5:N2}  on/off={6,5:N2}  status(off)={7} status(on)={8}" -f `
        $wl, $vN, $offN, $onN, ($vN / $offN), ($vN / $onN), ($offN / $onN), $off.status, $on.status
}
