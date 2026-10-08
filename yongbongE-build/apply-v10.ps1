param([string]$ClientPath)
$ErrorActionPreference = 'Stop'

$text = [IO.File]::ReadAllText($ClientPath)

$oldSlots = 'private const int MaxMetadataReadSessions = 2;'
$newSlots = 'private const int MaxMetadataReadSessions = 4;'
if (($text.Split($oldSlots).Length - 1) -ne 1) {
  throw "MaxMetadataReadSessions marker mismatch"
}
$text = $text.Replace($oldSlots, $newSlots)

$oldBlock = @'
                if (eof)
                {
                    Require(ReadResponse(), 226, 250);
                }
                else if (!ResyncAfterPartialReadNoLock())
                {
                    // 받은 데이터 자체는 유효하므로 반환하되, 동기화에 실패한 제어 연결은 폐기한다.
                    DisconnectNoLock();
                }
'@

$newBlock = @'
                if (eof)
                {
                    Require(ReadResponse(), 226, 250);
                }
                else
                {
                    // Explorer MP3 metadata probes stop RETR before EOF. Do not wait up to 3 seconds
                    // trying to resynchronize that control channel; discard it immediately.
                    DisconnectNoLock();
                }
'@

if (($text.Split($oldBlock).Length - 1) -ne 1) {
  throw "partial-read resync marker mismatch"
}
$text = $text.Replace($oldBlock, $newBlock)

[IO.File]::WriteAllText($ClientPath, $text, (New-Object System.Text.UTF8Encoding($false)))
