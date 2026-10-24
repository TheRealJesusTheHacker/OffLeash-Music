#!/usr/bin/env python3
"""OffLeash live smoke — replays the SPEC §1 recon probes anonymously.

browse (WEB_REMIX) -> search (WEB_REMIX) -> player (iOS 20.10.4) -> ranged
stream GET. Prints one PASS/FAIL line per probe and exits non-zero if any
probe fails. This script is a smoke alarm for YouTube's unofficial API
changing shape; it is not a unit test and it gates nothing.
"""
import json
import re
import sys
import urllib.error
import urllib.request

WEB_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
IOS_KEY = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc"
WEB_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
IOS_UA = ("com.google.ios.youtube/20.10.4 (iPhone16,2; U; "
          "CPU iOS 18_3_1 like Mac OS X)")
BASE = "https://music.youtube.com/youtubei/v1"

WEB_CTX = {"client": {"clientName": "WEB_REMIX",
                      "clientVersion": "1.20241127.01.00",
                      "hl": "en", "gl": "US", "userAgent": WEB_UA}}
IOS_CTX = {"client": {"clientName": "IOS", "clientVersion": "20.10.4",
                      "deviceMake": "Apple", "deviceModel": "iPhone16,2",
                      "userAgent": IOS_UA, "osName": "iPhone",
                      "osVersion": "18.3.1.22D72", "hl": "en", "gl": "US"}}

failures = []


def post(url, body, headers):
    req = urllib.request.Request(url, data=json.dumps(body).encode(),
                                 method="POST")
    req.add_header("Content-Type", "application/json")
    for k, v in headers.items():
        req.add_header(k, v)
    with urllib.request.urlopen(req, timeout=45) as r:
        return json.loads(r.read().decode("utf-8"))


def check(name, ok, detail=""):
    print(f"{'PASS' if ok else 'FAIL'}  {name}  {detail}".rstrip())
    if not ok:
        failures.append(name)


web_h = {"User-Agent": WEB_UA, "Origin": "https://music.youtube.com",
         "Referer": "https://music.youtube.com/"}

video_id = None
try:
    home = post(f"{BASE}/browse?key={WEB_KEY}",
                {"context": WEB_CTX, "browseId": "FEmusic_home"}, web_h)
    s = json.dumps(home)
    check("browse home", "musicCarouselShelfRenderer" in s
          or "musicShelfRenderer" in s, f"bytes={len(s)}")
except Exception as e:  # noqa: BLE001 - smoke reports, never crashes
    check("browse home", False, repr(e))

try:
    search = post(f"{BASE}/search?key={WEB_KEY}",
                  {"context": WEB_CTX, "query": "never gonna give you up"},
                  web_h)
    ids = re.findall(r'"videoId":\s*"([A-Za-z0-9_-]{11})"',
                     json.dumps(search))
    check("search", len(ids) > 0, f"videoIds={len(ids)}")
    video_id = ids[0] if ids else None
except Exception as e:  # noqa: BLE001
    check("search", False, repr(e))

if video_id:
    try:
        player = post(f"https://www.youtube.com/youtubei/v1/player"
                      f"?key={IOS_KEY}",
                      {"context": IOS_CTX, "videoId": video_id,
                       "racyCheckOk": True, "contentCheckOk": True},
                      {"User-Agent": IOS_UA})
        status = player.get("playabilityStatus", {}).get("status")
        sd = player.get("streamingData", {})
        fmts = sd.get("formats", []) + sd.get("adaptiveFormats", [])
        audio = [f for f in fmts
                 if "url" in f and f.get("mimeType", "").startswith("audio")]
        check("player iOS", status == "OK" and len(audio) > 0,
              f"playability={status} audio_direct={len(audio)}")
        if audio:
            best = max(audio, key=lambda f: f.get("bitrate", 0))
            req = urllib.request.Request(best["url"], method="GET")
            req.add_header("User-Agent", IOS_UA)
            req.add_header("Range", "bytes=0-2047")
            with urllib.request.urlopen(req, timeout=45) as r:
                data = r.read()
                check("stream range GET", r.status == 206 and len(data) > 0,
                      f"status={r.status} bytes={len(data)} "
                      f"type={r.headers.get('Content-Type')}")
    except urllib.error.HTTPError as e:
        check("player iOS", False,
              f"HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:120]}")
    except Exception as e:  # noqa: BLE001
        check("player iOS", False, repr(e))

print("SMOKE:", "ALL PASS" if not failures else f"FAILURES: {failures}")
sys.exit(1 if failures else 0)
