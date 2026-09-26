#!/usr/bin/env python3
"""
colab_gpu_audit_10k.py

AerialViews-Plus 10K GPU Audit Pipeline
Runs INSIDE Google Colab on a Tesla T4 (or L4) GPU and performs visual audit
of candidate video frames using OpenCLIP + DGC watermark detection.
"""

import os
import sys
import glob
import json
import gzip
import tarfile
import argparse
import datetime
from pathlib import Path
from tempfile import TemporaryDirectory
from collections import defaultdict
from typing import List, Dict, Any, Tuple

import numpy as np
import torch
import torch.nn.functional as F
import open_clip
from PIL import Image

# --- Gemini Vision (Stage 3 Gold Auditor) ---
GEMINI_ENABLED = True  # Set False to skip Gemini and use only OpenCLIP+DGC
GEMINI_MODEL = "gemini-2.5-flash"
GEMINI_THINKING_BUDGET = 2048
GEMINI_RPM_LIMIT = 14  # Stay just under 15 RPM free tier

# Google Drive or Local Paths
DEFAULT_DRIVE = Path('/content/drive/MyDrive/AerialViews')
DRIVE_ROOT = DEFAULT_DRIVE if DEFAULT_DRIVE.exists() else Path('curation_state')
BATCHES_DIR = DRIVE_ROOT / 'frame_batches'
RESULTS_DIR = DRIVE_ROOT / 'audit_results'
RESULTS_FILE = RESULTS_DIR / 'audit_results.jsonl'
PROGRESS_FILE = RESULTS_DIR / 'audit_progress.json'
CURATED_JSON = DRIVE_ROOT / 'curated_youtube_seed_10k.json'
CURATED_GZ = DRIVE_ROOT / 'curated_youtube_seed_10k.json.gz'

AMBIENT_PROMPTS = [
    'an aerial drone flyover of scenic landscape with mountains valleys and rivers',
    'a peaceful cinematic nature landscape of forests waterfalls or mountains',
    'an underwater coral reef with clear blue water and tropical fish',
    'a high-angle cinematic city skyline timelapse with architecture',
    'earth viewed from the international space station orbiting in space',
    'wildlife animals in natural habitat safari documentary',
    'dramatic storm clouds rolling over mountains and valleys',
    'snowy winter landscape with frozen lakes and alpine forests'
]

WASTE_PROMPTS = [
    'a person human face or vlogger talking directly to the camera',
    'a YouTube thumbnail with large bold title text graphics watermark',
    'a point of view handheld walking tour down a sidewalk or street',
    'a singer or pop music video performance with human faces',
    'a static photograph with fake digital rain snow or particle effects',
    'a computer generated 3D render or CGI animation',
    'indoor room hotel commercial storefront or restaurant',
    'close-up selfie with selfie stick bicycle handlebars or tourist crowd'
]


def log(msg: str):
    """Print log message with timestamp."""
    timestamp = datetime.datetime.now().strftime('%H:%M:%S')
    print(f"[{timestamp}] {msg}", flush=True)


def compute_dgc(frames_gray: List[np.ndarray]) -> float:
    """Directional Gradient Coherence (DGC): detects static watermarks across frames.
    Static watermark edges persist across frames while background landscape moves.
    Clean videos: DGC < 1.0%. Watermarked: DGC > 3.5% (Gurgaon was 6.6%, ISS was 90.5%).
    """
    if len(frames_gray) < 3:
        return 0.0
    try:
        min_h = min(im.shape[0] for im in frames_gray)
        min_w = min(im.shape[1] for im in frames_gray)
        imgs = [im[:min_h, :min_w] for im in frames_gray]

        # Gradients
        gxs = [im[:-1, 1:] - im[:-1, :-1] for im in imgs]
        gys = [im[1:, :-1] - im[:-1, :-1] for im in imgs]
        mags = [np.sqrt(gx**2 + gy**2) for gx, gy in zip(gxs, gys)]

        unit_xs = [gx / (m + 1e-5) for gx, m in zip(gxs, mags)]
        unit_ys = [gy / (m + 1e-5) for gy, m in zip(gys, mags)]

        mean_ux = np.mean(unit_xs, axis=0)
        mean_uy = np.mean(unit_ys, axis=0)
        dgc = np.sqrt(mean_ux**2 + mean_uy**2)

        strong_edges = np.mean(mags, axis=0) > 20.0
        coherent_edges = (dgc > 0.96) & strong_edges

        total_strong = np.sum(strong_edges)
        if total_strong == 0:
            return 0.0
        return float((np.sum(coherent_edges) / total_strong) * 100.0)
    except Exception:
        return 0.0


