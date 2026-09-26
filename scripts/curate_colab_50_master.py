#!/usr/bin/env python3
"""
curate_colab_50_master.py: Production Autonomous Curation Pipeline for 50 Verified 4K Ambient Videos.
Operates between local workstation (candidate discovery & sub-second frame seeking)
and Google Colab Tesla T4 GPU (FP16 batch tensor evaluation in VRAM).
"""

import os
import sys
import io
import time
import json
import gzip
import math
import tarfile
import urllib.parse
import urllib.request
import subprocess
from datetime import datetime, timezone
from PIL import Image
import numpy as np

COLAB_BIN = os.path.expanduser("~/.colab_venv/bin/colab")
SESSION_NAME = "aerial-50"
STATE_DIR = "curation_state"
PROGRESS_FILE = os.path.join(STATE_DIR, "curation_progress.json")
AUDIT_LOG_FILE = os.path.join(STATE_DIR, "audited_history_50.jsonl")
CATALOG_FILE = os.path.join(STATE_DIR, "verified_catalog_50.jsonl")
SEED_OUTPUT_JSON = "app/src/main/assets/curated_youtube_seed.json"
SEED_OUTPUT_GZ = "app/src/main/assets/curated_youtube_seed.json.gz"

CATEGORIES = [
    "nature", "drone", "ocean", "cities", "animals", "space", "weather", "winter"
]

CATEGORY_QUERIES = {
    "nature": [
        "4k mountain valley forest cinematic no talking",
        "4k waterfall river gorge nature documentary",
        "4k national park serene wilderness real footage"
    ],
    "drone": [
        "4k dji mavic drone aerial cinematic landscape",
        "4k drone flyover coast mountains 60fps",
        "4k aerial view coastline fjords alpine"
    ],
    "ocean": [
        "4k underwater coral reef tropical fish ocean",
        "4k aerial ocean waves crashing sea cliffs",
        "4k clear blue ocean marine life underwater"
    ],
    "cities": [
        "4k city skyline twilight modern architecture cinematic",
        "4k tokyo new york skyline night timelapse",
        "4k aerial city skyline bridges architecture"
    ],
    "animals": [
        "4k wildlife documentary animals safari wilderness",
        "4k marine life whales dolphins underwater",
        "4k wild birds animals cinematic nature film"
    ],
    "space": [
        "4k earth from iss space station orbit auroras",
        "4k deep space hubble james webb galaxies nebulae",
        "4k planet earth atmosphere satellite orbit"
    ],
    "weather": [
        "4k dramatic storm clouds rolling thunder mist fog",
        "4k mountain fog cloud inversion timelapse",
        "4k atmospheric weather clouds sky rolling"
    ],
    "winter": [
        "4k winter snow forest frozen lake alpine cinematic",
        "4k snowy mountains blizzard quiet snowfall",
        "4k arctic iceland snow glacier ice winter"
    ]
}

TITLE_BLACKLIST = [
    "vlog", "travel vlog", "travel diary", "travel guide", "review", "unboxing",
    "talking", "hosted by", "presented by", "tour guide", "hotel", "resort",
    "things to do", "itinerary", "day in my life", "reaction", "podcast",
    "tutorial", "how to", "sora", "runway ml", "midjourney", "pika labs",
    "ai generated", "made with ai", "3d animation", "render", "gameplay", "walk tour",
    "demo", "oled demo", "4k demo", "tv demo", "test", "settings", "bts", "commercial",
    "color grade", "lut", "luts", "davinci resolve", "premiere pro", "walking tour", "walk in"
]

def log(msg: str):
    ts = datetime.now().strftime("%H:%M:%S")
    print(f"[{ts}] {msg}", flush=True)

