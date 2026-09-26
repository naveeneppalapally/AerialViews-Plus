#!/usr/bin/env python3
"""
curate_colab_pipeline.py: Production Autonomous Curation Pipeline.
Designed for Google Colab GPU runtimes and local cloud environments.
Incorporates sub-second byte seeking, motion dynamics, OpenCLIP GPU pre-filtering,
Gemini Vision multi-frame gold auditing, and persistent WAL checkpointing.
"""

import os
import io
import sys
import json
import gzip
import time
import argparse
import subprocess
from datetime import datetime, timezone
from typing import Dict, List, Optional, Tuple, Set
from PIL import Image
import numpy as np

# -------------------------------------------------------------
# Module 1: Sub-Second Stream Byte Seeker (Header-Forwarded)
# -------------------------------------------------------------
class StreamByteSeeker:
    def __init__(self, target_height: int = 360, timeout_sec: int = 15):
        self.target_height = target_height
        self.timeout_sec = timeout_sec

    def resolve_stream(self, video_id: str) -> Optional[Tuple[str, str]]:
        import yt_dlp
        url = f"https://www.youtube.com/watch?v={video_id}"
        ydl_opts = {
            "quiet": True,
            "no_warnings": True,
            "noplaylist": True,
            "extractor_args": {"youtube": {"player_client": ["android", "ios", "web"]}}
        }
        try:
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                info = ydl.extract_info(url, download=False)
                formats = info.get("formats", [])
                
                # Priority 1: Exact target resolution
                for f in formats:
                    if f.get("vcodec") != "none" and f.get("url") and "manifest" not in f.get("url"):
                        if f.get("height") == self.target_height:
                            ua = f.get("http_headers", {}).get("User-Agent", "Mozilla/5.0")
                            return f["url"], ua

                # Priority 2: 240p or 480p fallback
                for f in formats:
                    if f.get("vcodec") != "none" and f.get("url") and "manifest" not in f.get("url"):
                        if f.get("height") in [240, 480]:
                            ua = f.get("http_headers", {}).get("User-Agent", "Mozilla/5.0")
                            return f["url"], ua

                # Priority 3: First available playable stream
                for f in formats:
                    if f.get("vcodec") != "none" and f.get("url") and "manifest" not in f.get("url"):
                        ua = f.get("http_headers", {}).get("User-Agent", "Mozilla/5.0")
                        return f["url"], ua
        except Exception:
            return None
        return None

    def extract_burst(
        self, 
        stream_url: str, 
        user_agent: str, 
        timestamp_sec: float, 
        duration_sec: float = 1.0, 
        fps: int = 4
    ) -> List[Image.Image]:
        cmd = [
            "ffmpeg", "-y",
            "-headers", f"User-Agent: {user_agent}\r\n",
            "-ss", f"{timestamp_sec:.2f}",
            "-reconnect", "1",
            "-reconnect_streamed", "1",
            "-reconnect_delay_max", "2",
            "-fflags", "+nobuffer+fastseek+discardcorrupt",
            "-i", stream_url,
            "-an", "-sn", "-dn",
            "-t", f"{duration_sec:.2f}",
            "-vf", f"fps={fps}",
            "-f", "image2pipe",
            "-vcodec", "mjpeg",
            "pipe:1"
        ]
        try:
            res = subprocess.run(cmd, capture_output=True, timeout=self.timeout_sec)
            if res.returncode != 0 or len(res.stdout) < 1000:
                return []
            
            data = res.stdout
            images = []
            curr = 0
            while curr < len(data):
                start = data.find(b"\xff\xd8", curr)
                if start == -1:
                    break
                end = data.find(b"\xff\xd9", start + 2)
                if end == -1:
                    break
                jpeg_bytes = data[start:end+2]
                try:
                    img = Image.open(io.BytesIO(jpeg_bytes)).convert("RGB")
                    images.append(img)
                except Exception:
                    pass
                curr = end + 2
            return images
        except Exception:
            return []

