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


def identify(row):
    # Mirror the Java C03 findVideoId decision branches.
    p = row.get("playlistItemData", {}).get("videoId", "")
    nav = row.get("navigationEndpoint", {}).get("watchEndpoint", {}).get("videoId", "")
    overlay = (row.get("overlay", {})
               .get("musicItemThumbnailOverlayRenderer", {})
               .get("content", {}).get("musicPlayButtonRenderer", {})
               .get("playNavigationEndpoint", {}).get("watchEndpoint", {})
               .get("videoId", ""))
    source = ("playlistItemData" if len(p) == 11 else
              "navigation" if len(nav) == 11 else
              "overlay" if len(overlay) == 11 else "NONE")
    return source, p or nav or overlay


def walk(node, found):
    if len(found) >= 24:
        return
    if isinstance(node, dict):
        row = node.get("musicResponsiveListItemRenderer")
        if row:
            columns = row.get("flexColumns", [])
            found.append({"columns": [runs(x) for x in columns[:3]], "id": identify(row)})
        for child in node.values():
            walk(child, found)
            if len(found) >= 24:
                return
    elif isinstance(node, list):
        for child in node:
            walk(child, found)
            if len(found) >= 24:
                return


def simulate_android_walk(obj, reverse=False):
    """Mirror Java C03 bounded recursive search (without Android JSONObject)."""
    visited = [0]
    candidates = []
    stats = {"maxdepth": 0, "prunedDepth": 0, "prunedVisit": 0,
             "allRows": 0, "videoRows": 0, "correctSeen": 0,
             "firstCorrectVisit": None, "firstCorrectDepth": None}

    def dive(node, depth):
        visited[0] += 1
        stats["maxdepth"] = max(stats["maxdepth"], depth)
        if depth > 32:
            stats["prunedDepth"] += 1
            return
        if visited[0] > 30000:
            stats["prunedVisit"] += 1
            return
        if len(candidates) >= 100:
            return
        if isinstance(node, dict):
            row = node.get("musicResponsiveListItemRenderer")
            if isinstance(row, dict):
                stats["allRows"] += 1
                cols=row.get("flexColumns", [])
                name="".join(runs(cols[0])) if cols else ""
                credit=runs(cols[1]) if len(cols)>1 else []
                source, videoid=identify(row)
                if len(videoid)==11:
                    stats["videoRows"] += 1
                    if "너에게" in name and "성시경" in "".join(credit):
                        stats["correctSeen"]+=1
                        if stats["firstCorrectVisit"] is None:
                            stats["firstCorrectVisit"] = visited[0]
                            stats["firstCorrectDepth"] = depth
                    candidates.append((name,credit,videoid))
            children=list(node.values())
            if reverse: children.reverse()
            for v in children:
                dive(v,depth+1)
                if visited[0]>30000 or len(candidates)>=100:return
        elif isinstance(node,list):
            seq=list(node)
            if reverse: seq.reverse()
            for v in seq:
                dive(v,depth+1)
                if visited[0]>30000 or len(candidates)>=100:return
    dive(obj,0)
    return visited[0],candidates,stats


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
            if query == "성시경 너에게":
                for backwards in (False, True):
                    visited, selected, stats = simulate_android_walk(
                        response, reverse=backwards)
                    print("C03 Java-walk simulator", "reverse="+str(backwards),
                          "visits="+str(visited),"collected="+str(len(selected)),
                          "stats="+str(stats))
                    for name,artist,vid in selected[:3]:
                        print("  gathered:",repr(name),repr(artist),vid)

            print(f"C03 API {query!r} filtered={filtered} rows={len(found)}")
            for i, item in enumerate(found):
                cols=item["columns"]
                print(f"  {i} id={item['id']} title={cols[0] if cols else []!r} "
                      f"credit={cols[1] if len(cols)>1 else []!r}")
        except Exception as exc:
            print(f"C03 API {query!r} filtered={filtered} unavailable: {type(exc).__name__} {str(exc)[:100]}")
