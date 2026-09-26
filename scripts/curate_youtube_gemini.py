#!/usr/bin/env python3
"""
AerialViews+ Autonomous Gemini Vision Curation Pipeline
Audits candidate YouTube videos using Google Gemini Multimodal Vision to eliminate waste entries
(talking heads, vlogs, text overlays/watermarks, static image loops, and AI slop)
with superhuman visual comprehension and native OCR.
"""

import sys
import os
import argparse
import base64
import urllib.request
import urllib.parse
import json
import time
import re
from datetime import datetime, timezone
from collections import Counter
from PIL import Image
import io

# --- Native YouTube Search Filters (Base64 Protobuf) ---
SP_VIDEO_4K = "EgQQAXAB"                  # Video + 4K
SP_VIDEO_4K_MEDIUM = "EgYQARgDcAE="         # Video + 4K + 4-20m Duration
SP_VIDEO_4K_LONG = "EgYQARgCcAE="           # Video + 4K + >20m Duration

GLOBAL_NEGATIVES = "-vlog -review -walk -walking -talking -guide -tour -hotel -resort -itinerary -tips -podcast -reaction -sora -ai -cgi -render -demo -amazing -stunning"
GEAR_NEGATIVES = "-test -tutorial -lut -luts -settings -bts -setup -vs -unboxing -commercial -oled"

# Combinatorial Query Matrix across all 8 Categories
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

TITLE_BLACKLIST = [
    "vlog", "travel vlog", "travel diary", "travel guide", "review", "unboxing",
    "talking", "hosted by", "presented by", "tour guide", "hotel", "resort",
    "things to do", "itinerary", "day in my life", "reaction", "podcast",
    "tutorial", "how to", "sora", "runway ml", "midjourney", "pika labs",
    "ai generated", "made with ai", "3d animation", "render", "gameplay", "walk tour",
    "demo", "oled demo", "4k demo", "tv demo", "test", "settings", "bts", "commercial",
    "color grade", "lut", "luts", "davinci resolve", "premiere pro"
]

def search_youtube_candidates(query, count_per_query=6, sp=SP_VIDEO_4K_MEDIUM):
    """Fetches candidate video metadata from YouTube search."""
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
                    thumb_url = f"https://i.ytimg.com/vi/{vid}/maxresdefault.jpg"
                    fallback_thumb = thumbs[-1]["url"] if thumbs else f"https://i.ytimg.com/vi/{vid}/hqdefault.jpg"
                    
                    if vid and title:
                        candidates.append({
                            "video_id": vid,
                            "title": title,
                            "uploader": uploader,
                            "length_text": length_text,
                            "thumbnail_url": thumb_url,
                            "fallback_thumb": fallback_thumb,
                            "source_query": query
                        })
                    if len(candidates) >= count_per_query:
                        break
            if len(candidates) >= count_per_query:
                break
    except Exception as e:
        print(f"Error querying '{query}': {e}", file=sys.stderr)
        
    return candidates

def download_image_bytes(url, fallback_url=None):
    headers = {"User-Agent": "Mozilla/5.0"}
    for target in [url, fallback_url]:
        if not target:
            continue
        try:
            req = urllib.request.Request(target, headers=headers)
            with urllib.request.urlopen(req, timeout=8) as resp:
                data = resp.read()
                if len(data) > 1000:
                    return data
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

def extract_json_payload(raw_text: str):
    """Safely extracts JSON even if enclosed in markdown code fences."""
    text = raw_text.strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text)
        text = re.sub(r"\s*```$", "", text)
    return json.loads(text.strip())

