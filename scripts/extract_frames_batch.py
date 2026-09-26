#!/usr/bin/env python3
"""
Production-grade script for the AerialViews+ 10K curation pipeline.
Extracts 6 strategic frames per video using yt-dlp + ffmpeg directly from stream URLs.
"""

import os
import sys
import json
import time
import glob
import random
import tarfile
import argparse
import subprocess
from pathlib import Path
from datetime import datetime

import numpy as np
from PIL import Image

# Directories relative to project root (assume script is in scripts/)
PROJECT_ROOT = Path(__file__).resolve().parent.parent
RAW_CANDIDATES = PROJECT_ROOT / "curation_state" / "10k_raw_candidates.jsonl"
EXTRACTED_METADATA = PROJECT_ROOT / "curation_state" / "10k_extracted_frames.jsonl"
STATE_FILE = PROJECT_ROOT / "curation_state" / "10k_extraction_state.json"
FRAMES_DIR = PROJECT_ROOT / "frames_10k"
BATCHES_DIR = PROJECT_ROOT / "curation_state" / "frame_batches"

BATCH_SIZE = 500

def log(msg):
    timestamp = datetime.now().strftime("%H:%M:%S")
    print(f"[{timestamp}] {msg}", flush=True)

def setup_dirs():
    for d in [FRAMES_DIR, BATCHES_DIR, PROJECT_ROOT / "curation_state"]:
        d.mkdir(parents=True, exist_ok=True)

def load_state():
    if STATE_FILE.exists():
        with open(STATE_FILE, "r") as f:
            try:
                return json.load(f)
            except json.JSONDecodeError:
                return {"extracted": [], "failed": [], "frozen": []}
    return {"extracted": [], "failed": [], "frozen": []}

def save_state(state):
    with open(STATE_FILE, "w") as f:
        json.dump(state, f, indent=2)

def load_candidates():
    if not RAW_CANDIDATES.exists():
        log(f"Error: Candidates file {RAW_CANDIDATES} not found.")
        sys.exit(1)
    
    candidates = []
    with open(RAW_CANDIDATES, "r") as f:
        for line in f:
            if line.strip():
                try:
                    candidates.append(json.loads(line))
                except json.JSONDecodeError:
                    continue
    return candidates

def get_stream_url(video_id, cookies_file=None):
    """Resolve stream URL using yt_dlp Python library directly."""
    try:
        import yt_dlp
    except ImportError:
        log("Error: yt_dlp package not installed.")
        return None, None

    url = f"https://www.youtube.com/watch?v={video_id}"
    ydl_opts = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "extractor_args": {"youtube": {"player_client": ["android", "ios", "web"]}}
    }
    if cookies_file:
        ydl_opts["cookiefile"] = cookies_file

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=False)
            stream_url = None
            ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
            
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
            return stream_url, ua
    except Exception as e:
        log(f"yt-dlp error for {video_id}: {e}")
        return None, None

def extract_frames(stream_url, video_id, duration, ua=None):
    timestamps = [
        max(15, 0.08 * duration),
        max(30, 0.22 * duration),
        max(60, 0.42 * duration),
        max(90, 0.62 * duration),
        max(120, 0.80 * duration),
        max(150, 0.94 * duration)
    ]
    
    video_dir = FRAMES_DIR / video_id
    video_dir.mkdir(parents=True, exist_ok=True)
    
    start_time = time.time()
    for i, t in enumerate(timestamps):
        out_path = video_dir / f"{video_id}_f{i}.jpg"
        
        cmd = ["ffmpeg", "-y"]
        if ua:
            cmd.extend(["-headers", f"User-Agent: {ua}\r\n"])
        cmd.extend([
            "-ss", f"{t:.2f}",
            "-reconnect", "1",
            "-reconnect_streamed", "1",
            "-reconnect_delay_max", "2",
            "-fflags", "+nobuffer+fastseek+discardcorrupt",
            "-i", stream_url,
            "-frames:v", "1",
            "-q:v", "2",
            "-loglevel", "error",
            str(out_path)
        ])
        
        try:
            subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
        except subprocess.TimeoutExpired:
            log(f"ffmpeg timeout on {video_id} frame {i}")
            return False, 0
            
    # Check if all frames were created
    for i in range(6):
        if not (video_dir / f"{video_id}_f{i}.jpg").exists():
            return False, 0
            
    elapsed = time.time() - start_time
    return True, elapsed

def check_frozen_video(video_id):
    """Compare frame 0 and frame 2. Returns True if considered frozen/still."""
    f0_path = FRAMES_DIR / video_id / f"{video_id}_f0.jpg"
    f2_path = FRAMES_DIR / video_id / f"{video_id}_f2.jpg"
    
    if not (f0_path.exists() and f2_path.exists()):
        return False
        
    try:
        im0 = Image.open(f0_path).convert('L')
        im2 = Image.open(f2_path).convert('L')
        
        arr0 = np.array(im0).astype(np.float32)
        arr2 = np.array(im2).astype(np.float32)
        
        # Mean absolute difference
        diff = np.mean(np.abs(arr0 - arr2))
        return diff < 2.0
    except Exception as e:
        log(f"Error checking frozen state for {video_id}: {e}")
        return False