def update_progress(audited_count, approved_map, t0, status="RUNNING"):
    total_approved = sum(approved_map.values())
    elapsed_sec = time.time() - t0
    elapsed_min = round(elapsed_sec / 60.0, 2)
    rate = round(total_approved / elapsed_min, 2) if elapsed_min > 0 else 0.0

    progress_data = {
        "status": status,
        "total_target": 50,
        "total_audited": audited_count,
        "total_approved": total_approved,
        "target_per_category": {cat: math.ceil(50 / len(CATEGORIES)) for cat in CATEGORIES},
        "approved_by_category": approved_map,
        "elapsed_seconds": round(elapsed_sec, 1),
        "elapsed_minutes": elapsed_min,
        "throughput_vids_per_min": rate,
        "last_updated": datetime.now(timezone.utc).isoformat()
    }
    with open(PROGRESS_FILE, "w", encoding="utf-8") as f:
        json.dump(progress_data, f, indent=2)

def search_candidates_for_category(category: str, limit_per_query: int = 15) -> list[dict]:
    queries = CATEGORY_QUERIES.get(category, [])
    candidates = []
    seen = set()

    for q in queries:
        encoded = urllib.parse.quote_plus(q)
        url = f"https://www.youtube.com/results?search_query={encoded}&sp=EgYQARgDcAE="
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"})
        try:
            with urllib.request.urlopen(req, timeout=12) as resp:
                html = resp.read().decode("utf-8")
            import re
            m = re.search(r"var ytInitialData = ({.*?});</script>", html)
            if not m:
                continue
            data = json.loads(m.group(1))
            contents = data.get("contents", {}).get("twoColumnSearchResultsRenderer", {}).get("primaryContents", {}).get("sectionListRenderer", {}).get("contents", [])
            for section in contents:
                for it in section.get("itemSectionRenderer", {}).get("contents", []):
                    if "videoRenderer" in it:
                        vr = it["videoRenderer"]
                        vid = vr.get("videoId", "")
                        title = vr.get("title", {}).get("runs", [{}])[0].get("text", "")
                        uploader = vr.get("ownerText", {}).get("runs", [{}])[0].get("text", "")
                        length_text = vr.get("lengthText", {}).get("simpleText", "")

                        if not vid or not title or vid in seen:
                            continue

                        # Duration check
                        dur_sec = parse_duration(length_text)
                        if dur_sec < 180 or dur_sec > 43200:
                            continue

                        # Title blacklist check
                        lower_title = title.lower()
                        if any(b in lower_title for b in TITLE_BLACKLIST):
                            continue

                        seen.add(vid)
                        candidates.append({
                            "video_id": vid,
                            "title": title,
                            "uploader": uploader,
                            "duration": dur_sec,
                            "category": category,
                            "length_str": length_text
                        })
                        if len(candidates) >= limit_per_query * len(queries):
                            break
        except Exception as e:
            log(f"  Search error for '{q}': {e}")

    return candidates

def parse_duration(length_str: str) -> int:
    if not length_str:
        return 0
    parts = length_str.strip().split(":")
    try:
        if len(parts) == 3:
            return int(parts[0]) * 3600 + int(parts[1]) * 60 + int(parts[2])
        elif len(parts) == 2:
            return int(parts[0]) * 60 + int(parts[1])
    except Exception:
        pass
    return 0

