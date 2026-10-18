#!/usr/bin/env python3
"""C06 anonymous public YTM search protocol parity diagnostic.
CI ONLY. Does not access user data, log in, or send private credentials.
Cross-checked against sigma67/ytmusicapi 1.12.3's public search params,
unauthenticated SOCS consent cookie, and WEB_REMIX client.
"""
import json
import urllib.request
import collections

URL = "https://music.youtube.com/youtubei/v1/search?alt=json"
KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
VERSION = "1.20260114.03.00"
NEW_SONGS = "EgWKAQIIAWoMEA4QChADEAQQCRAF"
OLD_SONGS = "EgWKAQIIAWoKEAMQBBAJEAoQEA=="
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:88.0) "
      "Gecko/20100101 Firefox/88.0")


def examine(obj):
    kinds = collections.Counter()
    matches = []

    def go(node):
        if isinstance(node, dict):
            for key in node:
                if key in (
                    "musicResponsiveListItemRenderer", "itemSectionRenderer",
                    "musicShelfRenderer", "tabbedSearchResultsRenderer",
                    "messageRenderer",
                ):
                    kinds[key] += 1
            row = node.get("musicResponsiveListItemRenderer")
            if row and len(matches) < 50:
                cols = row.get("flexColumns", [])
                def col(i):
                    if i >= len(cols):
                        return []
                    return [r.get("text", "")
                            for r in cols[i].get(
                                "musicResponsiveListItemFlexColumnRenderer",
                                {}).get("text", {}).get("runs", [])]
                play = (row.get("overlay", {})
                        .get("musicItemThumbnailOverlayRenderer", {})
                        .get("content", {})
                        .get("musicPlayButtonRenderer", {})
                        .get("playNavigationEndpoint", {})
                        .get("watchEndpoint", {}))
                video_type = play.get("watchEndpointMusicSupportedConfigs", {}
                    ).get("watchEndpointMusicConfig", {}).get("musicVideoType", "")
                song_id = row.get("playlistItemData", {}).get("videoId", "")
                matches.append((col(0), col(1), video_type, song_id))
            for value in node.values():
                go(value)
        elif isinstance(node, list):
            for value in node:
                go(value)
    go(obj)
    return kinds, matches


def request(query, filt, consent):
    payload = {
        "context": {
            "client": {
                "clientName": "WEB_REMIX",
                "clientVersion": VERSION,
                "hl": "ko", "gl": "KR",
            },
            "user": {},
        },
        "query": query,
    }
    if filt:
        payload["params"] = filt
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {
        "Content-Type": "application/json; charset=UTF-8",
        "Accept": "application/json",
        "X-YouTube-Client-Name": "67",
        "X-YouTube-Client-Version": VERSION,
        "X-Goog-Api-Key": KEY,
        "Origin": "https://music.youtube.com",
        "Referer": "https://music.youtube.com/",
        "User-Agent": UA,
    }
    if consent:
        headers["Cookie"] = "SOCS=CAI"
    req = urllib.request.Request(
        URL, data=data, headers=headers, method="POST"
    )
    with urllib.request.urlopen(req, timeout=15) as f:
        return json.load(f)


for query, filt, consent, name in [
    ("성시경 너에게", NEW_SONGS, True, "C06: 1.12.3 songs + SOCS"),
    ("성시경 너에게", OLD_SONGS, True, "C05: old songs + SOCS"),
    ("성시경 너에게", None, True, "C06: unfiltered + SOCS"),
    ("성시경 너에게", NEW_SONGS, False, "C06: 1.12.3 songs NO SOCS"),
    ("서태지 너에게", NEW_SONGS, True, "C06: Seo Taiji songs + SOCS"),
]:
    try:
        obj = request(query, filt, consent)
        kinds, hits = examine(obj)
        print(f"{name} query={query!r} counts={dict(kinds)}")
        for i, (title,artist,vt,song_id) in enumerate(hits[:5]):
            print(f"  {i} {title!r} {artist!r} "
                  f"musicVideoType={vt!r} id={song_id!r}")
    except Exception as ex:
        print(f"{name}: HTTP/parse unavailable: {type(ex).__name__} "
              f"{str(ex)[:120]}")
