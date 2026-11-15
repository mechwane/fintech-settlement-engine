$ErrorActionPreference = "Stop"
$batchId = Get-Date -Format "HHmmss"
Write-Host "`n=== LAUNCHING 25 CONCURRENT SIGNED WEBHOOKS (20 UNIQUE + 5 RACE DUPLICATES) ===" -ForegroundColor Cyan
$accounts = @(@{ va = "0123456789"; mch = "MCH-8821" }, @{ va = "9876543210"; mch = "MCH-9901" }, @{ va = "5544332211"; mch = "MCH-7700" })
$jobs = 1..25 | ForEach-Object {
    $idx = $_
    $refNum = if ($idx -le 20) { $idx } else { $idx - 20 }
    $acct = $accounts[$refNum % $accounts.Count]
    $ref = "TXN-LOAD-$batchId-$refNum"
    Start-Job -ScriptBlock {
        param($txnRef, $vaNum, $merchantId)
        $body = "{`"transactionReference`": `"$txnRef`", `"virtualAccountNumber`": `"$vaNum`", `"amountKobo`": 1000000, `"merchantId`": `"$merchantId`"}"
        $key = [System.Text.Encoding]::UTF8.GetBytes("nibss-super-secret-key-2026")
        $hmac = New-Object System.Security.Cryptography.HMACSHA256
        $hmac.Key = $key
        $sig = [System.BitConverter]::ToString($hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($body))).Replace("-", "").ToLower()
        try {
            $resp = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/settlements/webhook" -Method Post -Headers @{ "X-Webhook-Signature" = $sig; "Content-Type" = "application/json" } -Body $body
            return "[OK] $txnRef ($merchantId) -> $($resp.status)"
        } catch {
            return "[ERR] $txnRef -> $($_.Exception.Message)"
        }
    } -ArgumentList $ref, $acct.va, $acct.mch
}
$results = $jobs | Wait-Job | Receive-Job
$jobs | Remove-Job
$results | ForEach-Object { Write-Host $_ }
Write-Host "`nWaiting 2.5 seconds for Outbox Relay & Kafka Consumers to drain..." -ForegroundColor Yellow
Start-Sleep -Milliseconds 2500
Write-Host "`n=== T+0 RECONCILIATION AUDIT AFTER CONCURRENT BURST ===" -ForegroundColor Green
$recon = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/settlements/reconcile" -Method Get
$recon | ConvertTo-Json -Depth 4
