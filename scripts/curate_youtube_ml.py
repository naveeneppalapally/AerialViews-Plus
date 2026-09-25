#!/usr/bin/env python3
"""
AerialViews+ Autonomous ML Video Curation Pipeline
Audits candidate YouTube videos using computer vision markers to eliminate waste entries
(talking heads, vlogs, text overlays/watermarks, static image loops, and AI slop).
"""

import sys
import os
import argparse
import subprocess
import io
import re
import json
import time
import urllib.request
import urllib.parse
from datetime import datetime, timezone
from PIL import Image
import torch
import open_clip

# --- Query Matrix from QueryFormulaEngine.kt ---
CATEGORY_QUERIES = {
    "drone": [
        "4k aerial drone cinematic norway fjords",
        "4k drone landscape dolomites italy",
        "4k coastal drone hawaii coast",
        "4k mountain drone scottish highlands"
    ],
    "nature": [
        "4k forest landscape pacific northwest real footage no talking",
        "4k mountain valley swiss alps documentary",
        "4k river gorge iceland cinematic landscape",
        "4k alpine meadow patagonia real footage"
    ],
    "ocean": [
        "4k underwater coral reef maldives real footage",
        "4k deep ocean marine life scuba diving",
        "4k ocean waves aerial great barrier reef"
    ],
    "cities": [
        "4k city skyline timelapse tokyo japan",
        "4k aerial city new york manhattan day to night",
        "4k cityscape dubai skyline 4k cinematic"
    ],
    "animals": [
        "4k safari wildlife elephants lions national geographic",
        "4k marine life whales dolphins documentary",
        "4k arctic animals penguins seals cinematic wildlife"
    ]
}

# --- Text Blacklists from QueryFormulaEngine.kt & NewPipeHelper.kt ---
TITLE_BLACKLIST = [
    "vlog", "travel vlog", "travel diary", "travel guide", "review", "unboxing",
    "talking", "hosted by", "presented by", "tour guide", "hotel", "resort",
    "things to do", "itinerary", "day in my life", "reaction", "podcast",
    "tutorial", "how to", "sora", "runway ml", "midjourney", "pika labs",
    "ai generated", "made with ai", "3d animation", "render", "gameplay", "walk tour"
]

SPAM_WATERMARK_REGEX = re.compile(
    r"(\b(subscribe|follow|patreon|bell|top\s*10|episode|part\s*\d|merch|linkinbio)\b)|"
    r"(\b[a-z0-9\-]+\.(com|org|net|io|tv|me)\b)|(@[a-z0-9_]{3,})",
    re.I
)

def search_youtube_candidates(query, count_per_query=6):
    """Fetches candidate video metadata from YouTube search without API keys."""
    encoded = urllib.parse.quote_plus(query)
    url = f"https://www.youtube.com/results?search_query={encoded}"
    headers = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36"}
    req = urllib.request.Request(url, headers=headers)
    
    candidates = []
    try:
        with urllib.request.urlopen(req, timeout=12) as resp:
            html = resp.read().decode("utf-8")
        
        match = re.search(r"var ytInitialData = ({.*?});</script>", html)
        if not match:
            return []
        data = json.loads(match.group(1))
        
        contents = data.get("contents", {}).get("twoColumnSearchResultsRenderer", {}).get("primaryContents", {}).get("sectionListRenderer", {}).get("contents", [])
        for section in contents:
            for it in section.get("itemSectionRenderer", {}).get("contents", []):
                if "videoRenderer" in it:
                    vr = it["videoRenderer"]
                    vid = vr.get("videoId", "")
                    title = vr.get("title", {}).get("runs", [{}])[0].get("text", "")
                    uploader = vr.get("ownerText", {}).get("runs", [{}])[0].get("text", "")
                    length_text = vr.get("lengthText", {}).get("simpleText", "")
                    thumbs = vr.get("thumbnail", {}).get("thumbnails", [])
                    # Prefer high-res thumbnail
                    thumb_url = f"https://i.ytimg.com/vi/{vid}/maxresdefault.jpg"
                    fallback_thumb = thumbs[-1]["url"] if thumbs else f"https://i.ytimg.com/vi/{vid}/hqdefault.jpg"
                    
                    if vid and title:
                        candidates.append({
                            "video_id": vid,
                            "title": title,
                            "uploader": uploader,
                            "length_str": length_text,
                            "thumbnail_url": thumb_url,
                            "fallback_thumb": fallback_thumb,
                            "query": query
                        })
                    if len(candidates) >= count_per_query:
                        break
            if len(candidates) >= count_per_query:
                break
    except Exception as e:
        print(f"Error querying '{query}': {e}", file=sys.stderr)
        
    return candidates

