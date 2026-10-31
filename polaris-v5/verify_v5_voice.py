#!/usr/bin/env python3
"""Guard v0.58 voice alias parity and AA Media3 voice entry points."""
from pathlib import Path
import sys,re
root=Path(sys.argv[1])/"app/src/main/kotlin/app/hush/music"
voice=(root/"polarisv5/V5Voice.kt").read_text()
callback=(root/"playback/MediaLibrarySessionCallback.kt").read_text()
service=(root/"playback/MusicService.kt").read_text()
legacy=(Path(__file__).resolve().parents[1]/"app/src/main/java/com/polarisunbound/app/PolarisMediaService.java").read_text()
station_functions={
 "kiis": ["kiis","키스fm","키스에프엠","키이스","키스라디오"],
 "gallery":["gallery41","갤러리41","갤러리포티원","gallery","갤러리"],
 "kr1":["89.1","891","kbs","케이비에스","coolfm","쿨fm","쿨에프엠"],
 "kr2":["91.9","919","fm4u","fm포유","에프엠포유"],
 "kr3":["93.9","939","cbs","씨비에스","musicfm","뮤직fm","음악fm"],
 "kr4":["95.9","959","표준fm","표준에프엠","standardfm"],
 "kr5":["102.7","1027","afn","에이에프엔","eaglefm","이글fm"],
 "kr6":["107.7","1077","sbs","에스비에스","powerfm","파워fm","파워에프엠"],
}
tests={}
for id,names in station_functions.items():
 tests[f"v0.58 station voice aliases {id}"]=all('"'+x+'"' in voice and '"'+x+'"' in legacy for x in names)
tests.update({
 "v0.58 KIIS wins ambiguous 102.7":voice.index('return "kiis"')<voice.index('return "kr5"'),
 "v0.58 MBC fallback prefers FM4U":'if (any(tight,"mbc","엠비씨")) return "kr2"' in voice,
 "v0.58 Korean speech normalization":all('"'+x+'"' in voice for x in ["엠피쓰리","에프엠","점"]),
 "v0.58 original preserved":'private String radioIdFromVoice(String raw)' in legacy,
 "Hush music voice remains":'mediaLibrarySessionCallback.resolveVoiceMediaItems(trimmed)' in service,
 "AA direct intent radio voice":'V5Voice.radioIdForQuery(trimmed)' in service,
 "AA voice search count":'if (V5Voice.radioIdForQuery(q) != null)' in callback,
 "AA voice search playable item":'listOf(V5Radio.item(context, voiceRadioId))' in callback,
 "AA setMediaItems radio voice":'V5Voice.radioIdForQuery(firstItem.requestMetadata.searchQuery)' in callback,
 "AA addMediaItems radio voice":'listOf(V5Radio.item(context, radioId))' in callback,
 "AA steering-wheel radio voice queue":'MediaSession.MediaItemsWithStartPosition(\\n                            voiceQueue, voiceIndex, 0L,' in callback,
 "no legacy voice patch stack":'app.hush.music.polaris.PolarisVoiceRouter' not in callback and 'app.hush.music.polaris.PolarisVoiceRouter' not in service,
})
for name,good in tests.items():print(("PASS" if good else "FAIL")+": "+name)
assert all(tests.values()),"Polaris v5 voice-only regression failed"
