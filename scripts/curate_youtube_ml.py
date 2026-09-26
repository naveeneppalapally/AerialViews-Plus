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
import math
from collections import Counter
import urllib.request
import urllib.parse
from datetime import datetime, timezone
from PIL import Image, ImageChops
import numpy as np
import torch
import open_clip

# --- Native YouTube Search Filters (Base64 Protobuf) ---
SP_VIDEO_4K = "EgQQAXAB"                  # Video + 4K
SP_VIDEO_4K_MEDIUM = "EgYQARgDcAE="         # Video + 4K + 4-20m Duration (Legacy)
SP_VIDEO_4K_MEDIUM_MODERN = "EgYQARgFcAE="  # Video + 4K + 3-20m Duration (Modern)
SP_VIDEO_4K_LONG = "EgYQARgCcAE="           # Video + 4K + >20m Duration
SP_VIDEO_4K_RECENT = "EgYIBRABcAE="         # Video + 4K + Uploaded This Year

GLOBAL_NEGATIVES = "-vlog -review -walk -walking -talking -guide -tour -hotel -resort -itinerary -tips -podcast -reaction -sora -ai -cgi -render -demo"
GEAR_NEGATIVES = "-test -tutorial -lut -luts -settings -bts -setup -vs -unboxing -commercial -oled"

