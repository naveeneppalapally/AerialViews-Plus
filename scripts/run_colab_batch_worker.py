#!/usr/bin/env python3
"""
run_colab_batch_worker.py: Orchestrates candidate extraction locally
and executes batch GPU evaluation on active Google Colab session.
"""

import os
import sys
import io
import time
import json
import subprocess
from datetime import datetime, timezone
from PIL import Image

COLAB_BIN = os.path.expanduser("~/.colab_venv/bin/colab")
SESSION_NAME = "aerial-sample"
STATE_FILE = "curation_state/sample_audit_results.jsonl"

SAMPLE_CANDIDATES = [
    {
        "video_id": "LXb3EKWsInQ",
        "title": "COSTA RICA IN 4K 60fps HDR (ULTRA HD)",
        "uploader": "Jacob + Katie Schwarz",
        "category": "nature"
    },
    {
        "video_id": "1La4QzGeaaQ",
        "title": "Norway 4K - Scenic Relaxation Film With Calming Music",
        "uploader": "Scenic Relaxation",
        "category": "drone"
    },
    {
        "video_id": "ysz5S6PUM-U",
        "title": "A Walk in the Park - Talking Vlog",
        "uploader": "Sample Vlogger",
        "category": "nature"
    },
    {
        "video_id": "h3fUgOKFMNU",
        "title": "Tokyo in 4K - City of Neon Lights and Glass",
        "uploader": "Tokyo Ambient",
        "category": "cities"
    },
    {
        "video_id": "nO_dWBkG_pQ",
        "title": "Undersea 4K Ultra HD Coral Reef Fish Underwater",
        "uploader": "Ocean Life",
        "category": "ocean"
    },
    {
        "video_id": "e-ORhEE9VVg",
        "title": "Music Video Pop Artist with Lyrics and Faces",
        "uploader": "Pop Music Official",
        "category": "cities"
    }
]

def log_progress(msg: str):
    ts = datetime.now().strftime("%H:%M:%S")
    print(f"[{ts}] {msg}", flush=True)

def resolve_and_extract_frames(video_id: str) -> list[str]:
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
        duration = info.get("duration", 300)
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
        raise RuntimeError("Could not resolve playable stream URL")

    # Extract 3 stratified frames
    t_anchors = [
        max(35.0, 0.15 * duration),
        max(60.0, 0.50 * duration),
        max(90.0, 0.80 * duration)
    ]
    frame_paths = []
    for idx, ts in enumerate(t_anchors):
        out_path = f"/tmp/{video_id}_frame_{idx}.jpg"
        cmd = [
            "ffmpeg", "-y",
            "-headers", f"User-Agent: {ua}\r\n",
            "-ss", f"{ts:.2f}",
            "-reconnect", "1",
            "-i", stream_url,
            "-vframes", "1",
            "-f", "image2pipe",
            "-vcodec", "mjpeg",
            "pipe:1"
        ]
        res = subprocess.run(cmd, capture_output=True, timeout=20)
        if len(res.stdout) > 1000:
            with open(out_path, "wb") as f:
                f.write(res.stdout)
            frame_paths.append(out_path)

    return frame_paths