def package_batch(batch_num, video_ids, candidates_map=None):
    tar_name = BATCHES_DIR / f"batch_{batch_num:03d}.tar.gz"
    log(f"Packaging {len(video_ids)} videos into {tar_name.name}...")
    
    if candidates_map is None:
        candidates_map = {}
        for c in load_candidates():
            candidates_map[c.get('video_id')] = c
            
    manifest = {}
    
    with tarfile.open(tar_name, "w:gz") as tar:
        for vid in video_ids:
            vid_dir = FRAMES_DIR / vid
            frames = sorted(list(vid_dir.glob("*.jpg")))
            if not frames:
                continue
                
            frame_names = []
            for frame in frames:
                tar.add(frame, arcname=f"{vid}/{frame.name}")
                frame_names.append(frame.name)
            
            c = candidates_map.get(vid, {})
            manifest[vid] = {
                "videoId": vid,
                "title": c.get('title', ''),
                "uploaderName": c.get('uploader', ''),
                "durationSeconds": c.get('duration_seconds', c.get('duration', 300)),
                "categoryKey": c.get('category', 'drone'),
                "frames": frame_names
            }
            
        # Add manifest
        manifest_path = PROJECT_ROOT / "curation_state" / f"manifest_{batch_num:03d}.json"
        with open(manifest_path, "w") as f:
            json.dump(manifest, f, indent=2)
            
        tar.add(manifest_path, arcname="manifest.json")
        manifest_path.unlink() # clean up
        
    log(f"Packaged {tar_name.name}")
    
    # Optionally remove local frames to save space? We will just keep them as per requirements.
    # We could implement a cleanup flag if needed.

def main():
    parser = argparse.ArgumentParser(description="Extract frames from YouTube videos.")
    parser.add_argument("--limit", type=int, default=None, help="Process first N candidates")
    parser.add_argument("--resume", action="store_true", help="Continue from last checkpoint")
    parser.add_argument("--cookies", type=str, default=None, help="Use browser cookies file")
    parser.add_argument("--stats", action="store_true", help="Print extraction stats and exit")
    parser.add_argument("--package", action="store_true", help="Only package existing frames into tar.gz batches")
    
    args = parser.parse_args()
    
    setup_dirs()
    state = load_state()
    
    if args.stats:
        print("Extraction Statistics:")
        print(f"Extracted: {len(state['extracted'])}")
        print(f"Failed:    {len(state['failed'])}")
        print(f"Frozen:    {len(state['frozen'])}")
        return
        
    candidates = load_candidates()
    
    if args.package:
        # Package whatever is currently in frames_10k
        existing_vids = [d.name for d in FRAMES_DIR.iterdir() if d.is_dir()]
        existing_vids = sorted(existing_vids)
        
        batch_num = 1
        for i in range(0, len(existing_vids), BATCH_SIZE):
            batch_vids = existing_vids[i:i+BATCH_SIZE]
            package_batch(batch_num, batch_vids)
            batch_num += 1
        return

    processed_set = set(state['extracted']) | set(state['failed']) | set(state['frozen'])
    
    to_process = []
    if args.resume:
        to_process = [c for c in candidates if c['video_id'] not in processed_set]
    else:
        to_process = candidates
        # Reset state if not resuming
        state = {"extracted": [], "failed": [], "frozen": []}
        
    if args.limit:
        to_process = to_process[:args.limit]
        
    log(f"Starting extraction for {len(to_process)} videos...")
    
    consecutive_failures = 0
    total_processed = 0
    
    batch_vids = []
    current_batch_num = (len(state['extracted']) // BATCH_SIZE) + 1
    
    # Load previously pending batch_vids if resuming
    if args.resume and len(state['extracted']) % BATCH_SIZE != 0:
        batch_vids = state['extracted'][-(len(state['extracted']) % BATCH_SIZE):]

    for i, candidate in enumerate(to_process):
        vid = candidate['video_id']
        duration = candidate.get('duration', 300) # Fallback to 5 mins
        title = candidate.get('title', 'Unknown Title')
        
        # Sleep for rate limiting
        time.sleep(random.uniform(1.0, 2.0))
        
        stream_url, ua = get_stream_url(vid, cookies_file=args.cookies)
        
        if not stream_url:
            log(f"Failed to resolve stream for {vid}")
            state['failed'].append(vid)
            consecutive_failures += 1
        else:
            success, elapsed = extract_frames(stream_url, vid, duration, ua=ua)
            if success:
                consecutive_failures = 0
                if check_frozen_video(vid):
                    state['frozen'].append(vid)
                    log(f"[{i+1}/{len(to_process)}] Skipped frozen video '{title}'")
                else:
                    state['extracted'].append(vid)
                    batch_vids.append(vid)
                    
                    # Log to metadata
                    meta_entry = candidate.copy()
                    meta_entry['extracted_at'] = datetime.now().isoformat()
                    with open(EXTRACTED_METADATA, "a") as f:
                        f.write(json.dumps(meta_entry) + "\\n")
                        
                    log(f"[{i+1}/{len(to_process)}] Extracted 6 frames for '{title[:30]}...' in {elapsed:.1f}s")
            else:
                state['failed'].append(vid)
                consecutive_failures += 1
                log(f"Failed to extract frames for {vid}")
                
        total_processed += 1
        
        if total_processed % 10 == 0:
            save_state(state)
            
        if len(batch_vids) >= BATCH_SIZE:
            package_batch(current_batch_num, batch_vids)
            current_batch_num += 1
            batch_vids = []
            
        if consecutive_failures >= 5:
            log("5 consecutive failures. Pausing for 60 seconds to avoid IP ban...")
            time.sleep(60)
            consecutive_failures = 0
            
    # Save final state
    save_state(state)
    
    # Package remaining
    if len(batch_vids) > 0:
        package_batch(current_batch_num, batch_vids)
        
    log("Extraction complete.")

if __name__ == "__main__":
    main()