class GeminiVisionClassifier:
    def __init__(self, api_key, model_name=None):
        self.api_key = api_key.strip()
        self.client = None
        self.model_candidates = []
        
        # Priority order for active vision-capable models recommended by Google AI
        preferred_order = [
            "gemini-3.8-flash",
            "gemini-3.7-flash",
            "gemini-3.6-flash",
            "gemini-3.5-flash",
            "gemini-flash-latest",
            "gemini-3.1-flash-lite",
            "gemini-2.5-flash-lite",
            "gemini-3-flash-preview"
        ]
        
        try:
            from google import genai
            self.client = genai.Client(api_key=self.api_key)
            all_models = [m.name.replace("models/", "") for m in self.client.models.list()]
            available_flash = [m for m in all_models if "flash" in m.lower() and "tts" not in m.lower() and "audio" not in m.lower()]
            print(f"Available Google AI Flash models: {available_flash}")
            
            for p in preferred_order:
                if p in available_flash:
                    self.model_candidates.append(p)
                    
            for m in available_flash:
                if m not in self.model_candidates and "2.5-flash" != m:
                    self.model_candidates.append(m)
        except Exception as e:
            print(f"google-genai SDK auto-discovery notice ({e})")

        if model_name:
            self.model_candidates.insert(0, model_name)
            
        if not self.model_candidates:
            self.model_candidates = preferred_order

        self.model_name = self.model_candidates[0]
        print(f"Prioritized Gemini models to try: {self.model_candidates}. Starting with '{self.model_name}'.")

    def evaluate_image(self, image_bytes: bytes, title: str, category: str):
        prompt = f"""You are the master art director and visual quality curator for AerialViews+, an open-source 4K screensaver for large OLED/Living Room TVs.
Audit this YouTube video thumbnail for pristine living room ambient screensaver playback.

Video Title: "{title}"
Target Category: {category}

STRICT REJECTION CRITERIA (Any of these is an immediate VETO):
1. TALKING HEADS & VLOGS: Human faces, tourists talking to the camera, walking tours with hands/selfie sticks, podcast hosts.
2. TEXT GRAPHICS & WATERMARKS: Large bold text titles (e.g. 'NORWAY 4K', 'CHICAGO', 'EPISODE 1', 'PART 2'), channel logos, badges, or watermarks taking up > 2% of the frame.
3. COMMERCIAL CLUTTER: Hotel reviews, resort commercials, price tags, store fronts.
4. FAKE / CGI SLOP: 3D video game graphics, synthetic cartoon renders, unnatural AI morphing, or static JPEG photos overlaid with fake digital rain.

ACCEPTANCE CRITERIA:
- Pristine, serene, high-aesthetic nature, drone flyovers, ocean reefs, city architecture, starry night sky, or wildlife in natural habitats.
- International Space Station (ISS) views of Earth with solar panels/robotic arms or auroras ARE ACCEPTABLE.
- Real fine-art camera cinematography.

Respond ONLY with a valid JSON object matching this schema:
{{
  "decision": "APPROVED" | "VETOED",
  "aesthetic_score": <number 0 to 100>,
  "waste_score": <number 0 to 100>,
  "reason": "<clear concise explanation>",
  "detected_waste_elements": ["text_watermark" | "talking_head" | "vlog_pov" | "cgi_render" | "indoor_commercial" | "static_photo_loop" | "none"],
  "visual_description": "<one sentence describing what is seen>"
}}"""

        # 1. Preferred path: Official google-genai SDK with multi-model fallback
        if self.client:
            for cand_model in list(self.model_candidates):
                try:
                    from google.genai import types
                    resp = self.client.models.generate_content(
                        model=cand_model,
                        contents=[
                            types.Part.from_bytes(data=image_bytes, mime_type="image/jpeg"),
                            prompt
                        ],
                        config=types.GenerateContentConfig(
                            response_mime_type="application/json",
                            temperature=0.2
                        )
                    )
                    parsed = extract_json_payload(resp.text)
                    if cand_model != self.model_name:
                        self.model_name = cand_model
                        print(f"Locked onto working Gemini model: '{self.model_name}'")
                    return parsed
                except Exception as e:
                    print(f"google-genai SDK call failed with '{cand_model}': {e}", file=sys.stderr)
                    continue

        # 2. Robust REST fallback with multi-model fallback
        b64_img = base64.b64encode(image_bytes).decode("utf-8")
        payload = {
            "contents": [{
                "parts": [
                    {"inline_data": {"mime_type": "image/jpeg", "data": b64_img}},
                    {"text": prompt}
                ]
            }],
            "generationConfig": {
                "response_mime_type": "application/json",
                "temperature": 0.2
            }
        }
        
        last_error = "Unknown error"
        for cand_model in list(self.model_candidates):
            endpoint = f"https://generativelanguage.googleapis.com/v1beta/models/{cand_model}:generateContent"
            req = urllib.request.Request(
                endpoint,
                data=json.dumps(payload).encode("utf-8"),
                headers={
                    "Content-Type": "application/json",
                    "x-goog-api-key": self.api_key
                }
            )
            try:
                with urllib.request.urlopen(req, timeout=15) as resp:
                    result_json = json.loads(resp.read().decode("utf-8"))
                    text = result_json["candidates"][0]["content"]["parts"][0]["text"]
                    parsed = extract_json_payload(text)
                    if cand_model != self.model_name:
                        self.model_name = cand_model
                        print(f"Locked onto working Gemini REST model: '{self.model_name}'")
                    return parsed
            except urllib.error.HTTPError as e:
                err_body = ""
                try:
                    err_body = e.read().decode("utf-8")
                except Exception:
                    pass
                last_error = f"HTTP {e.code}: {err_body[:100]}"
                print(f"Gemini REST with '{cand_model}' failed: {last_error}", file=sys.stderr)
                continue
            except Exception as e:
                last_error = str(e)
                print(f"Gemini REST with '{cand_model}' exception: {last_error}", file=sys.stderr)
                continue
                
        return {
            "decision": "VETOED",
            "aesthetic_score": 0,
            "waste_score": 100,
            "reason": f"Gemini API evaluation failed: {last_error}",
            "detected_waste_elements": ["api_error"],
            "visual_description": "Failed to analyze"
        }

