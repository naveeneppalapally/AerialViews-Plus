#!/usr/bin/env python3
"""
discover_10k_candidates.py

Discovers candidate YouTube videos across 8 categories for the AerialViews+ app.
Searches YouTube by parsing ytInitialData, filters results, and saves state to JSON/JSONL.
"""

import os
import sys
import json
import time
import random
import argparse
import re
import logging
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
from urllib.parse import quote_plus
from datetime import datetime

# --- CONFIGURATION ---
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
STATE_FILE = "curation_state/10k_discovery_state.json"
CANDIDATES_FILE = "curation_state/10k_raw_candidates.jsonl"
SP_PARAMS = [
    "EgYQARgDcAE=",  # 4K + Medium (4-20 min)
    "EgYQARgCcAE="   # 4K + Long (>20 min)
]
MIN_DURATION_SEC = 180
MAX_DURATION_SEC = 43200

GLOBAL_NEGATIVES = "-vlog -review -walk -walking -talking -guide -tour -hotel -resort -itinerary -tips -podcast -reaction -sora -ai -cgi -render -demo -tutorial -how -unboxing -commercial -oled -test -settings -bts -lut -luts -davinci -premiere"

# Title blacklist
BLACKLIST_PATTERNS = [
    r'\bvlog\b', r'\breview\b', r'\bwalk(?:ing)?\b', r'\btour\b', r'\bguide\b',
    r'\btips\b', r'\btutorial\b', r'\bunboxing\b', r'\breaction\b', r'\bpodcast\b',
    r'\bhow to\b', r'\bgameplay\b', r'\bhotel\b', r'\bresort\b', r'\bitinerary\b',
    r'\bsora\b', r'\bmidjourney\b', r'\bchatgpt\b', r'\bai generated\b'
]

# --- CATEGORIES & QUERIES ---
CATEGORIES = ['drone', 'nature', 'ocean', 'cities', 'animals', 'space', 'weather', 'winter']

GEAR = ["DJI Inspire 3", "Mavic 3 Cine", "Sony FX3", "RED", "ProRes", "10-bit D-Log", "8K 60fps HDR", "4K 60fps"]
TECH = ["cinematic", "real footage", "no talking", "no music", "ambient", "slow", "flyover", "raw"]
LOCATIONS_BY_BIOME = {
    "drone": ["Geirangerfjord", "Lofoten", "Landmannalaugar", "Val di Funes", "Raja Ampat", "Milford Sound", "Tuscany", "Faroe Islands", "Patagonia", "Madeira", "Azores", "Ha Long Bay"],
    "nature": ["Yellowstone", "Yosemite", "Amazon Rainforest", "Serengeti", "Banff", "Jiuzhaigou", "Plitvice", "Zion", "Cairngorms", "Lake Bled"],
    "ocean": ["Great Barrier Reef", "Maldives", "Bora Bora", "Seychelles", "Palawan", "Galapagos", "Maui", "Fiji", "Tahiti", "Okinawa", "Bahamas"],
    "cities": ["Tokyo", "New York", "Singapore", "Dubai", "London", "Hong Kong", "Shanghai", "Paris", "Sydney", "Chicago", "Seoul", "Toronto"],
    "animals": ["Masai Mara", "Kruger", "Okavango", "Galapagos", "Borneo", "Pantanal", "Svalbard", "Yellowstone", "Madagascar", "Costa Rica"],
    "space": ["ISS", "Earth from space", "Orbit", "Milky Way timelapse", "Aurora borealis", "Night sky", "Deep space", "NASA", "James Webb"],
    "weather": ["Supercell", "Tornado", "Lightning", "Thunderstorm", "Monsoon", "Blizzard", "Hurricane", "Typhoon", "Storm clouds", "Rainstorm"],
    "winter": ["Swiss Alps", "Hokkaido", "Lapland", "Banff winter", "Tromso", "Antarctica", "Svalbard", "Greenland", "Dolomites winter", "Iceland winter"]
}
LANGUAGES = {
    "drone": ["4K ドローン 空撮 絶景", "4K Drohne Landschaft", "4K drone paysage", "4K dron paisaje"],
    "nature": ["4K 自然 絶景", "4K Natur", "4K nature", "4K naturaleza"],
    "ocean": ["4K 海 絶景", "4K Meer", "4K océan", "4K océano"],
    "cities": ["4K 都市", "4K Stadt", "4K ville", "4K ciudad"],
    "animals": ["4K 野生動物", "4K wilde Tiere", "4K animaux sauvages", "4K animales salvajes"],
    "space": ["4K 宇宙", "4K Weltraum", "4K espace", "4K espacio"],
    "weather": ["4K 天気", "4K Wetter", "4K météo", "4K clima"],
    "winter": ["4K 冬 絶景", "4K Winter Landschaft", "4K hiver", "4K invierno"]
}
CATEGORY_NEGATIVES = {
    "ocean": "-surfing -surfer -shark -cruise -party -boat",
    "cities": "-timelapse -hyperlapse -traffic -people -crowd -party",
    "animals": "-zoo -pet -domestic -hunting -poaching -funny",
    "space": "-scifi -movie -game -trailer -fiction",
    "weather": "-news -report -damage -aftermath",
    "drone": "-crash -race -racing -fpv -freestyle",
    "nature": "-camping -bushcraft -survival",
    "winter": "-skiing -snowboarding -skating -resort"
}