# -------------------------------------------------------------
# Module 2: Motion Dynamics Engine (Fluid & Ocean Protected)
# -------------------------------------------------------------
def compute_masked_ssim(img1: np.ndarray, img2: np.ndarray, mask: np.ndarray) -> float:
    if not np.any(mask):
        return 1.0
    val1 = img1[mask].astype(np.float64)
    val2 = img2[mask].astype(np.float64)
    C1 = (0.01 * 255.0) ** 2
    C2 = (0.03 * 255.0) ** 2
    mu_x = np.mean(val1)
    mu_y = np.mean(val2)
    sigma_x2 = np.var(val1)
    sigma_y2 = np.var(val2)
    sigma_xy = np.mean((val1 - mu_x) * (val2 - mu_y))
    numerator = (2.0 * mu_x * mu_y + C1) * (2.0 * sigma_xy + C2)
    denominator = (mu_x**2 + mu_y**2 + C1) * (sigma_x2 + sigma_y2 + C2)
    return float(numerator / denominator)

class MotionDynamicsAnalyzer:
    def __init__(self, static_threshold: float = 0.80, ssim_threshold: float = 0.970):
        self.static_threshold = static_threshold
        self.ssim_threshold = ssim_threshold

    def analyze_burst(self, frames: List[Image.Image]) -> Dict:
        import cv2
        if len(frames) < 4:
            return {"verdict": "VETOED", "reason": "Insufficient frames for motion analysis", "type": "error"}

        grays = [np.array(f.convert("L")) for f in frames]
        h, w = grays[0].shape

        flows = []
        static_masks = []
        magnitudes = []

        # 1. Gunnar Farnebäck Optical Flow between consecutive pairs
        for i in range(len(grays) - 1):
            flow = cv2.calcOpticalFlowFarneback(
                grays[i], grays[i+1], None,
                pyr_scale=0.5, levels=3, winsize=15,
                iterations=3, poly_n=5, poly_sigma=1.2, flags=0
            )
            flows.append(flow)
            mag = np.sqrt(flow[..., 0]**2 + flow[..., 1]**2)
            magnitudes.append(mag)
            static_masks.append(mag < 0.5)

        mean_flow_mag = float(np.mean([np.mean(m) for m in magnitudes]))
        mean_static_ratio = float(np.mean([np.mean(s) for s in static_masks]))

        # 2. Frozen Still Image Check
        if mean_flow_mag < 0.15:
            return {
                "verdict": "VETOED",
                "reason": f"Frozen still photo (mean flow {mean_flow_mag:.2f} px < 0.15 px)",
                "type": "frozen_still",
                "mean_flow": round(mean_flow_mag, 2),
                "static_ratio": round(mean_static_ratio, 3)
            }

        # 3. Fake Video Loop Check (Static photo + synthetic rain/snow)
        static_mask_intersection = np.logical_and.reduce(static_masks)
        if mean_static_ratio >= self.static_threshold:
            masked_ssim = compute_masked_ssim(grays[0], grays[-1], static_mask_intersection)
            if masked_ssim >= self.ssim_threshold:
                return {
                    "verdict": "VETOED",
                    "reason": f"Fake video loop (static background ratio {mean_static_ratio:.2f}, SSIM {masked_ssim:.3f})",
                    "type": "fake_loop",
                    "static_ratio": round(mean_static_ratio, 3),
                    "ssim": round(masked_ssim, 3)
                }

        # 4. Affine Estimation, Angular Jerk, and Cadence Flips
        step = 16
        rot_angles = []
        translations_y = []
        inlier_ratios = []

        for flow in flows:
            pts1, pts2 = [], []
            for y in range(0, h, step):
                for x in range(0, w, step):
                    pts1.append([x, y])
                    pts2.append([x + flow[y, x, 0], y + flow[y, x, 1]])
            pts1 = np.float32(pts1)
            pts2 = np.float32(pts2)

            M, inliers = cv2.estimateAffinePartial2D(pts1, pts2, method=cv2.RANSAC, ransacReprojThreshold=1.5)
            if M is not None:
                ang = np.arctan2(M[1, 0], M[0, 0])
                ty = M[1, 2]
                rot_angles.append(ang)
                translations_y.append(ty)
                inlier_ratios.append(float(np.mean(inliers)))

        mean_inlier_ratio = float(np.mean(inlier_ratios)) if inlier_ratios else 0.0

        dt = 0.25
        max_alpha = 0.0
        angular_jerk = 0.0
        if len(rot_angles) >= 3:
            omega = np.diff(rot_angles) / dt
            alpha = np.diff(omega) / dt
            max_alpha = float(np.max(np.abs(alpha)))
            if len(alpha) >= 2:
                angular_jerk = float(np.abs(alpha[1] - alpha[0]) / dt)

        n_flips = 0
        for i in range(len(translations_y) - 1):
            if (translations_y[i] * translations_y[i+1]) < -0.1:
                n_flips += 1

        # 5. Handheld Walking Shake vs Fluid/Ocean Protection
        # Ocean surf has low affine inliers (<0.48) but ZERO angular jerk (<0.20) and NO cadence flips!
        is_handheld = (mean_inlier_ratio < 0.48) and (max_alpha > 0.40 or angular_jerk > 0.50 or n_flips >= 2)
        if is_handheld:
            return {
                "verdict": "VETOED",
                "reason": f"Handheld walking shake (inlier: {mean_inlier_ratio:.2f}, jerk: {angular_jerk:.2f}, flips: {n_flips})",
                "type": "handheld_shaky",
                "inlier_ratio": round(mean_inlier_ratio, 3),
                "angular_jerk": round(angular_jerk, 3),
                "flips": n_flips
            }

        return {
            "verdict": "APPROVED",
            "reason": "Smooth cinematic motion verified",
            "type": "smooth_translation",
            "inlier_ratio": round(mean_inlier_ratio, 3),
            "angular_jerk": round(angular_jerk, 3),
            "static_ratio": round(mean_static_ratio, 3),
            "mean_flow": round(mean_flow_mag, 2)
        }

