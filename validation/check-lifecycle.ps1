param([string]$Serial='emulator-5560',[string]$Adb='D:\airplay-build\sdk\platform-tools\adb.exe')
$ErrorActionPreference='Stop'
function Read-State([string]$Stage,[bool]$Awake){
 $power=(& $Adb -s $Serial shell dumpsys power) -join "`n"
 $dump=(& $Adb -s $Serial shell dumpsys servicediscovery) -join "`n"
 $active=(($dump -split 'Active clients:',2)[1] -split '\r?\nLogs:',2)[0]
 $count=([regex]::Matches($active,'Advertiser: serviceFullName=.*\._(?:airplay|raop|airmirror)\._tcp')).Count
 $ss=(& $Adb -s $Serial shell ss -lun 2>$null) -join "`n"
 $dlna=$ss -match ':1900\s'
 $wakeLock=$power -match "(?m)^\s*PARTIAL_WAKE_LOCK[^\r\n]*'airplay:server'"
 if($Awake){if($power -notmatch 'mWakefulness=Awake' -or $count -ne 3 -or !$dlna -or !$wakeLock){throw "FAIL $Stage : awake expected; mdns=$count dlna=$dlna wakeLock=$wakeLock"}}
 else{if($power -notmatch 'mWakefulness=Asleep' -or $count -ne 0 -or $dlna -or $wakeLock){throw "FAIL $Stage : standby expected; mdns=$count dlna=$dlna wakeLock=$wakeLock"}}
 [pscustomobject]@{stage=$Stage;awake=$Awake;mdns=$count;dlna=$dlna;wakeLock=$wakeLock}
}
$results=@()
$results+=Read-State 'cold start while asleep' $false
& $Adb -s $Serial shell input keyevent 224
Start-Sleep -Seconds 6
$results+=Read-State 'wake resumes discovery' $true
& $Adb -s $Serial shell input keyevent 223
Start-Sleep -Seconds 3
$results+=Read-State 'sleep withdraws discovery' $false
& $Adb -s $Serial shell svc wifi disable
Start-Sleep -Seconds 1
& $Adb -s $Serial shell svc wifi enable
Start-Sleep -Seconds 10
$results+=Read-State 'network reconnect while asleep stays silent' $false
& $Adb -s $Serial shell input keyevent 224
Start-Sleep -Seconds 6
$results+=Read-State 'second wake resumes discovery' $true
$results | ConvertTo-Json | Tee-Object -FilePath "$PSScriptRoot\standby-result.json"
Write-Output 'PASS standby lifecycle, mDNS withdrawal/restoration, DLNA and wake-lock release'