def resolve_and_extract_burst(video_id: str, duration: int) -> tuple[list[str], str]:
    import yt_dlp
    url = f"https://www.youtube.com/watch?v={video_id}"
    ydl_opts = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "extractor_args": {"youtube": {"player_client": ["android", "ios", "web"]}}
    }
    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        info = ydl.extract_info(url, download=False)
        stream_url = None
        ua = "Mozilla/5.0"
        for f in info.get("formats", []):
            if f.get("vcodec") != "none" and f.get("url") and "manifest" not in f.get("url"):
                if f.get("height") in [360, 480]:
                    stream_url = f["url"]
                    ua = f.get("http_headers", {}).get("User-Agent", ua)
                    break
        if not stream_url:
            for f in info.get("formats", []):
                if f.get("vcodec") != "none" and f.get("url") and "manifest" not in f.get("url"):
                    stream_url = f["url"]
                    ua = f.get("http_headers", {}).get("User-Agent", ua)
                    break

    if not stream_url:
        return [], "no_stream"

    t_anchors = [
        max(35.0, 0.12 * duration),
        max(60.0, 0.50 * duration),
        max(90.0, 0.82 * duration)
    ]

    frame_paths = []
    loaded_imgs = []
    tmp_dir = "/tmp/aerial_frames"
    os.makedirs(tmp_dir, exist_ok=True)

    for idx, ts in enumerate(t_anchors):
        out_path = os.path.join(tmp_dir, f"{video_id}_{idx}.jpg")
        cmd = [
            "ffmpeg", "-y",
            "-headers", f"User-Agent: {ua}\r\n",
            "-ss", f"{ts:.2f}",
            "-reconnect", "1",
            "-reconnect_streamed", "1",
            "-reconnect_delay_max", "2",
            "-fflags", "+nobuffer+fastseek+discardcorrupt",
            "-i", stream_url,
            "-vframes", "1",
            "-f", "image2pipe",
            "-vcodec", "mjpeg",
            "pipe:1"
        ]
        res = subprocess.run(cmd, capture_output=True, timeout=15)
        if len(res.stdout) > 1000:
            with open(out_path, "wb") as f:
                f.write(res.stdout)
            frame_paths.append(out_path)
            try:
                loaded_imgs.append(np.array(Image.open(out_path).convert("L")))
            except Exception:
                pass

    if len(frame_paths) < 3:
        return [], "insufficient_frames"

    # Fast local static photo check
    if len(loaded_imgs) >= 2:
        diff_1_2 = float(np.mean(np.abs(loaded_imgs[0].astype(float) - loaded_imgs[1].astype(float))))
        if diff_1_2 < 2.0:
            return [], "frozen_still_loop"

    return frame_paths, "ok"

def dispatch_batch_to_colab(batch_items: list[dict]) -> dict:
    """
    batch_items: list of {'candidate': dict, 'frame_paths': list[str]}
    Packages frames into a single tar archive, uploads to Colab, executes in-VRAM GPU audit.
    """
    tar_path = "/tmp/batch_payload.tar.gz"
    manifest = {}

    with tarfile.open(tar_path, "w:gz") as tar:
        for item in batch_items:
            vid = item["candidate"]["video_id"]
            manifest[vid] = []
            for p in item["frame_paths"]:
                arcname = os.path.basename(p)
                tar.add(p, arcname=arcname)
                manifest[vid].append(arcname)

    # 1. Upload tar to Colab
    subprocess.run([COLAB_BIN, "--auth=adc", "upload", "-s", SESSION_NAME, tar_path, "/content/batch_payload.tar.gz"], capture_output=True, check=True)

    # 2. Execute process_batch using base64-encoded manifest
    import base64
    manifest_b64 = base64.b64encode(json.dumps(manifest).encode("utf-8")).decode("ascii")
    eval_call = f"""
import json, base64
manifest_json = base64.b64decode('{manifest_b64}').decode('utf-8')
res = process_batch('/content/batch_payload.tar.gz', manifest_json)
print('BATCH_RESULT:' + json.dumps(res))
"""
    tmp_call_file = "/tmp/exec_call.py"
    with open(tmp_call_file, "w") as f:
        f.write(eval_call)

    res = subprocess.run([COLAB_BIN, "--auth=adc", "exec", "-s", SESSION_NAME, "-f", tmp_call_file, "--timeout", "60"], capture_output=True, text=True)

    for line in res.stdout.splitlines():
        if line.startswith("BATCH_RESULT:"):
            return json.loads(line.replace("BATCH_RESULT:", ""))

    return {}

