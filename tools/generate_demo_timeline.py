#!/usr/bin/env python3
"""
Generate a small, synthetic Google-Maps-Timeline export for screenshots.

Why synthetic?
  * Privacy: the real 60 MB export is your actual location history. You do NOT
    want that on a public store listing. This file contains invented tracks.
  * Realistic: this models an ordinary life, not a world tour. You mostly stay
    in your home city (Malmo, Sweden) — weekday commute to work and back,
    lazy or errand-y weekends, the occasional day trip across the bridge to
    Copenhagen or down to Lund/Ystad — with real vacations sprinkled in every
    couple of months. The last 7 days are a proper car road trip through the
    Alps (Malmo -> Hamburg -> Zurich -> Lucerne -> Interlaken -> Innsbruck ->
    Bolzano/Dolomites -> Munich -> home), driven the whole way, ending "today".
  * Two kinds of lines appear on the map:
      - long hops (flights) -> dashed connectors (the gap-splitting feature:
        any jump over 80 km between consecutive samples is dashed), and
      - drives -> long SOLID red lines, because the points are interpolated
        close together (well under 80 km) so they never trip the gap rule.
  * Still small enough: tens of thousands of points (a couple MB) load
    instantly in a test, but the map now tells a believable two-year story
    instead of a dense zig-zag around the globe.

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

# ---------------------------------------------------------------------------
# Home base & everyday places
# ---------------------------------------------------------------------------

HOME_LATLON = (55.6050, 13.0038)   # Malmo, Sweden
WORK_LATLON = (55.5910, 12.9950)   # a made-up office a couple km away

# Short day-trip destinations across the Oresund bridge / around Skane.
NEARBY = [
    ("Lund", 55.7047, 13.1910),         # ~16 km
    ("Copenhagen", 55.6761, 12.5683),   # ~28 km, over the bridge
    ("Ystad", 55.4295, 13.8204),        # ~52 km
    ("Helsingborg", 56.0465, 12.6945),  # ~65 km
]

# How the 1.7 km trip to the office gets made. Malmo is a cycling city and the
# office is walkable, so a car every single day would be the odd choice — and a
# Timeline with one movement type in it cannot show what the movement filter is
# for. Weighted, and picked once per day so the trip home matches the trip out.
#   (activity type, km/h, probability)
COMMUTE_MODES = [
    ("CYCLING",             16, 0.42),
    ("WALKING",              5, 0.16),
    ("IN_PASSENGER_VEHICLE", 26, 0.24),
    ("IN_BUS",              18, 0.18),
]

# Real vacations over the two years, spaced out so most of life is at home.
# "offset" = days before the dataset's end date that the trip STARTS.
# "mode": "flight" (far away, shows up as a dashed line) or "drive" (a solid
# line road trip, like the weekend hop to Gothenburg).
VACATIONS = [
    {"name": "Stockholm",  "lat": 59.3293, "lon": 18.0686,  "offset": 705, "days": 3, "mode": "flight"},
    {"name": "New York",   "lat": 40.7128, "lon": -74.0060, "offset": 655, "days": 5, "mode": "flight"},
    {"name": "Gothenburg", "lat": 57.7089, "lon": 11.9746,  "offset": 600, "days": 2, "mode": "drive"},
    {"name": "Rome",       "lat": 41.9028, "lon": 12.4964,  "offset": 545, "days": 6, "mode": "flight"},
    {"name": "Prague",     "lat": 50.0755, "lon": 14.4378,  "offset": 470, "days": 4, "mode": "flight"},
    {"name": "Dubai",      "lat": 25.2048, "lon": 55.2708,  "offset": 405, "days": 6, "mode": "flight"},
    {"name": "Barcelona",  "lat": 41.3851, "lon": 2.1734,   "offset": 330, "days": 5, "mode": "flight"},
    {"name": "Tokyo",      "lat": 35.6762, "lon": 139.6503, "offset": 245, "days": 8, "mode": "flight"},
    {"name": "Lisbon",     "lat": 38.7223, "lon": -9.1393,  "offset": 155, "days": 4, "mode": "flight"},
    {"name": "Vienna",     "lat": 48.2082, "lon": 16.3738,  "offset": 85,  "days": 4, "mode": "flight"},
    {"name": "Krakow",     "lat": 50.0647, "lon": 19.9450,  "offset": 35,  "days": 3, "mode": "flight"},
]

# ---------------------------------------------------------------------------
# Timeframe: two years, ending "today", with the last 7 days reserved for the
# Alps road trip.
# ---------------------------------------------------------------------------

END_DATE = datetime(2026, 7, 1, tzinfo=timezone.utc)     # midnight of the last day
TOTAL_DAYS = 730
TRIP_DAYS = 7
START_DATE = END_DATE - timedelta(days=TOTAL_DAYS)
TRIP_START = END_DATE - timedelta(days=TRIP_DAYS - 1)    # first day of the road trip


def iso(t: datetime) -> str:
    return t.strftime("%Y-%m-%dT%H:%M:%S.000+00:00")


def haversine_km(a, b):
    lat1, lat2 = math.radians(a[0]), math.radians(b[0])
    d_lat = lat2 - lat1
    d_lon = math.radians(b[1] - a[1])
    h = math.sin(d_lat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(d_lon / 2) ** 2
    return 2 * 6371.0 * math.asin(math.sqrt(h))


def _interp(a, b, step_km):
    """Intermediate points from a (exclusive) to b (inclusive), ~step_km apart."""
    n = max(1, int(math.ceil(haversine_km(a, b) / step_km)))
    out = []
    for k in range(1, n + 1):
        f = k / n
        out.append((a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f))
    return out


def dense_path(waypoints, t0, step_km=20.0, speed_kmh=80.0, jitter_km=1.2):
    """A ~road-following track through waypoints -> one solid line.

    Points are spaced ~step_km apart (well under the app's 80 km gap
    threshold) so the whole thing renders as one continuous line no matter
    how far apart the waypoints are. Per-hop time is derived from the actual
    (jittered) distance and speed_kmh, with +/-15-25% variance so it doesn't
    feel like a robot driving at a constant speed.
    """
    raw = [(waypoints[0][1], waypoints[0][2])]
    cur = raw[0]
    for (_, lat, lon) in waypoints[1:]:
        nxt = (lat, lon)
        raw.extend(_interp(cur, nxt, step_km))
        cur = nxt

    jittered = []
    for (lat, lon) in raw:
        jlat = lat + random.uniform(-jitter_km, jitter_km) / 111.0
        jlon = lon + random.uniform(-jitter_km, jitter_km) / (111.0 * max(0.2, math.cos(math.radians(lat))))
        jittered.append((jlat, jlon))

    pts = []
    t = t0
    pts.append((round(jittered[0][0], 6), round(jittered[0][1], 6), t))
    for i in range(1, len(jittered)):
        hop_km = haversine_km(jittered[i - 1], jittered[i])
        minutes = max(0.5, (hop_km / speed_kmh) * 60 * random.uniform(0.85, 1.25))
        t += timedelta(minutes=minutes)
        pts.append((round(jittered[i][0], 6), round(jittered[i][1], 6), t))
    return pts


def wander_track(lat0, lon0, t0, minutes, sample_every=(4, 9)):
    """A meandering walk/errand loop around a point. Sparse sampling (every
    ~4-9 min of activity, not every minute) keeps two years of this small."""
    pts = []
    lat, lon = lat0, lon0
    heading = random.uniform(0, 2 * math.pi)
    t = t0
    elapsed = 0
    while elapsed < minutes:
        heading += random.uniform(-0.7, 0.7)
        step = random.uniform(0.0008, 0.003)  # ~90..330 m
        lat += step * math.cos(heading)
        lon += step * math.sin(heading) / max(0.2, math.cos(math.radians(lat)))
        lat += (lat0 - lat) * 0.04
        lon += (lon0 - lon) * 0.04
        dt = random.randint(*sample_every)
        t += timedelta(minutes=dt)
        elapsed += dt
        pts.append((round(lat, 6), round(lon, 6), t))
    if not pts:
        pts.append((round(lat0, 6), round(lon0, 6), t0 + timedelta(minutes=1)))
    return pts


def track_km(track):
    """Length of a generated track, summed hop by hop.

    Used as the `distanceMeters` of the activity segment that labels a track.
    Google reports its own estimate there rather than measuring the polyline,
    but for synthetic data the polyline IS the ground truth, so summing it is
    the honest number.
    """
    return sum(haversine_km((track[i - 1][0], track[i - 1][1]),
                            (track[i][0], track[i][1]))
               for i in range(1, len(track)))


def path_segment(track):
    return {
        "startTime": iso(track[0][2]),
        "endTime": iso(track[-1][2]),
        "timelinePath": [
            {"point": f"{p[0]}°, {p[1]}°", "time": iso(p[2])} for p in track
        ],
    }


def visit(lat, lon, t0, t1, place_id, semantic="INFERRED_POINT_OF_INTEREST", prob=0.9):
    return {
        "startTime": iso(t0),
        "endTime": iso(t1),
        "visit": {
            "hierarchyLevel": 0,
            "probability": prob,
            "topCandidate": {
                "placeId": f"demo_{place_id}",
                "semanticType": semantic,
                "probability": prob,
                "placeLocation": {"latLng": f"{lat}°, {lon}°"},
            },
        },
    }


def activity(t0, t1, distance_m, kind, prob=0.95):
    return {
        "startTime": iso(t0),
        "endTime": iso(t1),
        "activity": {
            "distanceMeters": float(distance_m),
            "probability": prob,
            "topCandidate": {"type": kind, "probability": prob},
        },
    }


# ---------------------------------------------------------------------------
# Ordinary life: a workday, a weekend day, and a vacation.
# ---------------------------------------------------------------------------

def workday_segments(day_start):
    segments = []
    wake = day_start + timedelta(hours=random.uniform(6.4, 7.6))
    segments.append(visit(*HOME_LATLON, day_start, wake, "home_malmo", "INFERRED_HOME", 0.95))

    kind, speed, _ = random.choices(COMMUTE_MODES, weights=[m[2] for m in COMMUTE_MODES])[0]

    commute_out = dense_path([("Home",) + HOME_LATLON, ("Work",) + WORK_LATLON], wake,
                             step_km=0.6, speed_kmh=speed)
    segments.append(path_segment(commute_out))
    segments.append(activity(commute_out[0][2], commute_out[-1][2],
                              track_km(commute_out) * 1000, kind, 0.9))

    work_start = commute_out[-1][2]
    work_end = work_start + timedelta(hours=random.uniform(7.5, 9))
    segments.append(visit(*WORK_LATLON, work_start, work_end, "work_office", "INFERRED_WORK", 0.9))

    commute_back = dense_path([("Work",) + WORK_LATLON, ("Home",) + HOME_LATLON], work_end,
                              step_km=0.6, speed_kmh=speed * random.uniform(0.9, 1.05))
    segments.append(path_segment(commute_back))
    segments.append(activity(commute_back[0][2], commute_back[-1][2],
                              track_km(commute_back) * 1000, kind, 0.9))

    cursor = commute_back[-1][2]
    if random.random() < 0.18:  # gym / dinner / a friend's place some evenings
        out_start = cursor + timedelta(minutes=random.uniform(30, 90))
        outing = wander_track(HOME_LATLON[0] + random.uniform(-0.008, 0.008),
                               HOME_LATLON[1] + random.uniform(-0.008, 0.008),
                               out_start, minutes=random.uniform(45, 110))
        segments.append(path_segment(outing))
        segments.append(activity(outing[0][2], outing[-1][2],
                                  track_km(outing) * 1000, "WALKING", 0.88))
        cursor = outing[-1][2] + timedelta(minutes=random.uniform(5, 20))

    night_end = day_start + timedelta(days=1)
    segments.append(visit(*HOME_LATLON, cursor, night_end, "home_malmo", "INFERRED_HOME", 0.95))
    return segments


def weekend_segments(day_start):
    segments = []
    roll = random.random()

    if roll < 0.20:  # a day trip across the bridge or down the coast
        wake = day_start + timedelta(hours=random.uniform(8.5, 10))
        segments.append(visit(*HOME_LATLON, day_start, wake, "home_malmo", "INFERRED_HOME", 0.95))

        dest_name, dest_lat, dest_lon = random.choice(NEARBY)
        # Copenhagen and Lund are the two you'd sooner take the train to than
        # drive — the Oresund line goes straight there.
        by_train = dest_name in ("Copenhagen", "Lund") and random.random() < 0.65
        kind, speed = ("IN_TRAIN", 74) if by_train else ("IN_PASSENGER_VEHICLE", 82)

        depart = wake + timedelta(minutes=random.uniform(15, 45))
        out = dense_path([("Home",) + HOME_LATLON, (dest_name, dest_lat, dest_lon)], depart,
                         step_km=15, speed_kmh=speed)
        segments.append(path_segment(out))
        segments.append(activity(out[0][2], out[-1][2],
                                  track_km(out) * 1000, kind, 0.94))

        arrive = out[-1][2]
        wander = wander_track(dest_lat, dest_lon, arrive + timedelta(minutes=10), minutes=random.uniform(150, 300))
        segments.append(path_segment(wander))
        # Wandering a city on a day trip is walking, and saying so is what puts
        # a walking figure on the breakdown next to the drives.
        segments.append(activity(wander[0][2], wander[-1][2],
                                  track_km(wander) * 1000, "WALKING", 0.9))

        back_start = wander[-1][2] + timedelta(minutes=random.uniform(15, 45))
        back = dense_path([(dest_name, dest_lat, dest_lon), ("Home",) + HOME_LATLON], back_start,
                          step_km=15, speed_kmh=speed)
        segments.append(path_segment(back))
        segments.append(activity(back[0][2], back[-1][2],
                                  track_km(back) * 1000, kind, 0.94))
        cursor = back[-1][2]

    elif roll < 0.55:  # errands / a walk around town
        wake = day_start + timedelta(hours=random.uniform(9, 11))
        segments.append(visit(*HOME_LATLON, day_start, wake, "home_malmo", "INFERRED_HOME", 0.95))
        out_start = wake + timedelta(minutes=random.uniform(20, 60))
        walk = wander_track(HOME_LATLON[0], HOME_LATLON[1], out_start, minutes=random.uniform(60, 200))
        segments.append(path_segment(walk))
        # Some of these errands are done on a bike; around here most are.
        on_bike = random.random() < 0.35
        segments.append(activity(walk[0][2], walk[-1][2], track_km(walk) * 1000,
                                  "CYCLING" if on_bike else "WALKING", 0.87))
        cursor = walk[-1][2] + timedelta(minutes=random.uniform(10, 30))

    else:  # a quiet weekend at home
        cursor = day_start

    night_end = day_start + timedelta(days=1)
    segments.append(visit(*HOME_LATLON, cursor, night_end, "home_malmo", "INFERRED_HOME", 0.95))
    return segments


def vacation_segments(v, t_start):
    name, lat, lon, days, mode = v["name"], v["lat"], v["lon"], v["days"], v["mode"]
    dest = (lat, lon)
    segments = []

    depart = t_start + timedelta(hours=random.uniform(5, 8))
    segments.append(visit(*HOME_LATLON, t_start, depart, "home_malmo", "INFERRED_HOME", 0.95))

    if mode == "flight":
        arrive = depart + timedelta(hours=random.uniform(1.5, 4.5)) + timedelta(hours=random.uniform(1.5, 11))
        segments.append(activity(depart, arrive, haversine_km(HOME_LATLON, dest) * 1000, "FLYING", 0.97))
        cursor = arrive
    else:
        wp = [("Malmo",) + HOME_LATLON, (name, lat, lon)]
        drive = dense_path(wp, depart, step_km=20, speed_kmh=88)
        segments.append(path_segment(drive))
        segments.append(activity(drive[0][2], drive[-1][2],
                                  haversine_km(HOME_LATLON, dest) * 1000, "IN_PASSENGER_VEHICLE", 0.95))
        cursor = drive[-1][2]

    for _day_i in range(days):
        hotel_in = cursor + timedelta(minutes=random.uniform(15, 40))
        segments.append(visit(lat, lon, cursor, hotel_in, f"{name.lower()}_stay", "INFERRED_POINT_OF_INTEREST", 0.85))
        wander_start = hotel_in + timedelta(minutes=random.uniform(20, 60))
        wander = wander_track(lat, lon, wander_start, minutes=random.uniform(140, 280))
        segments.append(path_segment(wander))
        segments.append(activity(wander[0][2], wander[-1][2],
                                  track_km(wander) * 1000, "WALKING", 0.9))
        cursor = wander[-1][2] + timedelta(minutes=random.uniform(20, 60))

    if mode == "flight":
        depart2 = cursor + timedelta(hours=random.uniform(1, 4))
        arrive2 = depart2 + timedelta(hours=random.uniform(1.5, 11))
        segments.append(activity(depart2, arrive2, haversine_km(dest, HOME_LATLON) * 1000, "FLYING", 0.97))
        cursor = arrive2
    else:
        wp2 = [(name, lat, lon), ("Malmo",) + HOME_LATLON]
        drive2 = dense_path(wp2, cursor + timedelta(hours=random.uniform(0.5, 2)), step_km=20, speed_kmh=88)
        segments.append(path_segment(drive2))
        segments.append(activity(drive2[0][2], drive2[-1][2],
                                  haversine_km(dest, HOME_LATLON) * 1000, "IN_PASSENGER_VEHICLE", 0.95))
        cursor = drive2[-1][2]

    next_midnight = datetime(cursor.year, cursor.month, cursor.day, tzinfo=timezone.utc) + timedelta(days=1)
    segments.append(visit(*HOME_LATLON, cursor, next_midnight, "home_malmo", "INFERRED_HOME", 0.95))
    return segments, next_midnight


# ---------------------------------------------------------------------------
# The last 7 days: a real car road trip through the Alps.
# ---------------------------------------------------------------------------

def alps_road_trip_segments(trip_start):
    """Malmo -> Hamburg -> Zurich -> Lucerne -> Interlaken -> Innsbruck ->
    Bolzano (+ a Dolomites side loop) -> Munich -> Malmo, driven the whole
    way. Distances roughly match the real road network; each overnight stop
    gets an evening walk so the map shows both the drive and the visit."""
    segments = []

    def overnight_stop(city_lat, city_lon, place_id, arrive_t, walk_minutes, sleep_until):
        s = []
        hotel_in = arrive_t + timedelta(minutes=random.uniform(15, 30))
        s.append(visit(city_lat, city_lon, arrive_t, hotel_in, f"{place_id}_checkin", "INFERRED_POINT_OF_INTEREST", 0.85))
        walk = wander_track(city_lat, city_lon, hotel_in + timedelta(minutes=random.uniform(30, 60)), minutes=walk_minutes)
        s.append(path_segment(walk))
        s.append(activity(walk[0][2], walk[-1][2], track_km(walk) * 1000, "WALKING", 0.9))
        night_start = walk[-1][2] + timedelta(minutes=random.uniform(10, 25))
        s.append(visit(city_lat, city_lon, night_start, sleep_until, f"{place_id}_hotel", "INFERRED_POINT_OF_INTEREST", 0.9))
        return s, sleep_until

    def drive_leg(wp, depart_t, distance_m, step_km=18, speed_kmh=90):
        d = dense_path(wp, depart_t, step_km=step_km, speed_kmh=speed_kmh)
        segs = [path_segment(d), activity(d[0][2], d[-1][2], distance_m, "IN_PASSENGER_VEHICLE", 0.96)]
        return segs, d[-1][2]

    HAMBURG = (53.5511, 9.9937)
    ZURICH = (47.3769, 8.5417)
    LUCERNE = (47.0502, 8.3093)
    INTERLAKEN = (46.6863, 7.8632)
    INNSBRUCK = (47.2692, 11.4041)
    BOLZANO = (46.4983, 11.3548)
    CARE_ZZA = (46.3572, 11.5686)  # Lago di Carezza, Dolomites
    MUNICH = (48.1351, 11.5820)

    cursor = trip_start + timedelta(hours=8)  # depart Malmo ~08:00

    # Day 0: Malmo -> Hamburg (~320 km)
    legs, cursor = drive_leg([("Malmo",) + HOME_LATLON, ("Hamburg",) + HAMBURG], cursor, 320_000.0, speed_kmh=92)
    segments.extend(legs)
    stop, cursor = overnight_stop(*HAMBURG, "hamburg", cursor, 110, trip_start + timedelta(days=1, hours=7))
    segments.extend(stop)

    # Day 1: Hamburg -> Zurich (~800 km, the big push south)
    legs, cursor = drive_leg([("Hamburg",) + HAMBURG, ("Zurich",) + ZURICH], cursor, 800_000.0, speed_kmh=98)
    segments.extend(legs)
    stop, cursor = overnight_stop(*ZURICH, "zurich", cursor, 70, trip_start + timedelta(days=2, hours=8))
    segments.extend(stop)

    # Day 2: Zurich -> Lucerne (stop) -> Interlaken (scenic Alpine foothills)
    legs, cursor = drive_leg([("Zurich",) + ZURICH, ("Lucerne",) + LUCERNE], cursor, 50_000.0, step_km=12, speed_kmh=72)
    segments.extend(legs)
    lucerne_walk = wander_track(*LUCERNE, cursor + timedelta(minutes=10), minutes=70)
    segments.append(path_segment(lucerne_walk))
    segments.append(activity(lucerne_walk[0][2], lucerne_walk[-1][2],
                              track_km(lucerne_walk) * 1000, "WALKING", 0.9))
    depart2 = lucerne_walk[-1][2] + timedelta(minutes=random.uniform(15, 30))
    legs, cursor = drive_leg([("Lucerne",) + LUCERNE, ("Interlaken",) + INTERLAKEN], depart2, 70_000.0, step_km=10, speed_kmh=62)
    segments.extend(legs)
    stop, cursor = overnight_stop(*INTERLAKEN, "interlaken", cursor, 140, trip_start + timedelta(days=3, hours=8))
    segments.extend(stop)

    # Day 3: Interlaken -> Innsbruck, crossing the Alps
    legs, cursor = drive_leg([("Interlaken",) + INTERLAKEN, ("Innsbruck",) + INNSBRUCK], cursor, 230_000.0, step_km=14, speed_kmh=66)
    segments.extend(legs)
    stop, cursor = overnight_stop(*INNSBRUCK, "innsbruck", cursor, 100, trip_start + timedelta(days=4, hours=8))
    segments.extend(stop)

    # Day 4: Innsbruck -> Bolzano over the Brenner Pass, then a Dolomites loop
    legs, cursor = drive_leg([("Innsbruck",) + INNSBRUCK, ("Bolzano",) + BOLZANO], cursor, 120_000.0, step_km=12, speed_kmh=70)
    segments.extend(legs)
    hotel_in = cursor + timedelta(minutes=random.uniform(15, 30))
    segments.append(visit(*BOLZANO, cursor, hotel_in, "bolzano_checkin", "INFERRED_POINT_OF_INTEREST", 0.85))
    loop_depart = hotel_in + timedelta(hours=random.uniform(0.8, 1.3))
    legs, loop_cursor = drive_leg([("Bolzano",) + BOLZANO, ("Lago di Carezza",) + CARE_ZZA], loop_depart, 35_000.0, step_km=8, speed_kmh=52)
    segments.extend(legs)
    lake_walk = wander_track(*CARE_ZZA, loop_cursor + timedelta(minutes=10), minutes=50)
    segments.append(path_segment(lake_walk))
    segments.append(activity(lake_walk[0][2], lake_walk[-1][2],
                              track_km(lake_walk) * 1000, "HIKING", 0.88))
    back_depart = lake_walk[-1][2] + timedelta(minutes=random.uniform(15, 30))
    legs, cursor = drive_leg([("Lago di Carezza",) + CARE_ZZA, ("Bolzano",) + BOLZANO], back_depart, 35_000.0, step_km=8, speed_kmh=55)
    segments.extend(legs)
    night_start = cursor + timedelta(minutes=random.uniform(15, 30))
    sleep_until = trip_start + timedelta(days=5, hours=8)
    segments.append(visit(*BOLZANO, night_start, sleep_until, "bolzano_hotel", "INFERRED_POINT_OF_INTEREST", 0.9))
    cursor = sleep_until

    # Day 5: Bolzano -> Munich (~300 km, heading north again)
    legs, cursor = drive_leg([("Bolzano",) + BOLZANO, ("Munich",) + MUNICH], cursor, 300_000.0, speed_kmh=92)
    segments.extend(legs)
    stop, cursor = overnight_stop(*MUNICH, "munich", cursor, 90, trip_start + timedelta(days=6, hours=7))
    segments.extend(stop)

    # Day 6 (today): Munich -> Malmo, the long final push home (~900 km)
    legs, cursor = drive_leg([("Munich",) + MUNICH, ("Malmo",) + HOME_LATLON], cursor, 900_000.0, speed_kmh=100)
    segments.extend(legs)
    segments.append(visit(*HOME_LATLON, cursor, cursor + timedelta(hours=3), "home_malmo", "INFERRED_HOME", 0.95))

    return segments


def main():
    segments = []
    current = START_DATE

    vacation_by_date = {}
    for v in VACATIONS:
        start_date = END_DATE - timedelta(days=v["offset"])
        vacation_by_date[start_date.date()] = v

    while current.date() < TRIP_START.date():
        d = current.date()
        if d in vacation_by_date:
            segs, current = vacation_segments(vacation_by_date[d], current)
            segments.extend(segs)
            continue
        if current.weekday() < 5:
            segments.extend(workday_segments(current))
        else:
            segments.extend(weekend_segments(current))
        current += timedelta(days=1)

    segments.extend(alps_road_trip_segments(TRIP_START))

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
    vac_names = ", ".join(v["name"] for v in VACATIONS)
    print(f"Wrote {out_path}")
    print(f"  segments: {len(segments)}  path points: {n_pts}  size: {size_kb:.0f} KB")
    print(f"  span: {iso(START_DATE)} .. {iso(END_DATE)}  ({TOTAL_DAYS} days)")
    print(f"  home base: Malmo, Sweden")
    print(f"  vacations ({len(VACATIONS)}): {vac_names}")
    print(f"  last {TRIP_DAYS} days: Alps road trip, Malmo -> Hamburg -> Zurich -> "
          f"Lucerne -> Interlaken -> Innsbruck -> Bolzano -> Munich -> Malmo")


if __name__ == "__main__":
    main()
