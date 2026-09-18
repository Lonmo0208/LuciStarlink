param(
    [string[]]$Workloads = @("block_toggle_border", "dense_chunk_patch", "edge_toggle", "roof_toggle", "sky_hole"),
    [string]$Stamp = (Get-Date -Format "yyyyMMdd-HHmmss"),
    [int]$Passes = 6,
    [int]$Warmups = 2
)
$ErrorActionPreference = "Continue"
$resultsDir = "benchmark-runs/$Stamp"
New-Item -ItemType Directory -Force -Path $resultsDir | Out-Null
foreach ($mode in @("vanilla", "lucistarlink")) {
    foreach ($wl in $Workloads) {
        $task = if ($mode -eq "vanilla") { "runBenchmarkVanillaServer" } else { "runBenchmarkServer" }
        $out = (Resolve-Path $resultsDir).Path + "/${mode}_${wl}.jsonl"
        $log = (Resolve-Path $resultsDir).Path + "/${mode}_${wl}.log"
        Write-Output "=== RUN mode=$mode workload=$wl ==="
        .\gradlew.bat $task -PbenchmarkWorkload=$wl -PbenchmarkPasses=$Passes -PbenchmarkWarmupPasses=$Warmups "-PbenchmarkOutput=$out" --console=plain 2>&1 |
            Tee-Object -FilePath $log |
            Select-String -Pattern "benchmark complete|BUILD (SUCCESSFUL|FAILED)" |
            Select-Object -First 3
        if ($LASTEXITCODE -ne 0) { Write-Output "GRADLE EXIT CODE: $LASTEXITCODE" }
    }
}
Write-Output "=== COLLECTED RESULTS ($Stamp) ==="
Get-ChildItem $resultsDir -Filter *.jsonl | ForEach-Object {
    Get-Content $_.FullName | ForEach-Object {
        $j = $_ | ConvertFrom-Json
        "{0,-28} {1,-20} changes={2,-6} nsPerChange={3,-12} measuredMs={4,-8} applyMs={5,-8} waitMs={6,-8} status={7}" -f `
            $_.File.Replace((Resolve-Path $resultsDir).Path + "\", ""), $j.workload, $j.changes, [math]::Round($j.nsPerChange, 1), [math]::Round($j.millis, 2), [math]::Round($j.applyMillis, 2), [math]::Round($j.waitMillis, 2), $j.status
    }
}
