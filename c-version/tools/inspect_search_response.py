#!/usr/bin/env python3
"""Optional CI-only diagnostic: list fields in public YT Music search rows.
No user account, cookie, token, or playlist data. Exit 0 if access blocked.
"""
import json
import urllib.request

ORIGIN = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
VERSION = "1.20260114.03.00"
FILTER = "EgWKAQIIAWoKEAMQBBAJEAoQEA=="


def runs(column):
    node = column.get("musicResponsiveListItemFlexColumnRenderer", {})
    payload = node.get("text", {})
    return [str(x.get("text", "")) for x in payload.get("runs", [])]


def walk(node, found):
    if len(found) >= 8:
        return
    if isinstance(node, dict):
        row = node.get("musicResponsiveListItemRenderer")
        if row:
            columns = row.get("flexColumns", [])
            found.append([runs(x) for x in columns[:3]])
        for child in node.values():
            walk(child, found)
            if len(found) >= 8:
                return
    elif isinstance(node, list):
        for child in node:
            walk(child, found)
            if len(found) >= 8:
                return


for query in ("성시경 너에게", "성시경 너에게는", "서태지 너에게"):
    for filtered in (True, False):
        try:
            data = {
                "context": {"client": {
                    "clientName": "WEB_REMIX",
                    "clientVersion": VERSION,
                    "hl": "ko",
                    "gl": "KR",
                }},
                "query": query,
            }
            if filtered:
                data["params"] = FILTER
            req = urllib.request.Request(
                ORIGIN,
                data=json.dumps(data, ensure_ascii=False).encode("utf-8"),
                headers={
                    "Content-Type": "application/json; charset=UTF-8",
                    "Accept": "application/json",
                    "X-YouTube-Client-Name": "67",
                    "X-YouTube-Client-Version": VERSION,
                    "X-Goog-Api-Key": KEY,
                    "Origin": "https://music.youtube.com",
                    "Referer": "https://music.youtube.com/",
                    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36",
                },
                method="POST",
            )
            with urllib.request.urlopen(req, timeout=12) as f:
                response = json.load(f)
            found = []
            walk(response, found)
            print(f"C03 API {query!r} filtered={filtered} rows={len(found)}")
            for i, cols in enumerate(found[:5]):
                print(f"  {i}: {cols!r}")
        except Exception as exc:
            print(f"C03 API {query!r} filtered={filtered} unavailable: {type(exc).__name__} {str(exc)[:100]}")