def generate_queries(category):
    queries = []
    locs = LOCATIONS_BY_BIOME.get(category, ["Earth"])
    cat_negatives = CATEGORY_NEGATIVES.get(category, "")
    
    # Gear + Location
    for loc in locs:
        gear = random.choice(GEAR)
        tech = random.choice(TECH)
        q = f"{loc} {category} {gear} {tech} {GLOBAL_NEGATIVES} {cat_negatives}".strip()
        q = re.sub(r'\s+', ' ', q)
        queries.append(q)
        
    # Languages
    for lang_q in LANGUAGES.get(category, []):
        tech = random.choice(TECH)
        q = f"{lang_q} {tech} {GLOBAL_NEGATIVES} {cat_negatives}".strip()
        queries.append(q)
        
    # Extra generic combos
    for i in range(10):
        gear = random.choice(GEAR)
        tech1 = random.choice(TECH)
        tech2 = random.choice(TECH)
        loc = random.choice(locs)
        q = f"{loc} {category} {gear} {tech1} {tech2} {GLOBAL_NEGATIVES} {cat_negatives}".strip()
        queries.append(q)
        
    return list(set(queries))

def setup_directories():
    os.makedirs(os.path.dirname(STATE_FILE), exist_ok=True)
    os.makedirs(os.path.dirname(CANDIDATES_FILE), exist_ok=True)

def load_state():
    if os.path.exists(STATE_FILE):
        with open(STATE_FILE, 'r', encoding='utf-8') as f:
            state = json.load(f)
            state['discovered_ids'] = set(state.get('discovered_ids', []))
            return state
    return {
        'discovered_ids': set(),
        'candidates_by_category': {cat: [] for cat in CATEGORIES},
        'queries_completed': [],
        'total_discovered': 0,
        'total_after_filter': 0,
        'last_updated': None
    }

def save_state(state):
    state_to_save = state.copy()
    state_to_save['discovered_ids'] = list(state['discovered_ids'])
    state_to_save['last_updated'] = datetime.now().isoformat()
    with open(STATE_FILE, 'w', encoding='utf-8') as f:
        json.dump(state_to_save, f, indent=2, ensure_ascii=False)

def append_candidate(candidate):
    with open(CANDIDATES_FILE, 'a', encoding='utf-8') as f:
        f.write(json.dumps(candidate, ensure_ascii=False) + '\n')

def parse_duration(length_str):
    parts = length_str.split(':')
    seconds = 0
    try:
        if len(parts) == 3:
            seconds = int(parts[0]) * 3600 + int(parts[1]) * 60 + int(parts[2])
        elif len(parts) == 2:
            seconds = int(parts[0]) * 60 + int(parts[1])
        elif len(parts) == 1:
            seconds = int(parts[0])
    except:
        pass
    return seconds

def is_blacklisted(title):
    title_lower = title.lower()
    for pattern in BLACKLIST_PATTERNS:
        if re.search(pattern, title_lower):
            return True
    return False