def download_image(url, fallback_url=None):
    headers = {"User-Agent": "Mozilla/5.0"}
    for target in [url, fallback_url]:
        if not target:
            continue
        try:
            req = urllib.request.Request(target, headers=headers)
            with urllib.request.urlopen(req, timeout=8) as resp:
                return Image.open(io.BytesIO(resp.read())).convert("RGB")
        except Exception:
            continue
    return None

def parse_duration_seconds(length_str):
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

class AmbientClassifier:
    def __init__(self, device="cpu"):
        self.device = device
        print(f"Loading OpenCLIP ViT-B-32 model on {device}...")
        self.model, _, self.preprocess = open_clip.create_model_and_transforms(
            "hf-hub:timm/vit_base_patch32_clip_224.openai",
            device=device
        )
        self.tokenizer = open_clip.get_tokenizer("hf-hub:timm/vit_base_patch32_clip_224.openai")
        
        self.ambient_prompts = [
            "an aerial drone video of scenic nature landscape",
            "a peaceful cinematic nature view of mountains, forests, or oceans",
            "wild animals or marine life in their natural habitat",
            "a city skyline timelapse with architecture and evening lights",
            "scenic clouds weather or starry night sky"
        ]
        
        self.waste_prompts = [
            "a person or human face talking to the camera",
            "a point of view walking tour vlog down a street or trail",
            "a YouTube video thumbnail with large bold title text graphics watermark",
            "a podcast studio with microphone headphones and host",
            "an indoor room bedroom living room or office studio",
            "a 3D animated CGI cartoon or artificial render"
        ]
        
        self.all_prompts = self.ambient_prompts + self.waste_prompts
        tokens = self.tokenizer(self.all_prompts).to(self.device)
        with torch.no_grad():
            self.text_features = self.model.encode_text(tokens)
            self.text_features /= self.text_features.norm(dim=-1, keepdim=True)
            
    def evaluate_image(self, image: Image.Image):
        tensor = self.preprocess(image).unsqueeze(0).to(self.device)
        with torch.no_grad():
            img_feat = self.model.encode_image(tensor)
            img_feat /= img_feat.norm(dim=-1, keepdim=True)
            sim = (100.0 * img_feat @ self.text_features.T).softmax(dim=-1)[0]
            
        ambient_score = sim[:len(self.ambient_prompts)].sum().item()
        waste_score = sim[len(self.ambient_prompts):].sum().item()
        
        top_idx = sim.argmax().item()
        top_prompt = self.all_prompts[top_idx]
        top_prob = sim[top_idx].item()
        is_top_waste = top_idx >= len(self.ambient_prompts)
        
        return {
            "ambient_score": round(ambient_score * 100, 1),
            "waste_score": round(waste_score * 100, 1),
            "top_prompt": top_prompt,
            "top_prob": round(top_prob * 100, 1),
            "is_top_waste": is_top_waste
        }

def get_best_device():
    if torch.cuda.is_available():
        try:
            # Verify CUDA kernel support for the specific GPU architecture
            t = torch.zeros(1, device="cuda")
            _ = t + 1
            del t
            torch.cuda.empty_cache()
            return "cuda"
        except Exception as e:
            print(f"CUDA device detected but kernel execution failed ({e}). Falling back to CPU.", file=sys.stderr)
            return "cpu"
    return "cpu"