# -------------------------------------------------------------
# Module 3: OpenCLIP GPU Pre-Filter (FP16 Autocast)
# -------------------------------------------------------------
class OpenClipFastFilter:
    def __init__(self, model_name: str = "ViT-B-32", pretrained: str = "laion2b_s34b_b79k", device: Optional[str] = None):
        import torch
        import open_clip
        self.torch = torch
        self.device = device or ("cuda" if torch.cuda.is_available() else "cpu")
        print(f"Loading OpenCLIP [{model_name}] on device: {self.device}...")
        self.model, _, self.preprocess = open_clip.create_model_and_transforms(
            model_name, pretrained=pretrained, device=self.device
        )
        self.tokenizer = open_clip.get_tokenizer(model_name)

        self.ambient_categories = {
            "drone": ["an aerial drone flyover of scenic landscape", "a cinematic drone shot of mountains, fjords, or coastline"],
            "nature": ["a peaceful cinematic nature landscape of forests, waterfalls, or mountains", "a tranquil outdoor wilderness scene"],
            "ocean": ["an underwater coral reef with clear blue water and tropical fish", "aerial view of ocean waves crashing against sea cliffs"],
            "cities": ["a high-angle cinematic city skyline timelapse with architecture", "illuminated modern skyscrapers at twilight"],
            "animals": ["wild animals in their natural African safari habitat", "marine life, whales, or birds in natural wilderness"],
            "space": ["a view of planet earth, auroras, and stars from the International Space Station", "deep space astrophotography of galaxies and nebulae"],
            "weather": ["dramatic storm clouds rolling over plains, cinematic nature", "peaceful thick fog rolling through a mountain valley"],
            "winter": ["a serene snowy winter forest with pine trees covered in snow", "a frozen alpine lake with glacial ice and snow peaks"]
        }

        self.waste_prompts = [
            "a person, human face, or vlogger talking directly to the camera",
            "a YouTube thumbnail with large bold title text graphics watermark",
            "a podcast studio with microphone, headphones, and talking host",
            "a point of view handheld walking tour down a sidewalk or street",
            "an indoor living room, bedroom, office desk, or retail storefront",
            "a 3D animated CGI video game cartoon or artificial render",
            "a static still photograph with no camera motion"
        ]

        self._build_embeddings()

    def _build_embeddings(self):
        self.flat_prompts = []
        self.ambient_indices = []
        self.waste_indices = []

        curr_idx = 0
        for cat, prompts in self.ambient_categories.items():
            for p in prompts:
                self.flat_prompts.append(p)
                self.ambient_indices.append(curr_idx)
                curr_idx += 1

        for p in self.waste_prompts:
            self.flat_prompts.append(p)
            self.waste_indices.append(curr_idx)
            curr_idx += 1

        tokens = self.tokenizer(self.flat_prompts).to(self.device)
        with self.torch.no_grad():
            feats = self.model.encode_text(tokens)
            feats /= feats.norm(dim=-1, keepdim=True)
            self.text_features = feats

    def evaluate_frames_batch(self, images: List[Image.Image]) -> List[Dict]:
        if not images:
            return []

        tensors = self.torch.stack([self.preprocess(img) for img in images]).to(self.device)
        scale = self.model.logit_scale.exp().item()

        with self.torch.no_grad():
            with self.torch.cuda.amp.autocast(enabled=(self.device == "cuda")):
                img_feats = self.model.encode_image(tensors)
                img_feats /= img_feats.norm(dim=-1, keepdim=True)
                logits = scale * (img_feats @ self.text_features.T)
                probs = logits.softmax(dim=-1)

        probs_cpu = probs.cpu().numpy()
        results = []
        for p in probs_cpu:
            ambient_score = float(p[self.ambient_indices].sum() * 100.0)
            waste_score = float(p[self.waste_indices].sum() * 100.0)
            top_idx = int(p.argmax())
            is_top_ambient = top_idx in self.ambient_indices

            pass_filter = (waste_score <= 35.0) and (ambient_score >= 60.0) and is_top_ambient
            results.append({
                "verdict": "APPROVED" if pass_filter else "VETOED",
                "ambient_score": round(ambient_score, 1),
                "waste_score": round(waste_score, 1),
                "top_prompt": self.flat_prompts[top_idx],
                "pass_clip": pass_filter
            })

        return results