def search_youtube(query, sp):
    url = f"https://www.youtube.com/results?search_query={quote_plus(query)}&sp={quote_plus(sp)}"
    req = Request(url, headers={'User-Agent': USER_AGENT})
    
    html = ""
    backoff = 30
    for _ in range(5):
        try:
            with urlopen(req, timeout=15) as response:
                html = response.read().decode('utf-8')
                break
        except HTTPError as e:
            if e.code == 429:
                logging.warning(f"HTTP 429 Too Many Requests. Backing off for {backoff}s...")
                time.sleep(backoff)
                backoff *= 2
            else:
                logging.error(f"HTTP Error {e.code}")
                break
        except URLError as e:
            logging.error(f"URL Error: {e.reason}")
            time.sleep(5)
            break
        except Exception as e:
            logging.error(f"Request failed: {e}")
            time.sleep(5)
            break
            
    if not html:
        return []

    match = re.search(r'ytInitialData\s*=\s*({.*?});</script>', html)
    if not match:
        return []
    
    try:
        data = json.loads(match.group(1))
    except json.JSONDecodeError:
        return []

    results = []
    try:
        tabs = data['contents']['twoColumnSearchResultsRenderer']['primaryContents']['sectionListRenderer']['contents']
        for tab in tabs:
            if 'itemSectionRenderer' in tab:
                items = tab['itemSectionRenderer']['contents']
                for item in items:
                    if 'videoRenderer' in item:
                        vr = item['videoRenderer']
                        video_id = vr.get('videoId')
                        title = vr.get('title', {}).get('runs', [{}])[0].get('text', '')
                        
                        length_text = ""
                        if 'lengthText' in vr:
                            length_text = vr['lengthText'].get('simpleText', '')
                            
                        duration = parse_duration(length_text)
                        
                        uploader = ""
                        if 'ownerText' in vr:
                            uploader = vr['ownerText'].get('runs', [{}])[0].get('text', '')
                            
                        if video_id and title:
                            results.append({
                                'video_id': video_id,
                                'title': title,
                                'duration_seconds': duration,
                                'length_str': length_text,
                                'uploader': uploader
                            })
    except Exception:
        pass
        
    return results

def print_stats(state):
    print("\n--- Discovery Stats ---")
    print(f"Total Discovered (raw): {state['total_discovered']}")
    print(f"Total After Filter: {state['total_after_filter']}")
    print(f"Unique Video IDs: {len(state['discovered_ids'])}")
    print(f"Completed Queries: {len(state['queries_completed'])}")
    print("Candidates by Category:")
    for cat, items in state['candidates_by_category'].items():
        print(f"  {cat}: {len(items)}")
    print("-----------------------\n")

def main():
    parser = argparse.ArgumentParser(description="Discover candidate YouTube videos for AerialViews+.")
    parser.add_argument('--target', type=int, default=25000, help="Target number of raw candidates to discover")
    parser.add_argument('--categories', type=str, default="", help="Comma-separated list of categories to scan")
    parser.add_argument('--resume', action='store_true', help="Resume from checkpoint")
    parser.add_argument('--stats', action='store_true', help="Print current stats and exit")
    
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format='%(message)s')
    
    # Run from script's parent directory if possible
    os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    setup_directories()
    
    state = load_state()
    if not args.resume and not args.stats:
        state = {
            'discovered_ids': set(),
            'candidates_by_category': {cat: [] for cat in CATEGORIES},
            'queries_completed': [],
            'total_discovered': 0,
            'total_after_filter': 0,
            'last_updated': None
        }
        if os.path.exists(STATE_FILE):
            os.remove(STATE_FILE)
        if os.path.exists(CANDIDATES_FILE):
            os.remove(CANDIDATES_FILE)
            
    if args.stats:
        print_stats(state)
        return

    target_cats = CATEGORIES
    if args.categories:
        target_cats = [c.strip() for c in args.categories.split(',') if c.strip() in CATEGORIES]

    total_filtered = state['total_after_filter']

    for cat in target_cats:
        queries = generate_queries(cat)
        logging.info(f"Generated {len(queries)} queries for category: {cat}")
        
        for idx, q in enumerate(queries, 1):
            if q in state['queries_completed']:
                continue
                
            if total_filtered >= args.target:
                logging.info(f"Target of {args.target} reached. Stopping.")
                return

            new_candidates = 0
            
            for sp in SP_PARAMS:
                results = search_youtube(q, sp)
                state['total_discovered'] += len(results)
                
                for res in results:
                    vid = res['video_id']
                    if vid in state['discovered_ids']:
                        continue
                        
                    if not (MIN_DURATION_SEC <= res['duration_seconds'] <= MAX_DURATION_SEC):
                        continue
                        
                    if is_blacklisted(res['title']):
                        continue
                        
                    state['discovered_ids'].add(vid)
                    cand = {
                        'category': cat,
                        'query': q,
                        'video_id': vid,
                        'title': res['title'],
                        'duration_seconds': res['duration_seconds'],
                        'length_str': res['length_str'],
                        'uploader': res['uploader'],
                        'discovered_at': datetime.now().isoformat()
                    }
                    state['candidates_by_category'][cat].append(cand)
                    append_candidate(cand)
                    new_candidates += 1
                    total_filtered += 1
                    state['total_after_filter'] += 1
                    
                time.sleep(random.uniform(3, 5))
                
            state['queries_completed'].append(q)
            save_state(state)
            
            now_str = datetime.now().strftime("%H:%M:%S")
            logging.info(f"[{now_str}] [{cat}] Query {idx}/{len(queries)}: Found {new_candidates} new candidates (total: {total_filtered})")

if __name__ == '__main__':
    main()
