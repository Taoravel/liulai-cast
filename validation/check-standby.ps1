param([string]$Serial='emulator-5560',[string]$Adb='D:\airplay-build\sdk\platform-tools\adb.exe')
$ErrorActionPreference='Stop'
$power=(& $Adb -s $Serial shell dumpsys power) -join "`n"
if($power -notmatch 'mWakefulness=Asleep'){throw 'Test precondition: device must be asleep'}
$dump=(& $Adb -s $Serial shell dumpsys servicediscovery) -join "`n"
$active=($dump -split 'Active clients:',2)[1] -split '\r?\nLogs:',2 | Select-Object -First 1
$count=([regex]::Matches($active,'Advertiser: serviceFullName=.*\._(?:airplay|raop|airmirror)\._tcp')).Count
if($count -ne 0){throw "FAIL: screen asleep but $count receiver announcements remain registered"}
Write-Output 'PASS: screen asleep, no receiver mDNS announcements registered'