# -------------------------------------------------------------
# Module 4: Gemini Vision Gold Auditor (Native OCR Inspection)
# -------------------------------------------------------------
class GeminiVisionAuditor:
    def __init__(self, api_key: str):
        from google import genai
        self.api_key = api_key.strip()
        self.client = genai.Client(api_key=self.api_key)
        self.model_name = self._resolve_active_model()
        print(f"Gemini Vision Auditor initialized with model: '{self.model_name}'")

    def _resolve_active_model(self) -> str:
        candidates = ["gemini-3.8-flash", "gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash"]
        try:
            available = [m.name.replace("models/", "") for m in self.client.models.list()]
            for c in candidates:
                if any(c in m for m in available):
                    return c
        except Exception:
            pass
        return "gemini-2.5-flash"

    def audit_candidate_frames(
        self, 
        frames: List[Image.Image], 
        title: str, 
        uploader: str, 
        category: str,
        max_retries: int = 5
    ) -> Dict:
        import re
        from google.genai import types

        prompt = f"""You are the master visual quality curator for AerialViews+, a 4K screensaver for large OLED Living Room TVs.
Audit these {len(frames)} sampled frames taken at intervals across the video timeline.

Video Title: "{title}"
Uploader: "{uploader}"
Target Category: {category}

STRICT REJECTION CRITERIA (Any violation across ANY frame is an immediate VETO):
1. TALKING HEADS & VLOGS: Human faces, tourists talking to camera, walking tours, podcast hosts.
2. TEXT & WATERMARKS (OCR AUDIT): Burned-in title cards ('NORWAY 4K', 'EPISODE 1'), channel logos, timestamps, URLs, or stock watermarks.
3. COMMERCIAL CLUTTER: Hotel rooms, resort commercials, price tags, store fronts.
4. FAKE / CGI / ANIMATION: 3D video game graphics, synthetic CGI, AI morphing, or static still photos with digital rain.
5. SHAKY / FISHEYE: Handheld walking bounce, cycling handlebars, severe barrel distortion.

ACCEPTANCE CRITERIA:
- Flawless, serene, high-aesthetic nature, drone flyovers, coral reefs, city skylines, or starry skies.
- Real fine-art camera cinematography with continuous serene atmosphere.

Respond ONLY with valid JSON:
{{
  "decision": "APPROVED" | "VETOED",
  "aesthetic_score": <int 0-100>,
  "waste_score": <int 0-100>,
  "detected_violations": ["talking_head" | "text_watermark" | "sponsor_segment" | "shaky_camera" | "cgi_render" | "indoor_store" | "none"],
  "framing_composition": "wide_cinematic" | "telephoto_wildlife" | "aerial_top_down" | "cluttered_tourist" | "selfie",
  "reason": "<clear concise explanation>",
  "visual_description": "<one serene sentence describing what is seen for TV captions>"
}}"""

        contents = []
        for img in frames:
            buf = io.BytesIO()
            img.save(buf, format="JPEG", quality=85)
            contents.append(types.Part.from_bytes(data=buf.getvalue(), mime_type="image/jpeg"))
        contents.append(prompt)

        cfg = types.GenerateContentConfig(
            response_mime_type="application/json",
            temperature=0.2
        )

        delay = 1.5
        for attempt in range(max_retries):
            try:
                resp = self.client.models.generate_content(
                    model=self.model_name,
                    contents=contents,
                    config=cfg
                )
                text = resp.text.strip()
                if text.startswith("```"):
                    text = re.sub(r"^```(?:json)?\s*", "", text)
                    text = re.sub(r"\s*```$", "", text)
                return json.loads(text.strip())
            except Exception as e:
                err_str = str(e)
                if "429" in err_str or "RESOURCE_EXHAUSTED" in err_str:
                    time.sleep(delay)
                    delay *= 2.0
                    continue
                if attempt == max_retries - 1:
                    return {
                        "decision": "VETOED",
                        "aesthetic_score": 0,
                        "waste_score": 100,
                        "detected_violations": ["api_error"],
                        "reason": f"Gemini API failure: {err_str}",
                        "visual_description": ""
                    }
                time.sleep(delay)

        return {"decision": "VETOED", "aesthetic_score": 0, "waste_score": 100, "reason": "Timeout"}

