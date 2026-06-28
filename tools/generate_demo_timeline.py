#!/usr/bin/env python3
"""
Generate a small, synthetic Google-Maps-Timeline export for screenshots.

Why synthetic?
  * Privacy: the real 60 MB export is your actual location history. You do NOT
    want that on a public store listing. This file contains invented tracks.
  * Looks great: it spans several continents, so the in-app map shows a
    world-wide route. Two kinds of lines appear:
      - long ocean hops -> dashed "flight" connectors (the gap-splitting
        feature: any jump over 80 km is dashed), and
      - European ROAD TRIPS -> long SOLID red lines, because the points are
        interpolated close together (~25 km) so they never trip the gap rule.
  * Small: a few thousand points (~a few hundred KB) load instantly in a test,
    yet the map still looks rich.

Output format: PHONE_TAKEOUT ( { "semanticSegments": [...] } ) — the current
on-device export the parser auto-detects as "Phone". Points are degree-string
"lat°, lon°" with ISO-8601 timestamps, exactly like a real phone export.

Run:  python tools/generate_demo_timeline.py
It writes app/src/androidTest/assets/timeline_demo.json
"""

import json
import math
import os
import random
from datetime import datetime, timedelta, timezone

random.seed(42)  # deterministic — same demo file every run

# (name, lat, lon) of city centres, in travel order around the globe.
CITIES = [
    ("New York",    40.7128,  -74.0060),
    ("London",      51.5074,   -0.1278),
    ("Paris",       48.8566,    2.3522),
    ("Cairo",       30.0444,   31.2357),
    ("Dubai",       25.2048,   55.2708),
    ("Mumbai",      19.0760,   72.8777),
    ("Tokyo",       35.6762,  139.6503),
    ("Sydney",     -33.8688,  151.2093),
    ("Cape Town",  -33.9249,   18.4241),
    ("Sao Paulo",  -23.5505,  -46.6333),
]

# Road trips, keyed by the city you arrive in. After wandering that city we
# "drive" through these waypoints; the route is interpolated densely so it
# renders as one long SOLID red line (no dashing). Each list starts at the
# host city so the drive connects to that city's cluster.
ROAD_TRIPS = {
    # A loop up Great Britain.
    "London": [
        ("London",      51.5074,  -0.1278),
        ("Oxford",      51.7520,  -1.2577),
        ("Birmingham",  52.4862,  -1.8904),
        ("Manchester",  53.4808,  -2.2426),
        ("Leeds",       53.8008,  -1.5491),
        ("Newcastle",   54.9783,  -1.6178),
        ("Edinburgh",   55.9533,  -3.1883),
    ],
    # A continental drive across central Europe.
    "Paris": [
        ("Paris",       48.8566,   2.3522),
        ("Brussels",    50.8503,   4.3517),
        ("Amsterdam",   52.3676,   4.9041),
        ("Cologne",     50.9375,   6.9603),
        ("Frankfurt",   50.1109,   8.6821),
        ("Stuttgart",   48.7758,   9.1829),
        ("Munich",      48.1351,  11.5820),
        ("Zurich",      47.3769,   8.5417),
        ("Milan",       45.4642,   9.1900),
    ],
}

# A point every ~1 min while moving; ~3 days of wandering per city.
POINTS_PER_CITY = 260
START = datetime(2026, 4, 1, 8, 0, 0, tzinfo=timezone.utc)
DAYS_PER_CITY = 3
DRIVE_STEP_KM = 25.0       # spacing between interpolated drive points (< 80 km)
DRIVE_MIN_PER_HOP = 9      # minutes of "driving" between those points


def iso(t: datetime) -> str:
    # e.g. "2026-04-01T08:00:00.000+00:00"
    return t.strftime("%Y-%m-%dT%H:%M:%S.000+00:00")