# --- Query Matrix from QueryFormulaEngine.kt ---
CATEGORY_QUERIES = {
    "drone": [
        f"4k drone landscape Geirangerfjord cinematic {GLOBAL_NEGATIVES} {GEAR_NEGATIVES}",
        f"4k DJI Inspire 3 aerial Lofoten real footage {GLOBAL_NEGATIVES} {GEAR_NEGATIVES}",
        f"4k Mavic 3 Cine ProRes Landmannalaugar slow flyover {GLOBAL_NEGATIVES} {GEAR_NEGATIVES}",
        f"4k drone fjord Senja 4k no music {GLOBAL_NEGATIVES} {GEAR_NEGATIVES}",
        f"4k mountain flyover Lauterbrunnen cinematic {GLOBAL_NEGATIVES} {GEAR_NEGATIVES}",
        f"4k aerial coastline Na Pali Coast real footage {GLOBAL_NEGATIVES} {GEAR_NEGATIVES}",
        f"4k desert dune flyover Rub al Khali dunes cinematic 4k {GLOBAL_NEGATIVES} {GEAR_NEGATIVES}",
    ],
    "nature": [
        f"4k forest landscape Hoh Rain Forest real footage no talking {GLOBAL_NEGATIVES} -safari -tour -atv -dune bashing",
        f"4k mountain valley Val di Funes ambient sound 4k {GLOBAL_NEGATIVES} -safari -tour -atv -dune bashing",
        f"4k river gorge Jiuzhaigou documentary 4k {GLOBAL_NEGATIVES} -safari -tour -atv -dune bashing",
        f"4k alpine meadow Torres del Paine cinematic landscape {GLOBAL_NEGATIVES} -safari -tour -atv -dune bashing",
        f"4k waterfall Vatnajökull real footage no talking {GLOBAL_NEGATIVES} -safari -tour -atv -dune bashing",
        f"4k desert sand dunes Sossusvlei Namib cinematic landscape {GLOBAL_NEGATIVES} -safari -tour -atv -dune bashing",
    ],
    "ocean": [
        f"4k coral reef underwater Raja Ampat real footage no music {GLOBAL_NEGATIVES} -surfing -surfer -shark -cruise -party",
        f"4k deep sea ocean Bora Bora ambient 4k {GLOBAL_NEGATIVES} -surfing -surfer -shark -cruise -party",
        f"4k ocean waves Great Barrier Reef documentary 4k {GLOBAL_NEGATIVES} -surfing -surfer -shark -cruise -party",
        f"4k sea cliffs coastline Seychelles relaxing real footage {GLOBAL_NEGATIVES} -surfing -surfer -shark -cruise -party",
    ],
    "cities": [
        f"4k city skyline tokyo real footage {GLOBAL_NEGATIVES} -food -streetfood -shopping -traffic",
        f"4k downtown cityscape new york cinematic 4k {GLOBAL_NEGATIVES} -food -streetfood -shopping -traffic",
        f"4k waterfront skyline singapore real footage {GLOBAL_NEGATIVES} -food -streetfood -shopping -traffic",
        f"4k urban architecture chicago cinematic 4k {GLOBAL_NEGATIVES} -food -streetfood -shopping -traffic",
    ],
    "animals": [
        f"4k safari wildlife Serengeti real footage no talking {GLOBAL_NEGATIVES} -zoo -pet -hunting -hunter -cartoon",
        f"4k marine life Svalbard documentary 4k {GLOBAL_NEGATIVES} -zoo -pet -hunting -hunter -cartoon",
        f"4k arctic animals Galapagos cinematic wildlife {GLOBAL_NEGATIVES} -zoo -pet -hunting -hunter -cartoon",
        f"4k birds documentary Okavango Delta natural sound 4k {GLOBAL_NEGATIVES} -zoo -pet -hunting -hunter -cartoon",
    ],
    "space": [
        f"4k earth from space international space station real footage 4k {GLOBAL_NEGATIVES} -ufo -alien -conspiracy -scifi -animation",
        f"4k iss earth view hubble james webb cinematic 4k {GLOBAL_NEGATIVES} -ufo -alien -conspiracy -scifi -animation",
        f"4k milky way night sky Atacama dark sky ambient sound no music {GLOBAL_NEGATIVES} -ufo -alien -conspiracy -scifi -animation",
        f"4k aurora borealis Aoraki Mackenzie dark sky cinematic 4k {GLOBAL_NEGATIVES} -ufo -alien -conspiracy -scifi -animation",
        f"4k bioluminescent night ocean deep space nebula real footage 4k {GLOBAL_NEGATIVES} -ufo -alien -conspiracy -scifi -animation",
    ],
    "weather": [
        f"4k thunderstorm lightning over mountains real footage no music {GLOBAL_NEGATIVES} -damage -disaster -tornado -destruction -loop",
        f"4k storm clouds over ocean ambient sound {GLOBAL_NEGATIVES} -damage -disaster -tornado -destruction -loop",
        f"4k fog rolling valley over plains nature documentary {GLOBAL_NEGATIVES} -damage -disaster -tornado -destruction -loop",
    ],
    "winter": [
        f"4k winter forest snow Arctic 4k no music {GLOBAL_NEGATIVES} -ski -snowboard -resort -cabin -chalet",
        f"4k snowfall landscape Lofoten peaceful snowfall {GLOBAL_NEGATIVES} -ski -snowboard -resort -cabin -chalet",
        f"4k frozen lake ice Alps documentary 4k {GLOBAL_NEGATIVES} -ski -snowboard -resort -cabin -chalet",
    ],
}

# --- Text Blacklists from QueryFormulaEngine.kt & NewPipeHelper.kt ---
TITLE_BLACKLIST = [
    "vlog", "travel vlog", "travel diary", "travel guide", "review", "unboxing",
    "talking", "hosted by", "presented by", "tour guide", "hotel", "resort",
    "things to do", "itinerary", "day in my life", "reaction", "podcast",
    "tutorial", "how to", "sora", "runway ml", "midjourney", "pika labs",
    "ai generated", "made with ai", "3d animation", "render", "gameplay", "walk tour",
    "demo", "oled demo", "4k demo", "tv demo", "test", "settings", "bts", "commercial",
    "color grade", "lut", "luts", "davinci resolve", "premiere pro"
]

SPAM_WATERMARK_REGEX = re.compile(
    r"(\b(subscribe|follow|patreon|bell|top\s*10|episode|part\s*\d|merch|linkinbio)\b)|"
    r"(\b[a-z0-9\-]+\.(com|org|net|io|tv|me)\b)|(@[a-z0-9_]{3,})",
    re.I
)