def run_curation_pilot(categories, candidates_per_cat=8, output_dir="curation_output"):
    os.makedirs(output_dir, exist_ok=True)
    device = get_best_device()
    classifier = AmbientClassifier(device=device)
    
    approved_entries = []
    eliminated_entries = []
    
    print("\n" + "=" * 80)
    print(f"STARTING AERIALVIEWS+ ML CURATION PILOT (Timestamp: {datetime.now(timezone.utc).isoformat()}Z)")
    print("=" * 80)
    
    for cat in categories:
        queries = CATEGORY_QUERIES.get(cat, [])
        if not queries:
            continue
        print(f"\n>>> Harvesting Category: [{cat.upper()}] ({len(queries)} queries)")
        candidates = []
        for q in queries:
            cands = search_youtube_candidates(q, count_per_query=max(2, candidates_per_cat // len(queries)))
            candidates.extend(cands)
            time.sleep(0.5)
            
        # Deduplicate
        seen_ids = set()
        deduped = []
        for c in candidates:
            if c["video_id"] not in seen_ids:
                seen_ids.add(c["video_id"])
                deduped.append(c)
                
        print(f"Auditing {len(deduped)} candidates for category '{cat}'...")
        
        for cand in deduped:
            vid = cand["video_id"]
            title = cand["title"]
            title_lower = title.lower()
            uploader = cand["uploader"]
            duration = parse_duration_seconds(cand["length_str"])
            
            # --- Stage 1: Metadata Filter ---
            if duration > 0 and duration < 120:
                eliminated_entries.append({
                    "video_id": vid,
                    "title": title,
                    "uploader": uploader,
                    "category": cat,
                    "stage": "Stage 1 (Metadata)",
                    "reason": f"Short duration ({duration}s < 120s limit)",
                    "url": f"https://youtu.be/{vid}"
                })
                print(f"  ❌ [STAGE 1 VETO] Short video ({duration}s): {title[:50]}...")
                continue
                
            matched_blacklist = next((b for b in TITLE_BLACKLIST if b in title_lower), None)
            if matched_blacklist:
                eliminated_entries.append({
                    "video_id": vid,
                    "title": title,
                    "uploader": uploader,
                    "category": cat,
                    "stage": "Stage 1 (Metadata)",
                    "reason": f"Title blacklist token matched: '{matched_blacklist}'",
                    "url": f"https://youtu.be/{vid}"
                })
                print(f"  ❌ [STAGE 1 VETO] Blacklist keyword '{matched_blacklist}': {title[:50]}...")
                continue
                
            # --- Stage 2: Vision Inspection ---
            img = download_image(cand["thumbnail_url"], cand["fallback_thumb"])
            if img is None:
                eliminated_entries.append({
                    "video_id": vid,
                    "title": title,
                    "uploader": uploader,
                    "category": cat,
                    "stage": "Stage 2 (Vision)",
                    "reason": "Failed to fetch thumbnail image",
                    "url": f"https://youtu.be/{vid}"
                })
                continue
                
            eval_res = classifier.evaluate_image(img)
            
            # Decision boundary: Extreme precision rule
            # Reject if: top label is waste OR waste score > 45%
            is_waste = eval_res["is_top_waste"] or eval_res["waste_score"] > 45.0
            
            if is_waste:
                eliminated_entries.append({
                    "video_id": vid,
                    "title": title,
                    "uploader": uploader,
                    "category": cat,
                    "stage": "Stage 2 (Vision)",
                    "reason": f"{eval_res['top_prompt']} (Waste Confidence: {eval_res['waste_score']}%)",
                    "scores": eval_res,
                    "url": f"https://youtu.be/{vid}"
                })
                print(f"  ❌ [STAGE 2 VETO] {eval_res['top_prompt']} ({eval_res['waste_score']}%): {title[:50]}...")
            else:
                entry = {
                    "videoId": vid,
                    "title": title,
                    "uploaderName": uploader,
                    "durationSeconds": duration,
                    "categoryKey": cat,
                    "videoPageUrl": f"https://www.youtube.com/watch?v={vid}",
                    "streamQualityScore": eval_res["ambient_score"],
                    "verifiedAt": datetime.now(timezone.utc).isoformat() + "Z"
                }
                approved_entries.append(entry)
                print(f"  ✅ [APPROVED] Ambient Score={eval_res['ambient_score']}% ({eval_res['top_prompt'][:30]}...): {title[:50]}...")

    # Write Manifests
    manifest_path = os.path.join(output_dir, "curated_youtube_manifest.json")
    report_path = os.path.join(output_dir, "waste_elimination_report.json")
    summary_path = os.path.join(output_dir, "pilot_summary.json")
    
    with open(manifest_path, "w") as f:
        json.dump(approved_entries, f, indent=2)
        
    with open(report_path, "w") as f:
        json.dump(eliminated_entries, f, indent=2)
        
    total_audited = len(approved_entries) + len(eliminated_entries)
    acceptance_rate = round((len(approved_entries) / max(1, total_audited)) * 100, 1)
    
    summary = {
        "timestamp": datetime.now(timezone.utc).isoformat() + "Z",
        "total_audited": total_audited,
        "total_approved": len(approved_entries),
        "total_eliminated": len(eliminated_entries),
        "acceptance_rate_percent": acceptance_rate,
        "manifest_path": manifest_path,
        "report_path": report_path
    }
    
    with open(summary_path, "w") as f:
        json.dump(summary, f, indent=2)
        
    print("\n" + "=" * 80)
    print("PILOT EXECUTION COMPLETE")
    print(f"Total Audited:    {total_audited}")
    print(f"Approved (Clean): {len(approved_entries)} ({acceptance_rate}%)")
    print(f"Eliminated:       {len(eliminated_entries)} ({100 - acceptance_rate}%)")
    print(f"Curated Manifest: {manifest_path}")
    print(f"Waste Report:     {report_path}")
    print("=" * 80 + "\n")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="AerialViews+ ML Curation Pipeline")
    parser.add_argument("--categories", nargs="+", default=["drone", "nature", "ocean", "cities", "animals"])
    parser.add_argument("--candidates-per-cat", type=int, default=8)
    parser.add_argument("--output-dir", type=str, default="curation_output")
    args = parser.parse_args()
    
    run_curation_pilot(
        categories=args.categories,
        candidates_per_cat=args.candidates_per_cat,
        output_dir=args.output_dir
    )