def mine_blacklist_tokens(approved, eliminated):
    waste_titles = [e["title"].lower() for e in eliminated]
    approved_titles = [e["title"].lower() for e in approved]
    
    waste_words = []
    for t in waste_titles:
        tokens = re.findall(r"\b[a-z]{4,15}\b", t)
        waste_words.extend(tokens)
        
    approved_words = []
    for t in approved_titles:
        tokens = re.findall(r"\b[a-z]{4,15}\b", t)
        approved_words.extend(tokens)
        
    waste_counter = Counter(waste_words)
    approved_counter = Counter(approved_words)
    
    mined = []
    stopwords = {"video", "footage", "drone", "ultra", "relaxing", "nature", "scenic", "aerial", "peaceful", "calm", "music", "with", "from"}
    for word, count in waste_counter.most_common(50):
        if word in stopwords:
            continue
        app_count = approved_counter.get(word, 0)
        prob = count / (count + app_count)
        if count >= 3 and prob >= 0.75:
            mined.append({
                "token": word,
                "waste_count": count,
                "approved_count": app_count,
                "waste_probability": round(prob, 2),
                "recommended_exclusion": f"-{word}"
            })
    return mined

def run_gemini_curation(api_key, categories, candidates_per_cat=20, output_dir="curation_output"):
    os.makedirs(output_dir, exist_ok=True)
    classifier = GeminiVisionClassifier(api_key=api_key)
    
    approved_entries = []
    eliminated_entries = []
    query_stats = {}
    
    print("\n" + "=" * 80)
    print(f"STARTING AERIALVIEWS+ GEMINI VISION CURATION BENCHMARK ({datetime.now(timezone.utc).isoformat()}Z)")
    print(f"Categories ({len(categories)}): {', '.join(categories)}")
    print(f"Candidates Per Category: {candidates_per_cat}")
    print("=" * 80 + "\n")
    
    for cat in categories:
        cat_key = cat.strip().lower()
        queries = CATEGORY_QUERIES.get(cat_key, [])
        if not queries:
            continue
            
        sp_filter = SP_VIDEO_4K_LONG if cat_key in ["space", "weather"] else SP_VIDEO_4K_MEDIUM
        print(f"\n>>> Harvesting Category: [{cat_key.upper()}] ({len(queries)} queries)")
        
        cat_candidates = []
        for q in queries:
            query_stats[q] = {"category": cat_key, "audited": 0, "approved": 0, "eliminated": 0}
            results = search_youtube_candidates(q, count_per_query=6, sp=sp_filter)
            for res in results:
                if not any(c["video_id"] == res["video_id"] for c in cat_candidates):
                    cat_candidates.append(res)
            if len(cat_candidates) >= candidates_per_cat:
                break
                
        cat_candidates = cat_candidates[:candidates_per_cat]
        print(f"Auditing {len(cat_candidates)} candidates for category '{cat_key}' with Gemini Vision...")
        
        for cand in cat_candidates:
            vid = cand["video_id"]
            title = cand["title"]
            uploader = cand["uploader"]
            duration = parse_duration_seconds(cand["length_text"])
            source_q = cand["source_query"]
            
            if source_q in query_stats:
                query_stats[source_q]["audited"] += 1
                
            # Stage 1: Fast metadata checks
            title_lower = title.lower()
            if duration > 0 and duration < 180:
                eliminated_entries.append({
                    "video_id": vid, "title": title, "uploader": uploader, "category": cat_key,
                    "stage": "Stage 1 (Duration)", "reason": f"Video duration too short: {duration}s (< 3m)",
                    "url": f"https://youtu.be/{vid}"
                })
                if source_q in query_stats:
                    query_stats[source_q]["eliminated"] += 1
                continue
                
            matched_blacklist = next((b for b in TITLE_BLACKLIST if b in title_lower), None)
            if matched_blacklist:
                eliminated_entries.append({
                    "video_id": vid, "title": title, "uploader": uploader, "category": cat_key,
                    "stage": "Stage 1 (Metadata)", "reason": f"Title blacklist token matched: '{matched_blacklist}'",
                    "url": f"https://youtu.be/{vid}"
                })
                if source_q in query_stats:
                    query_stats[source_q]["eliminated"] += 1
                print(f"  ❌ [STAGE 1 VETO] Blacklist keyword '{matched_blacklist}': {title[:50]}...")
                continue
                
            # Stage 2: Gemini Vision Multimodal Inspection
            img_bytes = download_image_bytes(cand["thumbnail_url"], cand["fallback_thumb"])
            if not img_bytes:
                eliminated_entries.append({
                    "video_id": vid, "title": title, "uploader": uploader, "category": cat_key,
                    "stage": "Stage 2 (Vision)", "reason": "Failed to fetch thumbnail image",
                    "url": f"https://youtu.be/{vid}"
                })
                continue
                
            eval_res = classifier.evaluate_image(img_bytes, title=title, category=cat_key)
            time.sleep(0.5) # Gentle rate-limit spacing
            
            is_waste = (eval_res.get("decision") == "VETOED") or (eval_res.get("waste_score", 0) > 40)
            
            if is_waste:
                eliminated_entries.append({
                    "video_id": vid, "title": title, "uploader": uploader, "category": cat_key,
                    "stage": "Gemini Vision Veto",
                    "reason": eval_res.get("reason", "Vetoed by Gemini Vision"),
                    "detected_elements": eval_res.get("detected_waste_elements", []),
                    "waste_score": eval_res.get("waste_score", 100),
                    "url": f"https://youtu.be/{vid}"
                })
                if source_q in query_stats:
                    query_stats[source_q]["eliminated"] += 1
                print(f"  ❌ [GEMINI VETO] {eval_res.get('reason')} ({eval_res.get('waste_score')}%): {title[:50]}...")
            else:
                entry = {
                    "videoId": vid,
                    "title": title,
                    "uploaderName": uploader,
                    "durationSeconds": duration,
                    "categoryKey": cat_key,
                    "videoPageUrl": f"https://www.youtube.com/watch?v={vid}",
                    "streamQualityScore": eval_res.get("aesthetic_score", 95.0),
                    "visualDescription": eval_res.get("visual_description", ""),
                    "verifiedBy": "Gemini 1.5 Flash",
                    "verifiedAt": datetime.now(timezone.utc).isoformat() + "Z"
                }
                approved_entries.append(entry)
                if source_q in query_stats:
                    query_stats[source_q]["approved"] += 1
                print(f"  ✅ [GEMINI APPROVED] Aesthetic={eval_res.get('aesthetic_score')}%: {title[:50]}...")
                
    # Manifest paths
    manifest_path = os.path.join(output_dir, "curated_youtube_manifest.json")
    report_path = os.path.join(output_dir, "waste_elimination_report.json")
    summary_path = os.path.join(output_dir, "gemini_summary.json")
    step_summary_path = os.path.join(output_dir, "github_step_summary.md")
    
    with open(manifest_path, "w") as f:
        json.dump(approved_entries, f, indent=2)
    with open(report_path, "w") as f:
        json.dump(eliminated_entries, f, indent=2)
        
    total_audited = len(approved_entries) + len(eliminated_entries)
    acceptance_rate = round((len(approved_entries) / max(1, total_audited)) * 100, 1)
    mined_tokens = mine_blacklist_tokens(approved_entries, eliminated_entries)
    
    # Render Step Summary
    cat_summary = {}
    for q_data in query_stats.values():
        c = q_data["category"]
        if c not in cat_summary:
            cat_summary[c] = {"audited": 0, "approved": 0, "eliminated": 0}
        cat_summary[c]["audited"] += q_data["audited"]
        cat_summary[c]["approved"] += q_data["approved"]
        cat_summary[c]["eliminated"] += q_data["eliminated"]

    with open(step_summary_path, "w") as f:
        f.write("# 🛰️ AerialViews+ Gemini Vision Curation Summary\n\n")
        f.write(f"- **Evaluator:** `Google Gemini 1.5 Flash (Multimodal Vision & OCR)`\n")
        f.write(f"- **Timestamp:** `{datetime.now(timezone.utc).isoformat()}Z`\n")
        f.write(f"- **Total Audited:** `{total_audited}`\n")
        f.write(f"- **Approved (Pristine Ambient):** `{len(approved_entries)}` ({acceptance_rate}%)\n")
        f.write(f"- **Eliminated (Waste Purged):** `{len(eliminated_entries)}` ({round(100 - acceptance_rate, 1)}%)\n\n")

        f.write("### 📊 Performance by Category\n\n")
        f.write("| Category | Audited | Approved | Eliminated | Clean Ambient % |\n")
        f.write("| :--- | :--- | :--- | :--- | :--- |\n")
        for c, s in cat_summary.items():
            yld = round((s["approved"] / max(1, s["audited"])) * 100, 1)
            f.write(f"| **{c.capitalize()}** | {s['audited']} | {s['approved']} | {s['eliminated']} | **{yld}%** |\n")
        f.write("\n")

        f.write("### 🔍 Sample Gemini Decisions & Reasoning\n\n")
        f.write("| Video | Category | Verdict | Score | Gemini Reasoning |\n")
        f.write("| :--- | :--- | :--- | :--- | :--- |\n")
        for e in approved_entries[:6]:
            f.write(f"| [{e['title'][:35]}...]({e['videoPageUrl']}) | `{e['categoryKey']}` | **APPROVED** | {e['streamQualityScore']}% | {e.get('visualDescription', '')[:50]}... |\n")
        for e in eliminated_entries[:6]:
            f.write(f"| [{e['title'][:35]}...]({e['url']}) | `{e['category']}` | **VETOED** | {e.get('waste_score', 0)}% | {e.get('reason', '')[:50]}... |\n")
        f.write("\n")

        if mined_tokens:
            f.write("### 🚫 Auto-Mined Negative Tokens for Search Engine\n\n")
            f.write("| Token | Waste Count | Approved Count | Recommended Exclusion |\n")
            f.write("| :--- | :--- | :--- | :--- |\n")
            for t in mined_tokens[:8]:
                f.write(f"| `{t['token']}` | {t['waste_count']} | {t['approved_count']} | `{t['recommended_exclusion']}` |\n")
            f.write("\n")

    # If running in GitHub Actions, write directly to GITHUB_STEP_SUMMARY
    gh_step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if gh_step_summary:
        try:
            with open(step_summary_path, "r") as src, open(gh_step_summary, "a") as dst:
                dst.write(src.read())
        except Exception as e:
            print(f"Warning: Failed to write to GITHUB_STEP_SUMMARY: {e}", file=sys.stderr)
            
    print("\n" + "=" * 80)
    print("GEMINI CURATION BENCHMARK COMPLETE")
    print(f"Total Audited:    {total_audited}")
    print(f"Approved (Clean): {len(approved_entries)} ({acceptance_rate}%)")
    print(f"Eliminated:       {len(eliminated_entries)} ({round(100 - acceptance_rate, 1)}%)")
    print(f"Curated Manifest: {manifest_path}")
    print(f"Waste Report:     {report_path}")
    print(f"Step Summary:     {step_summary_path}")
    print("=" * 80 + "\n")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="AerialViews+ Gemini Vision Curation Pipeline")
    parser.add_argument("--categories", nargs="+", default=["drone", "nature", "ocean", "cities", "animals", "space", "weather", "winter"])
    parser.add_argument("--candidates-per-cat", type=int, default=20)
    parser.add_argument("--output-dir", type=str, default="curation_output")
    args = parser.parse_args()
    
    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        print("ERROR: GEMINI_API_KEY environment variable is not set.", file=sys.stderr)
        sys.exit(1)
        
    run_gemini_curation(
        api_key=api_key,
        categories=args.categories,
        candidates_per_cat=args.candidates_per_cat,
        output_dir=args.output_dir
    )