def search_youtube_candidates(query, count_per_query=6, sp=SP_VIDEO_4K_MEDIUM):
    """Fetches candidate video metadata from YouTube search without API keys."""
    encoded = urllib.parse.quote_plus(query)
    url = f"https://www.youtube.com/results?search_query={encoded}"
    if sp:
        url += f"&sp={urllib.parse.quote_plus(sp)}"
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

def extract_video_frames(video_id, timestamps=(60, 120)):
    """
    Stage 3: Deep Stream Frame Extraction.
    Resolves direct low-res video stream URL (no file download) and extracts frames
    at specified timestamps using ffmpeg.
    """
    try:
        import yt_dlp
        ydl_opts = {
            "quiet": True,
            "no_warnings": True,
            "noplaylist": True,
        }
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(f"https://www.youtube.com/watch?v={video_id}", download=False)
            chosen_url = None
            for f in info.get("formats", []):
                if f.get("vcodec") != "none" and f.get("url") and "manifest" not in f.get("url"):
                    if f.get("height") in [360, 480, 240]:
                        chosen_url = f["url"]
                        break
            if not chosen_url:
                for f in info.get("formats", []):
                    if f.get("vcodec") != "none" and f.get("url") and "manifest" not in f.get("url"):
                        chosen_url = f["url"]
                        break
            if not chosen_url:
                return None

        frames = {}
        for ts in timestamps:
            cmd = [
                "ffmpeg", "-y", "-ss", str(ts),
                "-i", chosen_url,
                "-vframes", "1",
                "-f", "image2pipe",
                "-vcodec", "mjpeg",
                "pipe:1"
            ]
            res = subprocess.run(cmd, capture_output=True, timeout=12)
            if res.returncode == 0 and len(res.stdout) > 1000:
                frames[ts] = Image.open(io.BytesIO(res.stdout)).convert("RGB")
        return frames if frames else None
    except Exception:
        return None

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
            "scenic clouds weather or starry night sky",
            "a view of planet earth, auroras, or stars from space or the international space station",
            "deep space nebula astrophotography or glowing night sky",
            "a serene winter snow landscape with mountains, ice, or frozen lake",
            "desert sand dunes or arid canyon landscape under sunlight"
        ]
        
        self.waste_prompts = [
            "a person or human face talking to the camera",
            "a point of view walking tour vlog down a street or trail",
            "a YouTube video thumbnail with large bold title text graphics watermark",
            "a podcast studio with microphone headphones and host",
            "an indoor room bedroom living room or office studio",
            "a 3D animated CGI cartoon or artificial render",
            "a static still photograph with no camera motion"
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

def mine_blacklist_tokens(approved_entries, eliminated_entries, top_n=10):
    """Mines statistically significant waste tokens using G-Test (LLR) and PMI."""
    def tokenize(title):
        clean = re.sub(r"[^a-zA-Z0-9\s]", " ", title.lower())
        stopwords = {"4k", "8k", "hd", "uhd", "video", "the", "a", "an", "and", "or", "in", "on", "of", "to", "for", "with", "at", "by", "from"}
        return set(w for w in clean.split() if len(w) > 2 and w not in stopwords)

    waste_titles = [e.get("title", "") for e in eliminated_entries]
    approved_titles = [e.get("title", "") for e in approved_entries]
    
    total_waste = len(waste_titles)
    total_approved = len(approved_titles)
    n_total = total_waste + total_approved
    if total_waste == 0 or total_approved == 0:
        return []

    waste_token_counts = Counter()
    for t in waste_titles:
        for token in tokenize(t):
            waste_token_counts[token] += 1

    approved_token_counts = Counter()
    for t in approved_titles:
        for token in tokenize(t):
            approved_token_counts[token] += 1

    all_tokens = set(waste_token_counts.keys()) | set(approved_token_counts.keys())
    mined = []

    for w in all_tokens:
        o11 = waste_token_counts[w]
        o12 = approved_token_counts[w]
        o21 = total_waste - o11
        o22 = total_approved - o12
        r1 = o11 + o12
        r2 = o21 + o22
        c1 = total_waste
        c2 = total_approved

        if o11 < 2:  # Min support threshold in pilot batches
            continue

        # G-Test Log-Likelihood Ratio
        llr = 0.0
        for o, r, c in [(o11, r1, c1), (o12, r1, c2), (o21, r2, c1), (o22, r2, c2)]:
            if o > 0:
                e = (r * c) / n_total
                llr += 2.0 * o * math.log(o / e)

        p_waste_given_w = o11 / r1 if r1 > 0 else 0.0
        p_w = r1 / n_total
        p_waste = c1 / n_total
        pmi = math.log((o11 / n_total) / (p_w * p_waste)) if (o11 > 0 and p_w > 0 and p_waste > 0) else 0.0

        if p_waste_given_w >= 0.75 and llr >= 3.84:  # p < 0.05
            mined.append({
                "token": w,
                "waste_count": o11,
                "approved_count": o12,
                "waste_probability": round(p_waste_given_w, 3),
                "llr_score": round(llr, 2),
                "pmi_score": round(pmi, 2),
                "recommended_exclusion": f"-{w}"
            })

    mined.sort(key=lambda x: (x["waste_probability"], x["llr_score"]), reverse=True)
    return mined[:top_n]

def run_curation_pilot(categories, candidates_per_cat=20, waste_threshold=45.0, output_dir="curation_output"):
    os.makedirs(output_dir, exist_ok=True)
    device = get_best_device()
    classifier = AmbientClassifier(device=device)
    
    # Normalize categories across string / list / space / comma formats
    normalized_categories = []
    for c in categories:
        for item in str(c).replace(",", " ").split():
            item_clean = item.strip().lower()
            if item_clean and item_clean in CATEGORY_QUERIES and item_clean not in normalized_categories:
                normalized_categories.append(item_clean)
    if not normalized_categories:
        normalized_categories = list(CATEGORY_QUERIES.keys())
    categories = normalized_categories

    approved_entries = []
    eliminated_entries = []
    borderline_entries = []
    query_stats = {}
    
    print("\n" + "=" * 80)
    print(f"STARTING AERIALVIEWS+ ML CURATION BENCHMARK (Timestamp: {datetime.now(timezone.utc).isoformat()}Z)")
    print(f"Categories ({len(categories)}): {', '.join(categories)}")
    print(f"Candidates Per Category: {candidates_per_cat} | Waste Threshold: {waste_threshold}%")
    print("=" * 80)
    
    for cat in categories:
        queries = CATEGORY_QUERIES.get(cat, [])
        if not queries:
            continue
        for q in queries:
            query_stats[q] = {"category": cat, "audited": 0, "approved": 0, "eliminated": 0, "yield_percent": 0.0}

        cat_sp = SP_VIDEO_4K_LONG if cat in ("space", "weather") else SP_VIDEO_4K_MEDIUM
        print(f"\n>>> Harvesting Category: [{cat.upper()}] ({len(queries)} queries, SP: {cat_sp})")
        candidates = []
        for q in queries:
            cands = search_youtube_candidates(q, count_per_query=max(2, candidates_per_cat // len(queries)), sp=cat_sp)
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
            source_q = cand.get("query", "")
            if source_q in query_stats:
                query_stats[source_q]["audited"] += 1
            
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
                if source_q in query_stats:
                    query_stats[source_q]["eliminated"] += 1
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
                if source_q in query_stats:
                    query_stats[source_q]["eliminated"] += 1
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
                if source_q in query_stats:
                    query_stats[source_q]["eliminated"] += 1
                continue
                
            eval_res = classifier.evaluate_image(img)
            eval_res["stage"] = "Stage 2 (Thumbnail Vision)"
            
            # Initial waste decision from thumbnail
            is_waste = eval_res["is_top_waste"] or eval_res["waste_score"] > waste_threshold
            rescued_by_stage_3 = False

            # --- Stage 3: Deep Stream Frame & Temporal Verification ---
            # If thumbnail indicates waste, check whether it is purely thumbnail text/clickbait cover
            # or borderline (waste_score <= 65% or thumbnail text graphics prompt).
            is_thumbnail_text_only = (eval_res["top_prompt"] == "a YouTube video thumbnail with large bold title text graphics watermark")
            is_borderline_waste = (38.0 <= eval_res["waste_score"] <= 65.0)

            if is_waste and (is_thumbnail_text_only or is_borderline_waste):
                frames = extract_video_frames(vid, timestamps=(60, 120))
                if frames and 60 in frames:
                    # 1. Temporal motion check: Is it a static photo loop with fake rain/stars?
                    is_static_loop = False
                    motion_delta = None
                    if 120 in frames:
                        f60 = frames[60]
                        f120 = frames[120].resize(f60.size)
                        diff = ImageChops.difference(f60, f120)
                        motion_delta = float(np.array(diff).mean())
                        if motion_delta < 8.0:
                            is_static_loop = True

                    if is_static_loop:
                        eval_res = {
                            "ambient_score": 10.0,
                            "waste_score": 90.0,
                            "top_prompt": "a static still photograph with no camera motion",
                            "top_prob": 90.0,
                            "is_top_waste": True,
                            "stage": "Stage 3 (Temporal Motion Veto)",
                            "motion_delta": round(motion_delta, 2)
                        }
                        is_waste = True
                    else:
                        # 2. OpenCLIP classification of the actual 60s video frame
                        frame_eval = classifier.evaluate_image(frames[60])
                        frame_is_waste = frame_eval["is_top_waste"] or frame_eval["waste_score"] > waste_threshold
                        if not frame_is_waste:
                            # Rescued! The creator only put loud text on the YouTube cover image,
                            # but the video content at 60s is genuine, clean ambient footage.
                            eval_res = frame_eval
                            eval_res["stage"] = "Stage 3 (Video Frame Verified)"
                            eval_res["motion_delta"] = round(motion_delta, 2) if motion_delta is not None else None
                            is_waste = False
                            rescued_by_stage_3 = True
                        else:
                            # Frame also contains waste (e.g. persistent watermarks, talking head, indoor)
                            eval_res = frame_eval
                            eval_res["stage"] = "Stage 3 (Video Frame Veto)"
                            eval_res["motion_delta"] = round(motion_delta, 2) if motion_delta is not None else None
                            is_waste = True

            # Borderline tracking (+/- 7% from decision threshold)
            if abs(eval_res["waste_score"] - waste_threshold) <= 7.0:
                borderline_entries.append({
                    "video_id": vid,
                    "title": title,
                    "uploader": uploader,
                    "category": cat,
                    "decision": "VETOED" if is_waste else "APPROVED",
                    "waste_score": eval_res["waste_score"],
                    "ambient_score": eval_res["ambient_score"],
                    "top_prompt": eval_res["top_prompt"],
                    "stage": eval_res.get("stage", "Stage 2"),
                    "url": f"https://youtu.be/{vid}"
                })
            
            if is_waste:
                eliminated_entries.append({
                    "video_id": vid,
                    "title": title,
                    "uploader": uploader,
                    "category": cat,
                    "stage": eval_res.get("stage", "Stage 2 (Vision)"),
                    "reason": f"{eval_res['top_prompt']} (Waste Confidence: {eval_res['waste_score']}%)",
                    "scores": eval_res,
                    "url": f"https://youtu.be/{vid}"
                })
                if source_q in query_stats:
                    query_stats[source_q]["eliminated"] += 1
                print(f"  ❌ [{eval_res.get('stage', 'STAGE 2 VETO')}] {eval_res['top_prompt']} ({eval_res['waste_score']}%): {title[:50]}...")
            else:
                entry = {
                    "videoId": vid,
                    "title": title,
                    "uploaderName": uploader,
                    "durationSeconds": duration,
                    "categoryKey": cat,
                    "videoPageUrl": f"https://www.youtube.com/watch?v={vid}",
                    "streamQualityScore": eval_res["ambient_score"],
                    "verificationStage": eval_res.get("stage", "Stage 2"),
                    "verifiedAt": datetime.now(timezone.utc).isoformat() + "Z"
                }
                approved_entries.append(entry)
                if source_q in query_stats:
                    query_stats[source_q]["approved"] += 1
                if rescued_by_stage_3:
                    print(f"  ✨ [STAGE 3 RESCUED] Ambient Score={eval_res['ambient_score']}% (Motion Δ={eval_res.get('motion_delta')}): {title[:50]}...")
                else:
                    print(f"  ✅ [APPROVED] Ambient Score={eval_res['ambient_score']}% ({eval_res['top_prompt'][:30]}...): {title[:50]}...")

    # Calculate MAB yields
    for q, stats in query_stats.items():
        if stats["audited"] > 0:
            stats["yield_percent"] = round((stats["approved"] / stats["audited"]) * 100, 1)

    mined_tokens = mine_blacklist_tokens(approved_entries, eliminated_entries)
    mab_analytics = {
        "timestamp": datetime.now(timezone.utc).isoformat() + "Z",
        "query_performance": query_stats,
        "mined_blacklist_tokens": mined_tokens
    }

    # Write Manifests
    manifest_path = os.path.join(output_dir, "curated_youtube_manifest.json")
    report_path = os.path.join(output_dir, "waste_elimination_report.json")
    borderline_path = os.path.join(output_dir, "borderline_candidates.json")
    summary_path = os.path.join(output_dir, "pilot_summary.json")
    mab_path = os.path.join(output_dir, "mab_query_analytics.json")
    step_summary_path = os.path.join(output_dir, "github_step_summary.md")
    
    with open(manifest_path, "w") as f:
        json.dump(approved_entries, f, indent=2)
        
    with open(report_path, "w") as f:
        json.dump(eliminated_entries, f, indent=2)

    with open(borderline_path, "w") as f:
        json.dump(borderline_entries, f, indent=2)

    with open(mab_path, "w") as f:
        json.dump(mab_analytics, f, indent=2)
        
    total_audited = len(approved_entries) + len(eliminated_entries)
    acceptance_rate = round((len(approved_entries) / max(1, total_audited)) * 100, 1)
    stage_3_rescues = sum(1 for e in approved_entries if e.get("verificationStage") == "Stage 3 (Video Frame Verified)")
    
    summary = {
        "timestamp": datetime.now(timezone.utc).isoformat() + "Z",
        "total_audited": total_audited,
        "total_approved": len(approved_entries),
        "total_eliminated": len(eliminated_entries),
        "total_borderline": len(borderline_entries),
        "stage_3_rescues": stage_3_rescues,
        "acceptance_rate_percent": acceptance_rate,
        "waste_threshold": waste_threshold,
        "manifest_path": manifest_path,
        "report_path": report_path,
        "borderline_path": borderline_path,
        "mab_analytics_path": mab_path,
        "top_mined_tokens": [t["recommended_exclusion"] for t in mined_tokens[:5]]
    }
    
    with open(summary_path, "w") as f:
        json.dump(summary, f, indent=2)

    # Render GitHub Markdown Step Summary
    cat_summary = {}
    for q_data in query_stats.values():
        c = q_data["category"]
        if c not in cat_summary:
            cat_summary[c] = {"audited": 0, "approved": 0, "eliminated": 0}
        cat_summary[c]["audited"] += q_data["audited"]
        cat_summary[c]["approved"] += q_data["approved"]
        cat_summary[c]["eliminated"] += q_data["eliminated"]

    with open(step_summary_path, "w") as f:
        f.write("# 🛰️ AerialViews+ ML Curation Benchmark Summary\n\n")
        f.write(f"- **Timestamp:** `{summary['timestamp']}`\n")
        f.write(f"- **Total Audited:** `{total_audited}`\n")
        f.write(f"- **Approved (Pristine Ambient):** `{len(approved_entries)}` ({acceptance_rate}%)\n")
        f.write(f"- **Eliminated (Waste Purged):** `{len(eliminated_entries)}` ({round(100 - acceptance_rate, 1)}%)\n")
        f.write(f"- **Stage 3 Stream Rescues:** `{stage_3_rescues}` (clean videos with noisy cover thumbnails rescued)\n")
        f.write(f"- **Decision Waste Threshold:** `{waste_threshold}%`\n")
        f.write(f"- **Borderline Cases Tracked:** `{len(borderline_entries)}`\n\n")

        f.write("### 📊 Performance by Category\n\n")
        f.write("| Category | Audited | Approved | Eliminated | Ambient Yield % |\n")
        f.write("| :--- | :--- | :--- | :--- | :--- |\n")
        for c, s in cat_summary.items():
            yld = round((s["approved"] / max(1, s["audited"])) * 100, 1)
            f.write(f"| **{c.capitalize()}** | {s['audited']} | {s['approved']} | {s['eliminated']} | **{yld}%** |\n")
        f.write("\n")

        if borderline_entries:
            f.write("### 🔍 Borderline Decision Audit (Within ±7% of Decision Boundary)\n\n")
            f.write("| Video | Category | Decision | Waste % | Top Visual Marker |\n")
            f.write("| :--- | :--- | :--- | :--- | :--- |\n")
            for b in borderline_entries[:15]:
                f.write(f"| [{b['title'][:40]}...]({b['url']}) | `{b['category']}` | **{b['decision']}** | {b['waste_score']}% | {b['top_prompt'][:40]}... |\n")
            f.write("\n")

        if mined_tokens:
            f.write("### 🚫 Top Mined Negative Tokens for Search Engine\n\n")
            f.write("| Token | Waste Count | Approved Count | Waste Prob | Recommended Exclusion |\n")
            f.write("| :--- | :--- | :--- | :--- | :--- |\n")
            for t in mined_tokens[:8]:
                f.write(f"| `{t['token']}` | {t['waste_count']} | {t['approved_count']} | {int(t['waste_probability']*100)}% | `{t['recommended_exclusion']}` |\n")
            f.write("\n")

    # If running in GitHub Actions, append directly to $GITHUB_STEP_SUMMARY
    gh_step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if gh_step_summary:
        try:
            with open(step_summary_path, "r") as src, open(gh_step_summary, "a") as dst:
                dst.write(src.read())
        except Exception as e:
            print(f"Warning: Failed to write to GITHUB_STEP_SUMMARY: {e}", file=sys.stderr)
        
    print("\n" + "=" * 80)
    print("PILOT BENCHMARK COMPLETE")
    print(f"Total Audited:    {total_audited}")
    print(f"Approved (Clean): {len(approved_entries)} ({acceptance_rate}%)")
    print(f"Eliminated:       {len(eliminated_entries)} ({round(100 - acceptance_rate, 1)}%)")
    print(f"Borderlines:      {len(borderline_entries)}")
    print(f"Curated Manifest: {manifest_path}")
    print(f"Waste Report:     {report_path}")
    print(f"Borderline Log:   {borderline_path}")
    print(f"Step Summary:     {step_summary_path}")
    print(f"MAB Analytics:    {mab_path}")
    if mined_tokens:
        print(f"Mined Tokens:     {[t['recommended_exclusion'] for t in mined_tokens]}")
    print("=" * 80 + "\n")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="AerialViews+ ML Curation Pipeline")
    parser.add_argument("--categories", nargs="+", default=["drone", "nature", "ocean", "cities", "animals", "space", "weather", "winter"])
    parser.add_argument("--candidates-per-cat", type=int, default=20)
    parser.add_argument("--waste-threshold", type=float, default=45.0)
    parser.add_argument("--output-dir", type=str, default="curation_output")
    args = parser.parse_args()
    
    run_curation_pilot(
        categories=args.categories,
        candidates_per_cat=args.candidates_per_cat,
        waste_threshold=args.waste_threshold,
        output_dir=args.output_dir
    )
