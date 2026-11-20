# Dev check: runs Blink with a BLINK_TEST scenario, snapshots its own window later, reports whether Blink is drawn.
param([string]$Scenario, [int]$SnapAt = 16000)
Set-Location $PSScriptRoot
Get-Process electron -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "*blink-desktop*" } | Stop-Process -Force
Start-Sleep 1
$snap = "$env:TEMP\blink-t-$Scenario.png"
Remove-Item "$env:TEMP\blink-t-$Scenario-*.png" -ErrorAction SilentlyContinue
$env:BLINK_TEST = $Scenario; $env:BLINK_SNAPSHOT = $snap; $env:BLINK_SNAPSHOT_DELAY = "$SnapAt"; $env:BLINK_SNAPSHOT_COUNT = "1"
$p = Start-Process ".\node_modules\electron\dist\electron.exe" -ArgumentList "." -PassThru
Start-Sleep -Milliseconds ($SnapAt + 3000)
Get-Process electron -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "*blink-desktop*" } | Stop-Process -Force
Add-Type -AssemblyName System.Drawing
$src = [System.Drawing.Bitmap]::new("$env:TEMP\blink-t-$Scenario-0.png"); $n = 0
for ($y = 0; $y -lt $src.Height; $y += 3) { for ($x = 0; $x -lt $src.Width; $x += 3) { if ($src.GetPixel($x, $y).A -gt 30) { $n++ } } }
$src.Dispose()
"$Scenario : $(if ($n -gt 150) { 'Blink VISIBLE' } else { 'Blink MISSING' }) ($n samples)"