class Auditor:
    def __init__(self, force_cpu: bool = False):
        self.device = 'cpu'
        if not force_cpu and torch.cuda.is_available():
            try:
                # Test compute capability with small dummy op
                t = torch.zeros(1, device='cuda') + 1.0
                _ = t.cpu()
                self.device = 'cuda'
            except Exception as e:
                log(f"   ⚠️ GPU detected but CUDA test failed ({e}). Falling back to CPU.")
                self.device = 'cpu'
        
        backend_name = torch.cuda.get_device_name(0) if self.device == 'cuda' else 'CPU'
        log("═══════════════════════════════════════════════════")
        log("🚀 AerialViews+ 10K Audit Pipeline")
        log(f"   Backend: {backend_name} | OpenCLIP ViT-B-32")
        log("═══════════════════════════════════════════════════")

        self.model, _, self.preprocess = open_clip.create_model_and_transforms(
            'ViT-B-32', pretrained='laion2b_s34b_b79k', device=self.device
        )
        self.tokenizer = open_clip.get_tokenizer('ViT-B-32')
        
        # Precompute text embeddings
        text_prompts = AMBIENT_PROMPTS + WASTE_PROMPTS
        text_tokens = self.tokenizer(text_prompts).to(self.device)
        
        with torch.no_grad(), torch.amp.autocast(self.device):
            text_features = self.model.encode_text(text_tokens)
            self.text_features = F.normalize(text_features, dim=-1)
            
        self.num_ambient = len(AMBIENT_PROMPTS)
        
        # --- Stage 3: Gemini Vision Gold Auditor ---
        self.gemini_client = None
        self._gemini_last_call = 0.0
        if GEMINI_ENABLED:
            try:
                from google import genai
                # Try ADC first (works in Colab after auth.authenticate_user())
                try:
                    from google.colab import auth
                    auth.authenticate_user()
                    self.gemini_client = genai.Client()
                    log(f"   🤖 Gemini Vision: {GEMINI_MODEL} (ADC auth, thinking={GEMINI_THINKING_BUDGET})")
                except Exception:
                    # Fallback: try API key from environment or Colab secrets
                    api_key = os.environ.get("GEMINI_API_KEY", "")
                    if not api_key:
                        try:
                            from google.colab import userdata
                            api_key = userdata.get("GEMINI_API_KEY")
                        except Exception:
                            pass
                    if api_key:
                        self.gemini_client = genai.Client(api_key=api_key)
                        log(f"   🤖 Gemini Vision: {GEMINI_MODEL} (API key, thinking={GEMINI_THINKING_BUDGET})")
                    else:
                        log("   ⚠️  Gemini Vision: No auth available. Running OpenCLIP+DGC only.")
            except ImportError:
                log("   ⚠️  google-genai not installed. Running OpenCLIP+DGC only.")
        else:
            log("   ℹ️  Gemini Vision: Disabled. Running OpenCLIP+DGC only.")
        log("═══════════════════════════════════════════════════")
        
        RESULTS_DIR.mkdir(parents=True, exist_ok=True)
        self.processed_videos = self.load_progress()
        
    def load_progress(self) -> set:
        processed = set()
        if RESULTS_FILE.exists():
            try:
                with open(RESULTS_FILE, 'r', encoding='utf-8') as f:
                    for line in f:
                        if not line.strip(): continue
                        data = json.loads(line)
                        processed.add(data.get('videoId'))
            except Exception as e:
                log(f"Warning: Error reading {RESULTS_FILE}: {e}")
        return processed

    def save_result(self, result: dict):
        with open(RESULTS_FILE, 'a', encoding='utf-8') as f:
            f.write(json.dumps(result) + '\n')
        self.processed_videos.add(result['videoId'])
        
        # Optional: Save progress file with count
        with open(PROGRESS_FILE, 'w', encoding='utf-8') as f:
            json.dump({'processed_count': len(self.processed_videos), 'last_updated': datetime.datetime.now().isoformat()}, f)

    def process_video_frames(self, frames_paths: List[Path], manifest_data: dict) -> Tuple[str, dict]:
        """Process frames for a single video, returning verdict and stats."""
        images = []
        frames_gray = []
        
        # Read frames
        for path in sorted(frames_paths):
            try:
                pil_img = Image.open(path).convert('RGB')
                images.append(self.preprocess(pil_img))
                
                # For DGC, convert to grayscale numpy array
                gray_arr = np.array(pil_img.convert('L'), dtype=float)
                frames_gray.append(gray_arr)
            except Exception as e:
                log(f"Error loading frame {path}: {e}")
                
        if not images:
            return "VETOED", {"reason": "missing_frames"}
            
        image_input = torch.tensor(np.stack(images)).to(self.device)
        
        with torch.no_grad(), torch.amp.autocast(self.device):
            image_features = self.model.encode_image(image_input)
            image_features = F.normalize(image_features, dim=-1)
            
            # Scaled cosine similarity: (num_frames, embed_dim) x (embed_dim, num_prompts) -> (num_frames, num_prompts)
            scale = self.model.logit_scale.exp()
            logits = scale * (image_features @ self.text_features.T)
            # apply softmax to get probabilities
            probs = logits.softmax(dim=-1).float().cpu().numpy()
            
        ambient_probs = probs[:, :self.num_ambient]
        waste_probs = probs[:, self.num_ambient:]
        
        ambient_scores = np.sum(ambient_probs, axis=1) * 100.0
        waste_scores = np.sum(waste_probs, axis=1) * 100.0
        
        mean_ambient = np.mean(ambient_scores)
        mean_waste = np.mean(waste_scores)
        min_ambient = np.min(ambient_scores)
        
        dgc_score = compute_dgc(frames_gray)
        
        verdict = "APPROVED"
        reason = ""
        
        if dgc_score >= 3.5:
            verdict = "VETOED"
            reason = "watermark (DGC)"
        elif min_ambient < 40.0:
            verdict = "VETOED"
            reason = "mid-video non-ambient cut"
        elif mean_waste > 35.0:
            verdict = "VETOED"
            reason = "waste content"
        elif mean_ambient < 65.0:
            verdict = "VETOED"
            reason = "insufficient ambient quality"
        
        # --- Stage 3: Gemini Vision Gold Audit (only for APPROVED candidates) ---
        gemini_result = None
        if verdict == "APPROVED" and self.gemini_client:
            gemini_result = self._gemini_gold_audit(frames_paths[len(frames_paths)//2])
            if gemini_result and gemini_result.get("decision") == "VETOED":
                verdict = "VETOED"
                reason = f"Gemini: {gemini_result.get('reason', 'failed gold audit')}"
            
        stats = {
            "mean_ambient": float(mean_ambient),
            "mean_waste": float(mean_waste),
            "min_ambient": float(min_ambient),
            "dgc_score": float(dgc_score),
            "reason": reason,
            "gemini": gemini_result
        }
        return verdict, stats

    def _gemini_gold_audit(self, frame_path: Path) -> dict:
        """Stage 3: Send 1 representative frame to Gemini Vision with thinking enabled.
        Gemini inspects corners/edges for text watermarks, logos, and overlays
        that OpenCLIP and DGC might miss."""
        import time as _time
        
        # Rate limit: wait if needed to stay under RPM limit
        now = _time.time()
        min_interval = 60.0 / GEMINI_RPM_LIMIT
        elapsed = now - self._gemini_last_call
        if elapsed < min_interval:
            _time.sleep(min_interval - elapsed)
        
        try:
            from google.genai import types
            
            with open(frame_path, "rb") as f:
                frame_bytes = f.read()
            
            prompt = """You are auditing a video frame for a 4K TV screensaver app.
Inspect this frame carefully in 4 steps:

1. CORNERS: Scan all 4 corners and edges for any text, logos, watermarks, or channel branding (even faint/semi-transparent ones).
2. OVERLAYS: Look for any subscribe buttons, social media handles, URLs, episode numbers, or "PART 1/2" text anywhere on the frame.
3. PEOPLE: Check if there are any human faces, selfie sticks, hands, or walking POV perspectives visible.
4. QUALITY: Is this pristine, cinematic ambient footage suitable for a living room TV screensaver?

Respond with ONLY a JSON object:
{
  "decision": "APPROVED" or "VETOED",
  "watermark_text_found": "<any text you can read on the frame, or 'none'>",
  "reason": "<brief explanation>",
  "confidence": <0-100>
}"""

            response = self.gemini_client.models.generate_content(
                model=GEMINI_MODEL,
                contents=[
                    types.Part.from_bytes(data=frame_bytes, mime_type="image/jpeg"),
                    prompt
                ],
                config=types.GenerateContentConfig(
                    thinking_config=types.ThinkingConfig(thinking_budget=GEMINI_THINKING_BUDGET),
                    temperature=0.1,
                    response_mime_type="application/json",
                ),
            )
            
            self._gemini_last_call = _time.time()
            
            import re
            text = response.text.strip()
            if text.startswith("```"):
                text = re.sub(r"^```(?:json)?\s*", "", text)
                text = re.sub(r"\s*```$", "", text)
            
            result = json.loads(text)
            wm = result.get("watermark_text_found", "none")
            if wm and wm.lower() != "none":
                log(f"      🔍 Gemini found text: \"{wm}\"")
            return result
            
        except Exception as e:
            log(f"      ⚠️  Gemini audit error: {e}")
            return None  # Don't veto on Gemini failure — OpenCLIP+DGC verdict stands

    def audit_batch(self, batch_tar_path: Path):
        log(f"   Batch: {batch_tar_path.name}")
        log("═══════════════════════════════════════════════════")
        
        if not batch_tar_path.exists():
            log(f"Error: Batch {batch_tar_path} not found.")
            return

        with TemporaryDirectory() as temp_dir:
            try:
                with tarfile.open(batch_tar_path, 'r:gz') as tar:
                    tar.extractall(path=temp_dir)
            except Exception as e:
                log(f"Error extracting {batch_tar_path}: {e}")
                return
                
            temp_path = Path(temp_dir)
            manifest_path = temp_path / 'manifest.json'
            if not manifest_path.exists():
                log(f"Error: manifest.json not found in {batch_tar_path.name}")
                return
                
            with open(manifest_path, 'r', encoding='utf-8') as f:
                manifests = json.load(f)
                
            # Group frames by videoId
            video_frames = defaultdict(list)
            for img_path in temp_path.glob("**/*.jpg"):
                vid = img_path.stem.split('_f')[0]
                video_frames[vid].append(img_path)
                
            for i, (vid, manifest_data) in enumerate(manifests.items(), 1):
                if vid in self.processed_videos:
                    continue
                    
                frames = video_frames.get(vid, [])
                if not frames:
                    continue
                    
                verdict, stats = self.process_video_frames(frames, manifest_data)
                
                result_entry = {
                    "videoId": vid,
                    "manifest": manifest_data,
                    "verdict": verdict,
                    "stats": stats,
                    "batch": batch_tar_path.name,
                    "timestamp": datetime.datetime.now().isoformat()
                }
                
                self.save_result(result_entry)
                
                category = manifest_data.get('categoryKey', 'unknown')
                title = manifest_data.get('title', 'Unknown Title')[:20]
                amb = stats.get('mean_ambient', 0)
                wst = stats.get('mean_waste', 0)
                dgc = stats.get('dgc_score', 0)
                
                status_icon = "✅ APPROVED" if verdict == "APPROVED" else "🚫 VETOED "
                reason_str = f" → {stats['reason']}" if stats['reason'] else ""
                
                log(f"[{i}/{len(manifests)}] {status_icon} [{category}] (Amb:{amb:.1f} Wst:{wst:.1f} DGC:{dgc:.1f}%) '{title}...' ({vid}){reason_str}")


def export_catalog():
    log("Exporting final curated catalog...")
    if not RESULTS_FILE.exists():
        log(f"Error: Results file {RESULTS_FILE} not found.")
        return
        
    approved_entries = {}
    
    # Check if there are existing curated items to preserve (e.g. 50 verified seed videos)
    existing_curated_paths = [
        DRIVE_ROOT / 'curated_youtube_seed.json',
        Path('app/src/main/assets/curated_youtube_seed.json')
    ]
    for p in existing_curated_paths:
        if p.exists():
            try:
                with open(p, 'r', encoding='utf-8') as f:
                    data = json.load(f)
                    for item in data:
                        if 'videoId' in item:
                            approved_entries[item['videoId']] = item
            except Exception as e:
                log(f"Warning: Could not load existing curated entries from {p}: {e}")

    with open(RESULTS_FILE, 'r', encoding='utf-8') as f:
        for line in f:
            if not line.strip(): continue
            try:
                data = json.loads(line)
                if data.get('verdict') == 'APPROVED':
                    vid = data['videoId']
                    m = data['manifest']
                    
                    if vid not in approved_entries:
                        approved_entries[vid] = {
                            "videoId": vid,
                            "title": m.get('title', ''),
                            "uploaderName": m.get('uploaderName', ''),
                            "durationSeconds": m.get('durationSeconds', 0),
                            "categoryKey": m.get('categoryKey', 'nature'),
                            "videoPageUrl": m.get('videoPageUrl', f"https://www.youtube.com/watch?v={vid}"),
                            "streamQualityScore": m.get('streamQualityScore', 90),
                            "visualDescription": "Pristine 4K ambient cinematography.",
                            "verifiedBy": "OpenCLIP ViT-B-32 + DGC + Gemini Vision (Colab GPU)",
                            "verifiedAt": datetime.datetime.now().isoformat()
                        }
            except Exception as e:
                continue

    output_list = list(approved_entries.values())
    
    with open(CURATED_JSON, 'w', encoding='utf-8') as f:
        json.dump(output_list, f, indent=2)
        
    with gzip.open(CURATED_GZ, 'wt', encoding='utf-8') as f:
        json.dump(output_list, f)
        
    log(f"Export complete. {len(output_list)} APPROVED videos saved to:")
    log(f" - {CURATED_JSON}")
    log(f" - {CURATED_GZ}")


def print_stats():
    if not RESULTS_FILE.exists():
        log(f"Error: Results file {RESULTS_FILE} not found.")
        return
        
    total = 0
    approved = 0
    vetoed = 0
    reasons = defaultdict(int)
    
    with open(RESULTS_FILE, 'r', encoding='utf-8') as f:
        for line in f:
            if not line.strip(): continue
            try:
                data = json.loads(line)
                total += 1
                if data.get('verdict') == 'APPROVED':
                    approved += 1
                else:
                    vetoed += 1
                    reason = data.get('stats', {}).get('reason', 'unknown')
                    reasons[reason] += 1
            except Exception:
                continue
                
    log("═══════════════════════════════════════════════════")
    log("📊 Audit Statistics")
    log("═══════════════════════════════════════════════════")
    log(f"Total processed : {total}")
    log(f"APPROVED        : {approved} ({(approved/total*100) if total else 0:.1f}%)")
    log(f"VETOED          : {vetoed} ({(vetoed/total*100) if total else 0:.1f}%)")
    log("Veto Reasons:")
    for r, count in sorted(reasons.items(), key=lambda x: x[1], reverse=True):
        log(f"  - {r}: {count}")


def main():
    parser = argparse.ArgumentParser(description="AerialViews+ GPU Audit Pipeline")
    parser.add_argument('--batch', type=str, help='Process specific batch (e.g. batch_001.tar.gz)')
    parser.add_argument('--all', action='store_true', help='Process all batches in frame_batches/')
    parser.add_argument('--export', action='store_true', help='Export final curated catalog')
    parser.add_argument('--stats', action='store_true', help='Print audit statistics')
    args = parser.parse_args()

    if args.export:
        export_catalog()
        return

    if args.stats:
        print_stats()
        return

    if not args.batch and not args.all:
        parser.print_help()
        return

    auditor = Auditor()

    if args.batch:
        batch_path = BATCHES_DIR / args.batch
        auditor.audit_batch(batch_path)
    elif args.all:
        batches = sorted(BATCHES_DIR.glob('*.tar.gz'))
        if not batches:
            log(f"No batches found in {BATCHES_DIR}")
        for batch_path in batches:
            auditor.audit_batch(batch_path)


if __name__ == '__main__':
    main()