def run_colab_evaluation(video_id: str, frame_paths: list[str], category: str) -> dict:
    # 1. Upload frames to Colab
    colab_frame_names = []
    for p in frame_paths:
        base_name = os.path.basename(p)
        remote_path = f"/content/{base_name}"
        subprocess.run([COLAB_BIN, "--auth=adc", "upload", "-s", SESSION_NAME, p, remote_path], capture_output=True, check=True)
        colab_frame_names.append(remote_path)

    # 2. Prepare Colab Python Evaluation script
    eval_script = f"""
import torch, open_clip, json, numpy as np, cv2
from PIL import Image

device = 'cuda' if torch.cuda.is_available() else 'cpu'
model, _, preprocess = open_clip.create_model_and_transforms('ViT-B-32', pretrained='laion2b_s34b_b79k', device=device)
tokenizer = open_clip.get_tokenizer('ViT-B-32')

ambient_prompts = [
    'an aerial drone flyover of scenic landscape',
    'a peaceful cinematic nature landscape of forests, waterfalls, or mountains',
    'an underwater coral reef with clear blue water and tropical fish',
    'a high-angle cinematic city skyline timelapse with architecture'
]
waste_prompts = [
    'a person, human face, or vlogger talking directly to the camera',
    'a YouTube thumbnail with large bold title text graphics watermark',
    'a point of view handheld walking tour down a sidewalk or street',
    'a singer or pop music video performance with human faces'
]

all_prompts = ambient_prompts + waste_prompts
tokens = tokenizer(all_prompts).to(device)
with torch.no_grad():
    text_feats = model.encode_text(tokens)
    text_feats /= text_feats.norm(dim=-1, keepdim=True)

frame_files = {json.dumps(colab_frame_names)}
scores = []
for fpath in frame_files:
    img = Image.open(fpath)
    t = torch.stack([preprocess(img)]).to(device)
    with torch.no_grad():
        with torch.amp.autocast('cuda' if device == 'cuda' else 'cpu'):
            img_feats = model.encode_image(t)
            img_feats /= img_feats.norm(dim=-1, keepdim=True)
            scale = model.logit_scale.exp().item()
            logits = scale * (img_feats @ text_feats.T)
            probs = logits.softmax(dim=-1)[0].cpu().numpy()

    amb = float(probs[:len(ambient_prompts)].sum() * 100.0)
    wst = float(probs[len(ambient_prompts):].sum() * 100.0)
    top = int(probs.argmax())
    scores.append({{'ambient': amb, 'waste': wst, 'top_prompt': all_prompts[top]}})

mean_amb = float(np.mean([s['ambient'] for s in scores]))
mean_wst = float(np.mean([s['waste'] for s in scores]))
verdict = 'APPROVED' if (mean_amb >= 65.0 and mean_wst <= 35.0) else 'VETOED'

print('RESULT_JSON:' + json.dumps({{'verdict': verdict, 'ambient_score': round(mean_amb, 1), 'waste_score': round(mean_wst, 1), 'details': scores}}))
"""
    tmp_script = f"/tmp/eval_{video_id}.py"
    with open(tmp_script, "w") as f:
        f.write(eval_script)

    res = subprocess.run([COLAB_BIN, "--auth=adc", "exec", "-s", SESSION_NAME, "-f", tmp_script], capture_output=True, text=True, timeout=60)
    out = res.stdout
    for line in out.splitlines():
        if line.startswith("RESULT_JSON:"):
            return json.loads(line.replace("RESULT_JSON:", ""))

    return {"verdict": "ERROR", "reason": "No JSON output from Colab", "raw": out[-300:]}

def main():
    log_progress(f"Starting Colab GPU Sample Curation on session '{SESSION_NAME}'...")
    log_progress(f"Queue size: {len(SAMPLE_CANDIDATES)} candidate videos.")

    os.makedirs(os.path.dirname(STATE_FILE), exist_ok=True)

    for i, cand in enumerate(SAMPLE_CANDIDATES):
        vid = cand["video_id"]
        title = cand["title"]
        category = cand["category"]
        log_progress(f"[{i+1}/{len(SAMPLE_CANDIDATES)}] Auditing '{title}' ({vid}) [{category.upper()}]...")

        try:
            t0 = time.time()
            frame_paths = resolve_and_extract_frames(vid)
            if not frame_paths:
                log_progress(f"  ❌ Failed to extract frames for {vid}")
                continue

            log_progress(f"  ⚡ Extracted {len(frame_paths)} frames. Dispatching to Colab Tesla T4 GPU...")
            eval_result = run_colab_evaluation(vid, frame_paths, category)

            verdict = eval_result.get("verdict", "ERROR")
            amb = eval_result.get("ambient_score", 0.0)
            wst = eval_result.get("waste_score", 0.0)
            elapsed = time.time() - t0

            record = {
                "videoId": vid,
                "title": title,
                "categoryKey": category,
                "verdict": verdict,
                "ambientScore": amb,
                "wasteScore": wst,
                "evaluatedOn": "Google Colab (Tesla T4 GPU)",
                "latencySec": round(elapsed, 2),
                "timestamp": datetime.now(timezone.utc).isoformat()
            }

            with open(STATE_FILE, "a", encoding="utf-8") as f:
                f.write(json.dumps(record) + "\n")

            if verdict == "APPROVED":
                log_progress(f"  🌟 APPROVED ({amb}% Ambient, {wst}% Waste) in {elapsed:.2f}s")
            else:
                log_progress(f"  🚫 VETOED ({amb}% Ambient, {wst}% Waste) in {elapsed:.2f}s")

        except Exception as e:
            log_progress(f"  ⚠️ Error auditing {vid}: {e}")

    log_progress(f"Sample run complete! All results saved to {STATE_FILE}.")

if __name__ == "__main__":
    main()