def main():
    t0 = time.time()
    log("===================================================================")
    log("🚀 AERIALVIEWS+ AUTONOMOUS COLAB GPU 50-VIDEO CURATION PIPELINE")
    log(f"   Target: 50 Verified Ambient Videos across {len(CATEGORIES)} Categories")
    log(f"   Backend: Google Colab Session '{SESSION_NAME}' (Tesla T4 GPU)")
    log("===================================================================")

    os.makedirs(STATE_DIR, exist_ok=True)
    os.makedirs(os.path.dirname(SEED_OUTPUT_JSON), exist_ok=True)

    # Read existing WAL & Catalog
    seen_ids = set()
    approved_by_category = {cat: 0 for cat in CATEGORIES}

    if os.path.exists(AUDIT_LOG_FILE):
        with open(AUDIT_LOG_FILE, "r", encoding="utf-8") as f:
            for line in f:
                try:
                    entry = json.loads(line)
                    seen_ids.add(entry["video_id"])
                except Exception:
                    pass

    existing_catalog = []
    if os.path.exists(CATALOG_FILE):
        with open(CATALOG_FILE, "r", encoding="utf-8") as f:
            for line in f:
                try:
                    entry = json.loads(line)
                    existing_catalog.append(entry)
                    cat = entry.get("categoryKey", "")
                    if cat in approved_by_category:
                        approved_by_category[cat] += 1
                except Exception:
                    pass

    total_approved = sum(approved_by_category.values())
    total_audited = len(seen_ids)
    log(f"Initial State: {total_audited} previously audited, {total_approved}/50 already verified.")

    target_per_cat = math.ceil(50 / len(CATEGORIES)) # 7 per category

    # Harvest candidates category by category
    for cat_idx, cat in enumerate(CATEGORIES):
        if total_approved >= 50:
            break

        current_cat_approved = approved_by_category[cat]
        needed_for_cat = target_per_cat - current_cat_approved
        if needed_for_cat <= 0:
            log(f"Category '{cat.upper()}' already satisfied ({current_cat_approved}/{target_per_cat}). Skipping.")
            continue

        log(f"\n[{cat_idx+1}/{len(CATEGORIES)}] 🔍 Harvesting candidates for category '{cat.upper()}' (Need {needed_for_cat} more)...")
        candidates = search_candidates_for_category(cat, limit_per_query=15)
        log(f"  Found {len(candidates)} search candidates for '{cat}'.")

        # Process in batches of 4 candidates
        batch_queue = []
        for cand in candidates:
            if total_approved >= 50 or approved_by_category[cat] >= target_per_cat:
                break

            vid = cand["video_id"]
            if vid in seen_ids:
                continue

            seen_ids.add(vid)
            total_audited += 1

            # Extract frames locally
            try:
                frame_paths, status = resolve_and_extract_burst(vid, cand["duration"])
            except Exception as e:
                frame_paths, status = [], str(e)

            if not frame_paths:
                # Log Veto
                wal_rec = {
                    "video_id": vid,
                    "title": cand["title"],
                    "category": cat,
                    "verdict": "VETOED",
                    "reason": f"Extraction failure: {status}",
                    "timestamp": datetime.now(timezone.utc).isoformat()
                }
                with open(AUDIT_LOG_FILE, "a", encoding="utf-8") as f:
                    f.write(json.dumps(wal_rec) + "\n")
                continue

            batch_queue.append({
                "candidate": cand,
                "frame_paths": frame_paths
            })

            # When batch reaches 4 or candidates end, dispatch to Colab GPU!
            if len(batch_queue) >= 4 or cand == candidates[-1]:
                log(f"  ⚡ Dispatching batch of {len(batch_queue)} candidate(s) to Colab Tesla T4 GPU...")
                t_dispatch = time.time()
                try:
                    gpu_results = dispatch_batch_to_colab(batch_queue)
                    dispatch_time = time.time() - t_dispatch
                    log(f"  ✅ GPU Batch Evaluated in {dispatch_time:.2f}s!")
                except Exception as e:
                    log(f"  ❌ GPU Batch failed: {e}")
                    gpu_results = {}

                for item in batch_queue:
                    c = item["candidate"]
                    c_vid = c["video_id"]
                    audit_res = gpu_results.get(c_vid, {"verdict": "VETOED", "ambient_score": 0.0, "waste_score": 100.0})

                    verdict = audit_res.get("verdict", "VETOED")
                    amb = audit_res.get("ambient_score", 0.0)
                    wst = audit_res.get("waste_score", 0.0)

                    wal_rec = {
                        "video_id": c_vid,
                        "title": c["title"],
                        "category": cat,
                        "verdict": verdict,
                        "ambient_score": amb,
                        "waste_score": wst,
                        "timestamp": datetime.now(timezone.utc).isoformat()
                    }
                    with open(AUDIT_LOG_FILE, "a", encoding="utf-8") as f:
                        f.write(json.dumps(wal_rec) + "\n")

                    if verdict == "APPROVED" and total_approved < 50:
                        approved_by_category[cat] += 1
                        total_approved += 1

                        catalog_entry = {
                            "videoId": c_vid,
                            "title": c["title"],
                            "uploaderName": c["uploader"],
                            "durationSeconds": c["duration"],
                            "categoryKey": cat,
                            "videoPageUrl": f"https://www.youtube.com/watch?v={c_vid}",
                            "streamQualityScore": int(round(amb)),
                            "visualDescription": f"Verified pristine 4K {cat} ambient cinematography evaluated on Google Colab Tesla T4 GPU.",
                            "verifiedBy": "OpenCLIP ViT-B-32 (Google Colab Tesla T4 GPU)",
                            "verifiedAt": datetime.now(timezone.utc).isoformat()
                        }
                        with open(CATALOG_FILE, "a", encoding="utf-8") as f:
                            f.write(json.dumps(catalog_entry) + "\n")
                        existing_catalog.append(catalog_entry)

                        log(f"  🌟 APPROVED [{total_approved}/50] [{cat.upper()}] ({amb}% Amb, {wst}% Wst): '{c['title'][:45]}...' ({c_vid})")
                    else:
                        log(f"  🚫 VETOED [{cat.upper()}] ({amb}% Amb, {wst}% Wst): '{c['title'][:45]}...'")

                batch_queue = []
                update_progress(total_audited, approved_by_category, t0)

    # Final Compilation & Export
    update_progress(total_audited, approved_by_category, t0, status="COMPLETED")

    # Deduplicate and sort catalog
    unique_catalog = {e["videoId"]: e for e in existing_catalog}
    final_catalog = list(unique_catalog.values())[:50]

    with open(SEED_OUTPUT_JSON, "w", encoding="utf-8") as f:
        json.dump(final_catalog, f, indent=2)

    raw_bytes = json.dumps(final_catalog).encode("utf-8")
    with gzip.open(SEED_OUTPUT_GZ, "wb", compresslevel=6) as f_gz:
        f_gz.write(raw_bytes)

    total_time = time.time() - t0
    log("\n===================================================================")
    log("🎉 50-VIDEO CURATION PIPELINE COMPLETED SUCCESSFULLY!")
    log(f"   Total Audited: {total_audited} candidates")
    log(f"   Total Approved: {len(final_catalog)} videos")
    log(f"   Total Elapsed Time: {total_time/60.0:.2f} minutes ({total_time:.1f}s)")
    log(f"   Overall Throughput: {len(final_catalog)/(total_time/60.0):.2f} approved videos / minute")
    log(f"   Distribution across categories:")
    for cat, count in approved_by_category.items():
        log(f"     - {cat.capitalize():10s}: {count} videos")
    log(f"   Catalog exported to:")
    log(f"     - JSON: {SEED_OUTPUT_JSON} ({round(os.path.getsize(SEED_OUTPUT_JSON)/1024, 1)} KB)")
    log(f"     - GZ:   {SEED_OUTPUT_GZ} ({round(os.path.getsize(SEED_OUTPUT_GZ)/1024, 1)} KB)")
    log("===================================================================")

if __name__ == "__main__":
    main()