def haversine_km(a, b):
    lat1, lat2 = math.radians(a[0]), math.radians(b[0])
    d_lat = lat2 - lat1
    d_lon = math.radians(b[1] - a[1])
    h = math.sin(d_lat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(d_lon / 2) ** 2
    return 2 * 6371.0 * math.asin(math.sqrt(h))


def city_track(lat0, lon0, t0, n):
    """A plausible meandering walk/drive around a city centre."""
    pts = []
    lat, lon = lat0, lon0
    heading = random.uniform(0, 2 * math.pi)
    t = t0
    for i in range(n):
        # Occasionally turn; otherwise keep roughly the same heading.
        heading += random.uniform(-0.6, 0.6)
        step = random.uniform(0.0006, 0.0022)  # ~60..240 m
        lat += step * math.cos(heading)
        lon += step * math.sin(heading) / max(0.2, math.cos(math.radians(lat)))
        # Gentle pull back toward the centre so we don't wander off the map.
        lat += (lat0 - lat) * 0.03
        lon += (lon0 - lon) * 0.03
        # 1..3 min between samples.
        t += timedelta(minutes=random.choice([1, 1, 2, 3]))
        pts.append((round(lat, 6), round(lon, 6), t))
    return pts


def _interp(a, b, step_km):
    """Intermediate points from a (exclusive) to b (inclusive), ~step_km apart."""
    n = max(1, int(math.ceil(haversine_km(a, b) / step_km)))
    out = []
    for k in range(1, n + 1):
        f = k / n
        out.append((a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f))
    return out


def drive_track(waypoints, t0):
    """Dense, ~road-following track through waypoints -> one solid line."""
    pts = []
    t = t0
    cur = (waypoints[0][1], waypoints[0][2])
    pts.append((round(cur[0], 6), round(cur[1], 6), t))  # start point
    for (_, lat, lon) in waypoints[1:]:
        nxt = (lat, lon)
        for (plat, plon) in _interp(cur, nxt, DRIVE_STEP_KM):
            # small jitter so the line is not perfectly straight between waypoints
            jlat = plat + random.uniform(-0.02, 0.02)
            jlon = plon + random.uniform(-0.02, 0.02)
            t += timedelta(minutes=DRIVE_MIN_PER_HOP)
            pts.append((round(jlat, 6), round(jlon, 6), t))
        cur = nxt
    return pts


def path_segment(track):
    return {
        "startTime": iso(track[0][2]),
        "endTime": iso(track[-1][2]),
        "timelinePath": [
            {"point": f"{p[0]}°, {p[1]}°", "time": iso(p[2])} for p in track
        ],
    }


def main():
    segments = []
    t = START

    for idx, (name, lat0, lon0) in enumerate(CITIES):
        track = city_track(lat0, lon0, t, POINTS_PER_CITY)
        seg_end = track[-1][2]
        segments.append(path_segment(track))

        # A "visit" segment at the city centre (adds realism + visit count).
        segments.append({
            "startTime": iso(seg_end),
            "endTime": iso(seg_end + timedelta(hours=10)),
            "visit": {
                "hierarchyLevel": 0,
                "probability": 0.95,
                "topCandidate": {
                    "placeId": f"demo_{name.replace(' ', '_').lower()}",
                    "semanticType": "INFERRED_HOME" if idx == 0 else "INFERRED_POINT_OF_INTEREST",
                    "probability": 0.9,
                    "placeLocation": {"latLng": f"{lat0}°, {lon0}°"},
                },
            },
        })

        # Optional road trip out of this city -> long solid red line.
        last_time = seg_end
        if name in ROAD_TRIPS:
            drive = drive_track(ROAD_TRIPS[name], seg_end + timedelta(hours=2))
            segments.append(path_segment(drive))
            segments.append({
                "startTime": iso(drive[0][2]),
                "endTime": iso(drive[-1][2]),
                "activity": {
                    "distanceMeters": 600_000.0,
                    "probability": 0.97,
                    "topCandidate": {"type": "IN_PASSENGER_VEHICLE", "probability": 0.95},
                },
            })
            last_time = drive[-1][2]

        # The hop to the next city = a big time + distance gap -> dashed flight.
        next_city_time = seg_end + timedelta(days=DAYS_PER_CITY)
        if idx < len(CITIES) - 1:
            segments.append({
                "startTime": iso(last_time + timedelta(hours=4)),
                "endTime": iso(next_city_time),
                "activity": {
                    "distanceMeters": 8_000_000.0,
                    "probability": 0.99,
                    "topCandidate": {"type": "FLYING", "probability": 0.98},
                },
            })
        t = next_city_time

    doc = {"semanticSegments": segments}

    out_dir = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "app", "src", "androidTest", "assets",
    )
    os.makedirs(out_dir, exist_ok=True)
    out_path = os.path.join(out_dir, "timeline_demo.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, separators=(",", ":"))

    n_pts = sum(len(s.get("timelinePath", [])) for s in segments)
    size_kb = os.path.getsize(out_path) / 1024
    print(f"Wrote {out_path}")
    print(f"  segments: {len(segments)}  path points: {n_pts}  size: {size_kb:.0f} KB")
    print(f"  road trips (solid lines): {', '.join(ROAD_TRIPS.keys())}")
    print(f"  span: {iso(START)} .. {iso(t)}")


if __name__ == "__main__":
    main()
