# Random load bursts on random backends, to create varied training data.
$ports = 9001, 9002, 9003
$end = (Get-Date).AddMinutes(30)

while ((Get-Date) -lt $end) {
    $port = Get-Random -InputObject $ports
    $workers = Get-Random -Minimum 1 -Maximum 9
    $seconds = Get-Random -Minimum 10 -Maximum 40
    Write-Host "Burst: port $port, $workers workers, $seconds seconds"

    $jobs = 1..$workers | ForEach-Object {
        Start-Job -ArgumentList $port, $seconds -ScriptBlock {
            param($p, $s)
            $stop = (Get-Date).AddSeconds($s)
            while ((Get-Date) -lt $stop) {
                Invoke-RestMethod "http://localhost:$p/backend/work?ms=300" | Out-Null
            }
        }
    }

    $jobs | Wait-Job | Out-Null
    $jobs | Remove-Job -Force

    Start-Sleep -Seconds (Get-Random -Minimum 5 -Maximum 20)
}
Write-Host "Done"