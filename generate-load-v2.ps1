# Sustained, varying load on all three backends at the same time.
# Each backend's load level moves up and down in steps, with occasional idle gaps.
param([int]$Minutes = 45)

$cores = [Environment]::ProcessorCount
$ports = 9001, 9002, 9003
$end = (Get-Date).AddMinutes($Minutes)
Write-Host "Logical cores: $cores. Running for $Minutes minutes. Press Ctrl+C to stop."

$jobs = foreach ($port in $ports) {
    Start-Job -ArgumentList $port, $cores, $end -ScriptBlock {
        param($p, $maxThreads, $endTime)
        $step = [Math]::Max(2, [int]($maxThreads / 4))
        $level = Get-Random -Minimum 0 -Maximum ($maxThreads + 1)

        while ((Get-Date) -lt $endTime) {
            $holdUntil = (Get-Date).AddSeconds((Get-Random -Minimum 15 -Maximum 50))

            while ((Get-Date) -lt $holdUntil -and (Get-Date) -lt $endTime) {
                if ($level -gt 0) {
                    try {
                        Invoke-RestMethod "http://localhost:$p/backend/work?ms=200&threads=$level" | Out-Null
                    } catch {
                        Start-Sleep -Seconds 1
                    }
                } else {
                    Start-Sleep -Seconds 1
                }
            }

            # Next level: a random step up or down, and about 1 time in 10 an idle gap.
            if ((Get-Random -Minimum 0 -Maximum 10) -eq 0) {
                $level = 0
            } else {
                $delta = Get-Random -Minimum (-$step) -Maximum ($step + 1)
                $level = [Math]::Min($maxThreads, [Math]::Max(0, $level + $delta))
            }
        }
    }
}

while ((Get-Job -State Running).Count -gt 0) {
    Start-Sleep -Seconds 60
    Write-Host ("{0} still running..." -f (Get-Date -Format "HH:mm"))
}
$jobs | Remove-Job -Force
Write-Host "Done"