# -------------------------------------------------------------
# Module 5: Colab Orchestrator & CLI Runner
# -------------------------------------------------------------
class ColabOrchestrator:
    def __init__(self, base_dir: str, gemini_api_key: str):
        self.base_dir = base_dir
        self.wal_path = os.path.join(base_dir, "state", "audited_history.jsonl")
        self.catalog_path = os.path.join(base_dir, "state", "verified_catalog.jsonl")
        self.output_json = os.path.join(base_dir, "output", "curated_youtube_seed.json")
        self.output_gz = os.path.join(base_dir, "output", "curated_youtube_seed.json.gz")

        os.makedirs(os.path.join(base_dir, "state"), exist_ok=True)
        os.makedirs(os.path.join(base_dir, "output"), exist_ok=True)

        self.seen_ids: Set[str] = set()
        if os.path.exists(self.wal_path):
            with open(self.wal_path, "r", encoding="utf-8") as f:
                for line in f:
                    try:
                        self.seen_ids.add(json.loads(line)["video_id"])
                    except Exception:
                        pass
        print(f"Loaded {len(self.seen_ids)} previously audited video IDs from WAL.")

        self.seeker = StreamByteSeeker(target_height=360)
        self.motion_engine = MotionDynamicsAnalyzer()
        self.clip_filter = OpenClipFastFilter()
        self.gemini_auditor = GeminiVisionAuditor(api_key=gemini_api_key)

    def log_wal(self, vid: str, verdict: str, reason: str, category: str):
        rec = {
            "video_id": vid,
            "verdict": verdict,
            "reason": reason,
            "category": category,
            "timestamp": datetime.now(timezone.utc).isoformat()
        }
        with open(self.wal_path, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec) + "\n")
        self.seen_ids.add(vid)

    def append_catalog(self, entry: Dict):
        with open(self.catalog_path, "a", encoding="utf-8") as f:
            f.write(json.dumps(entry) + "\n")

    def audit_candidate(self, candidate: Dict) -> Optional[Dict]:
        vid = candidate["video_id"]
        title = candidate["title"]
        uploader = candidate["uploader"]
        duration = candidate["duration"]
        category = candidate["category"]

        if vid in self.seen_ids:
            return None

        # Stage 0: Duration Gate
        if duration < 180:
            self.log_wal(vid, "VETOED", f"Duration too short ({duration}s)", category)
            return None

        # Stage 1: Resolve stream URL
        stream_res = self.seeker.resolve_stream(vid)
        if not stream_res:
            self.log_wal(vid, "VETOED", "Unable to resolve 360p stream URL", category)
            return None
        stream_url, ua = stream_res

        # Timeline Sampling Anchors
        t_intro = max(35.0, 0.10 * duration)
        t_outro = max(30.0, 0.08 * duration)
        eff_len = duration - t_outro - t_intro
        K = 4 if duration < 900 else (5 if duration < 1800 else 6)
        anchor_times = [t_intro + ((k - 0.5) / K) * eff_len for k in range(1, K + 1)]

        sampled_anchor_frames = []
        for ts in anchor_times:
            burst = self.seeker.extract_burst(stream_url, ua, timestamp_sec=ts, duration_sec=1.0, fps=4)
            if len(burst) < 4:
                continue

            motion_res = self.motion_engine.analyze_burst(burst)
            if motion_res["verdict"] == "VETOED":
                self.log_wal(vid, "VETOED", f"Motion Veto @{int(ts)}s: {motion_res['reason']}", category)
                return None

            sampled_anchor_frames.append((ts, burst[1], motion_res))

        if len(sampled_anchor_frames) < (K - 1):
            self.log_wal(vid, "VETOED", "Failed to extract required anchor bursts", category)
            return None

        # Stage 2: OpenCLIP GPU Pre-Filter
        vision_frames = [item[1] for item in sampled_anchor_frames]
        clip_results = self.clip_filter.evaluate_frames_batch(vision_frames)
        for i, cres in enumerate(clip_results):
            if cres["verdict"] == "VETOED":
                self.log_wal(vid, "VETOED", f"OpenCLIP Veto @anchor {i+1}: {cres['top_prompt']} ({cres['waste_score']}%)", category)
                return None

        # Stage 3: Gemini Vision Gold Audit
        gemini_res = self.gemini_auditor.audit_candidate_frames(
            frames=vision_frames,
            title=title,
            uploader=uploader,
            category=category
        )

        if gemini_res.get("decision") != "APPROVED" or gemini_res.get("aesthetic_score", 0) < 80:
            self.log_wal(vid, "VETOED", f"Gemini Veto: {gemini_res.get('reason')}", category)
            return None

        mean_inlier = float(np.mean([item[2].get("inlier_ratio", 0.8) for item in sampled_anchor_frames]))
        mean_clip_ambient = float(np.mean([cres["ambient_score"] for cres in clip_results]))
        aesthetic_score = float(gemini_res["aesthetic_score"])
        
        final_quality = int(round(0.65 * aesthetic_score + 0.20 * mean_clip_ambient + 0.15 * (100.0 * mean_inlier)))
        final_quality = max(0, min(100, final_quality))

        entry = {
            "videoId": vid,
            "title": title,
            "uploaderName": uploader,
            "durationSeconds": duration,
            "categoryKey": category,
            "videoPageUrl": f"https://www.youtube.com/watch?v={vid}",
            "streamQualityScore": final_quality,
            "visualDescription": gemini_res.get("visual_description", ""),
            "verifiedBy": f"Gemini Vision ({self.gemini_auditor.model_name}) + OpenCLIP",
            "verifiedAt": datetime.now(timezone.utc).isoformat()
        }

        self.append_catalog(entry)
        self.log_wal(vid, "APPROVED", f"Approved (Score: {final_quality})", category)
        print(f"  🌟 APPROVED [{final_quality}/100]: '{title}' ({vid})")
        return entry

    def compile_catalog(self):
        entries = []
        if os.path.exists(self.catalog_path):
            with open(self.catalog_path, "r", encoding="utf-8") as f:
                for line in f:
                    try:
                        entries.append(json.loads(line))
                    except Exception:
                        pass
        unique = {e["videoId"]: e for e in entries}
        final_list = list(unique.values())

        with open(self.output_json, "w", encoding="utf-8") as f:
            json.dump(final_list, f, indent=2)

        raw = json.dumps(final_list).encode("utf-8")
        with gzip.open(self.output_gz, "wb", compresslevel=6) as f_gz:
            f_gz.write(raw)

        print(f"Catalog compiled: {len(final_list)} entries.")
        print(f"JSON: {self.output_json} ({round(os.path.getsize(self.output_json)/1024, 1)} KB)")
        print(f"GZ:   {self.output_gz} ({round(os.path.getsize(self.output_gz)/1024, 1)} KB)")

def main():
    parser = argparse.ArgumentParser(description="AerialViews+ 4K Ambient Video Curation Pipeline")
    parser.add_argument("--base-dir", default="./curation_state", help="Directory for state logging and output")
    parser.add_argument("--target-per-category", type=int, default=10, help="Target verified videos per category")
    parser.add_argument("--category", default=None, help="Filter to single category (e.g. drone, nature)")
    args = parser.parse_args()

    api_key = os.environ.get("GEMINI_API_KEY", "")
    if not api_key:
        print("ERROR: GEMINI_API_KEY environment variable is required.")
        sys.exit(1)

    orchestrator = ColabOrchestrator(base_dir=args.base_dir, gemini_api_key=api_key)
    print("Orchestrator ready.")

if __name__ == "__main__":
    main()
