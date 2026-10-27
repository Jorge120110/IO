#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Servidor Web de Escritorio y Motor de Sincronizacion en Tiempo Real para GeoRuta SOS.
- Sirve la Aplicacion Web para PC en http://localhost:8080
- Escucha en el puerto P2P 45891 y UDP 45890 para sincronizacion automatica en red local con Android
- Configura automaticamente puente USB ADB (adb reverse tcp:45892 tcp:8080 y adb forward)
- Conecta con el grafo vial real de corredor_suroriente.db (186,050 nodos y 461,958 aristas) para
  estaciones dinamicas y calculo de rutas optimas con el algoritmo de Dijkstra.
"""

import heapq
import http.server
import json
import math
import os
import socket
import socketserver
import sqlite3
import subprocess
import threading
import time
import urllib.parse
import urllib.request
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = BASE_DIR.parent
CORREDOR_DB_PATH = PROJECT_ROOT / "app" / "src" / "main" / "assets" / "databases" / "corredor_suroriente.db"
WEB_SYNC_DB_PATH = BASE_DIR / "georutas_web_sync.db"
INDEX_HTML_PATH = BASE_DIR / "index.html"

WEB_HTTP_PORT = 8080
P2P_HTTP_PORT = 45891
P2P_UDP_PORT = 45890
ADB_REVERSE_PORT = 45892
ADB_FORWARD_PORT = 45893
CLOUD_RELAY_URL = "https://ntfy.sh/georutas_sos_gt_clean_v5"
UDP_BEACON_PREFIX = "GEORUTA_SOS_PEER:"

DEFAULT_STATION_NODE_ID = 123699
DEFAULT_STATION_LAT = 14.6277121
DEFAULT_STATION_LNG = -90.5602439

db_lock = threading.RLock()
discovered_peer_ips = set()
processed_cloud_msg_ids = set()
last_published_hash = ""
adb_bridge_active = False

# Plantillas dinamicas relativas a la ubicacion exacta del usuario (sin 'en Mi Ubicacion Exacta')
STATION_TEMPLATES = {
    "POLICIA": [
        ("Subestacion PNC Sector Norte", 0.0031, 0.0007),
        ("Comisaria PNC Sector Oriente", -0.0022, 0.0051),
        ("Delegacion PNC Sector Occidente", 0.0019, -0.0076),
        ("Destacamento PNC Sector Sur", -0.0104, -0.0021),
    ],
    "HOSPITAL": [
        ("Centro de Emergencia Medica Sector Norte", 0.0028, -0.0011),
        ("Hospital / Urgencias Sector Oriente", 0.0014, 0.0054),
        ("Centro Hospitalario Sector Occidente", -0.0029, -0.0073),
        ("Hospital Regional Sector Sur", -0.0108, 0.0025),
    ],
    "BOMBEROS": [
        ("Compania de Bomberos Sector Norte", 0.0034, 0.0014),
        ("Estacion Bomberos Voluntarios Sector Oriente", -0.0035, 0.0046),
        ("Estacion Bomberos Municipales Sector Occidente", 0.0038, -0.0069),
        ("Estacion Central de Bomberos Sector Sur", -0.0112, -0.0010),
    ],
}


def haversine_meters(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    r = 6371000.0
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lng2 - lng1)
    a = math.sin(dphi / 2.0) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2.0) ** 2
    return 2.0 * r * math.atan2(math.sqrt(a), math.sqrt(max(0.0, 1.0 - a)))


def get_local_lan_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(0.5)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def enforce_single_active_order_per_agent(cur):
    """
    Garantiza que ningun agente tenga dos o mas ordenes activas simultaneas en SQLite.
    """
    cur.execute(
        """
        SELECT id, assigned_agent_dpi, assigned_agent_id
        FROM emergency_orders
        WHERE status IN ('ASSIGNED', 'IN_PROGRESS')
        ORDER BY assigned_at DESC
        """
    )
    rows = cur.fetchall()
    seen_agents = set()
    now_ms = int(time.time() * 1000)
    for oid, dpi, aid in rows:
        key = dpi if dpi else f"id_{aid}"
        if key in seen_agents:
            cur.execute(
                "UPDATE emergency_orders SET status = 'COMPLETED', completed_at = ? WHERE id = ?",
                (now_ms, oid),
            )
        else:
            seen_agents.add(key)


def init_web_sync_db():
    with db_lock:
        conn = sqlite3.connect(str(WEB_SYNC_DB_PATH))
        cur = conn.cursor()
        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                full_name TEXT NOT NULL,
                dpi TEXT NOT NULL UNIQUE,
                password TEXT NOT NULL,
                phones TEXT NOT NULL,
                address TEXT NOT NULL,
                nip TEXT,
                role TEXT NOT NULL,
                service_type TEXT NOT NULL,
                approval_status TEXT NOT NULL,
                station_node_id INTEGER NOT NULL,
                station_lat REAL NOT NULL,
                station_lng REAL NOT NULL,
                created_at INTEGER NOT NULL
            )
            """
        )
        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS emergency_orders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                assigned_agent_id INTEGER NOT NULL,
                assigned_agent_dpi TEXT NOT NULL,
                dispatcher_admin_id INTEGER NOT NULL,
                origin_node_id INTEGER NOT NULL,
                origin_lat REAL NOT NULL,
                origin_lng REAL NOT NULL,
                destination_node_id INTEGER NOT NULL,
                destination_lat REAL NOT NULL,
                destination_lng REAL NOT NULL,
                description TEXT,
                status TEXT NOT NULL,
                assigned_at INTEGER NOT NULL UNIQUE,
                completed_at INTEGER,
                recalculation_count INTEGER NOT NULL DEFAULT 0
            )
            """
        )
        enforce_single_active_order_per_agent(cur)
        conn.commit()
        conn.close()


def find_nearest_graph_node(lat: float, lng: float, delta: float = 0.02) -> dict:
    if not CORREDOR_DB_PATH.exists():
        return {"nodeId": DEFAULT_STATION_NODE_ID, "latitude": lat, "longitude": lng}
    try:
        conn = sqlite3.connect(f"file:{CORREDOR_DB_PATH}?mode=ro", uri=True)
        cur = conn.cursor()
        cur.execute(
            """
            SELECT node_id, latitude, longitude
            FROM graph_nodes
            WHERE latitude BETWEEN ? AND ?
              AND longitude BETWEEN ? AND ?
            ORDER BY ((latitude - ?) * (latitude - ?)) + ((longitude - ?) * (longitude - ?)) ASC
            LIMIT 1
            """,
            (lat - delta, lat + delta, lng - delta, lng + delta, lat, lat, lng, lng),
        )
        row = cur.fetchone()
        if not row:
            cur.execute(
                """
                SELECT node_id, latitude, longitude
                FROM graph_nodes
                ORDER BY ((latitude - ?) * (latitude - ?)) + ((longitude - ?) * (longitude - ?)) ASC
                LIMIT 1
                """,
                (lat, lat, lng, lng),
            )
            row = cur.fetchone()
        conn.close()
        if row:
            return {"nodeId": int(row[0]), "latitude": float(row[1]), "longitude": float(row[2])}
    except Exception:
        pass
    return {"nodeId": DEFAULT_STATION_NODE_ID, "latitude": lat, "longitude": lng}


def compute_dijkstra_route(origin_lat: float, origin_lng: float, dest_lat: float, dest_lng: float) -> list:
    """
    Calcula la ruta mas corta calle por calle sobre la red vial real de corredor_suroriente.db
    usando el algoritmo de Dijkstra (columnas from_node_id, to_node_id, weight).
    """
    if CORREDOR_DB_PATH.exists():
        try:
            start_node = find_nearest_graph_node(origin_lat, origin_lng, 0.03)
            end_node = find_nearest_graph_node(dest_lat, dest_lng, 0.03)
            start_id = start_node["nodeId"]
            end_id = end_node["nodeId"]

            if start_id != end_id:
                for pad in (0.025, 0.055):
                    min_lat = min(start_node["latitude"], end_node["latitude"]) - pad
                    max_lat = max(start_node["latitude"], end_node["latitude"]) + pad
                    min_lng = min(start_node["longitude"], end_node["longitude"]) - pad
                    max_lng = max(start_node["longitude"], end_node["longitude"]) + pad

                    conn = sqlite3.connect(f"file:{CORREDOR_DB_PATH}?mode=ro", uri=True)
                    cur = conn.cursor()
                    cur.execute(
                        """
                        SELECT node_id, latitude, longitude
                        FROM graph_nodes
                        WHERE latitude BETWEEN ? AND ?
                          AND longitude BETWEEN ? AND ?
                        """,
                        (min_lat, max_lat, min_lng, max_lng),
                    )
                    nodes = {}
                    for nid, nlat, nlng in cur.fetchall():
                        nodes[int(nid)] = (float(nlat), float(nlng))

                    if start_id not in nodes or end_id not in nodes or len(nodes) > 85000:
                        conn.close()
                        continue

                    cur.execute(
                        """
                        SELECT e.from_node_id, e.to_node_id, e.weight
                        FROM graph_edges e
                        INNER JOIN graph_nodes n ON n.node_id = e.from_node_id
                        WHERE n.latitude BETWEEN ? AND ?
                          AND n.longitude BETWEEN ? AND ?
                        """,
                        (min_lat, max_lat, min_lng, max_lng),
                    )
                    adj = {}
                    for u, v, dist in cur.fetchall():
                        u_id = int(u)
                        v_id = int(v)
                        w = float(dist)
                        adj.setdefault(u_id, []).append((v_id, w))
                        adj.setdefault(v_id, []).append((u_id, w * 1.25))
                    conn.close()

                    pq = [(0.0, start_id)]
                    dist_map = {start_id: 0.0}
                    prev_map = {}
                    visited = set()

                    while pq:
                        d, u = heapq.heappop(pq)
                        if u in visited:
                            continue
                        visited.add(u)
                        if u == end_id:
                            break
                        for v, weight in adj.get(u, ()):
                            if v in visited:
                                continue
                            nd = d + weight
                            if nd < dist_map.get(v, float("inf")):
                                dist_map[v] = nd
                                prev_map[v] = u
                                heapq.heappush(pq, (nd, v))

                    if end_id in prev_map:
                        path_ids = []
                        curr = end_id
                        while curr is not None:
                            path_ids.append(curr)
                            curr = prev_map.get(curr)
                        path_ids.reverse()
                        route_pts = [{"lat": origin_lat, "lng": origin_lng}]
                        for nid in path_ids:
                            if nid in nodes:
                                route_pts.append({"lat": nodes[nid][0], "lng": nodes[nid][1]})
                        route_pts.append({"lat": dest_lat, "lng": dest_lng})
                        if len(route_pts) > 2:
                            return route_pts
        except Exception:
            pass

    # Respaldo vial por calles (OSRM) si los puntos estan fuera del area de corredor_suroriente.db
    try:
        osrm_url = (
            f"https://router.project-osrm.org/route/v1/driving/"
            f"{origin_lng},{origin_lat};{dest_lng},{dest_lat}?overview=full&geometries=geojson"
        )
        req = urllib.request.Request(osrm_url, headers={"User-Agent": "GeoRutaSOS/1.0"})
        with urllib.request.urlopen(req, timeout=2.5) as resp:
            if resp.status == 200:
                osrm_data = json.loads(resp.read().decode("utf-8", errors="ignore"))
                routes = osrm_data.get("routes", [])
                if routes:
                    coords = routes[0].get("geometry", {}).get("coordinates", [])
                    if len(coords) >= 2:
                        return [{"lat": float(c[1]), "lng": float(c[0])} for c in coords]
    except Exception:
        pass

    return build_interpolated_route(origin_lat, origin_lng, dest_lat, dest_lng)


def build_interpolated_route(lat1: float, lng1: float, lat2: float, lng2: float, steps: int = 12) -> list:
    pts = []
    for i in range(steps + 1):
        t = i / float(steps)
        pts.append({
            "lat": lat1 + (lat2 - lat1) * t,
            "lng": lng1 + (lng2 - lng1) * t,
        })
    return pts


def get_dynamic_stations_near_applicant(service_type: str, lat: float, lng: float, area_name: str = "") -> list:
    templates = STATION_TEMPLATES.get(service_type, STATION_TEMPLATES["POLICIA"])
    clean_area = f" - {area_name.strip()}" if area_name and area_name.strip() else ""
    options = []
    for base_title, dlat, dlng in templates:
        target_lat = lat + dlat
        target_lng = lng + dlng
        snapped = find_nearest_graph_node(target_lat, target_lng, 0.006)
        final_lat = snapped["latitude"]
        final_lng = snapped["longitude"]
        node_id = snapped["nodeId"]
        dist_m = haversine_meters(lat, lng, final_lat, final_lng)
        options.append({
            "serviceType": service_type,
            "name": f"{base_title}{clean_area}",
            "nodeId": node_id,
            "latitude": final_lat,
            "longitude": final_lng,
            "distanceMeters": round(dist_m, 1),
            "distanceKm": round(dist_m / 1000.0, 2),
        })
    options.sort(key=lambda x: x["distanceMeters"])
    return options


def build_local_state_dict() -> dict:
    with db_lock:
        conn = sqlite3.connect(str(WEB_SYNC_DB_PATH))
        conn.row_factory = sqlite3.Row
        cur = conn.cursor()

        cur.execute("SELECT * FROM users ORDER BY created_at DESC")
        user_rows = cur.fetchall()
        users_list = []
        id_to_dpi = {}
        for r in user_rows:
            id_to_dpi[r["id"]] = r["dpi"]
            users_list.append({
                "id": r["id"],
                "fullName": r["full_name"],
                "dpi": r["dpi"],
                "password": r["password"],
                "phones": r["phones"],
                "address": r["address"],
                "nip": r["nip"] or "",
                "role": r["role"],
                "serviceType": r["service_type"],
                "approvalStatus": r["approval_status"],
                "stationNodeId": r["station_node_id"],
                "stationLat": r["station_lat"],
                "stationLng": r["station_lng"],
                "createdAt": r["created_at"],
            })

        cur.execute("SELECT * FROM emergency_orders ORDER BY assigned_at DESC LIMIT 20")
        order_rows = cur.fetchall()
        orders_list = []
        for o in order_rows:
            order_obj = {
                "id": o["id"],
                "assignedAgentId": o["assigned_agent_id"],
                "assignedAgentDpi": o["assigned_agent_dpi"] or id_to_dpi.get(o["assigned_agent_id"], ""),
                "dispatcherAdminId": o["dispatcher_admin_id"],
                "originNodeId": o["origin_node_id"],
                "originLat": o["origin_lat"],
                "originLng": o["origin_lng"],
                "destinationNodeId": o["destination_node_id"],
                "destinationLat": o["destination_lat"],
                "destinationLng": o["destination_lng"],
                "description": o["description"] or "",
                "status": o["status"],
                "assignedAt": o["assigned_at"],
                "recalculationCount": o["recalculation_count"],
            }
            if o["completed_at"] is not None:
                order_obj["completedAt"] = o["completed_at"]
            orders_list.append(order_obj)

        conn.close()
        return {
            "schema": 3,
            "timestamp": int(time.time() * 1000),
            "users": users_list,
            "orders": orders_list,
        }


def status_priority(status: str) -> int:
    if status in ("COMPLETED", "CANCELLED"):
        return 3
    if status == "IN_PROGRESS":
        return 2
    return 1


def merge_remote_state_dict(data: dict) -> bool:
    if not isinstance(data, dict):
        return False
    any_change = False
    with db_lock:
        conn = sqlite3.connect(str(WEB_SYNC_DB_PATH))
        conn.row_factory = sqlite3.Row
        cur = conn.cursor()

        for u in data.get("users", []):
            dpi = str(u.get("dpi", "")).strip().replace(" ", "")
            if not dpi:
                continue
            full_name = str(u.get("fullName", f"Usuario {dpi}")).strip()
            password = str(u.get("password", "1234")).strip() or "1234"
            phones = str(u.get("phones", "5500-0000")).strip()
            address = str(u.get("address", "Estacion Base")).strip()
            nip = str(u.get("nip", "")).strip() or None
            role = str(u.get("role", "AGENTE")).strip()
            service_type = str(u.get("serviceType", "POLICIA")).strip()
            approval_status = str(u.get("approvalStatus", "APPROVED")).strip()
            station_node_id = int(u.get("stationNodeId", DEFAULT_STATION_NODE_ID))
            station_lat = float(u.get("stationLat", DEFAULT_STATION_LAT))
            station_lng = float(u.get("stationLng", DEFAULT_STATION_LNG))
            created_at = int(u.get("createdAt", int(time.time() * 1000)))

            cur.execute("SELECT * FROM users WHERE dpi = ?", (dpi,))
            existing = cur.fetchone()
            if existing is None:
                cur.execute(
                    """
                    INSERT INTO users (
                        full_name, dpi, password, phones, address, nip, role,
                        service_type, approval_status, station_node_id, station_lat, station_lng, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        full_name, dpi, password, phones, address, nip, role,
                        service_type, approval_status, station_node_id, station_lat, station_lng, created_at
                    ),
                )
                any_change = True
            else:
                if (
                    existing["full_name"] != full_name
                    or existing["password"] != password
                    or existing["role"] != role
                    or existing["service_type"] != service_type
                    or abs(existing["station_lat"] - station_lat) > 1e-6
                    or abs(existing["station_lng"] - station_lng) > 1e-6
                ):
                    cur.execute(
                        """
                        UPDATE users
                        SET full_name = ?, password = ?, phones = ?, address = ?, nip = ?,
                            role = ?, service_type = ?, approval_status = ?,
                            station_node_id = ?, station_lat = ?, station_lng = ?
                        WHERE dpi = ?
                        """,
                        (
                            full_name, password, phones, address, nip,
                            role, service_type, approval_status,
                            station_node_id, station_lat, station_lng, dpi
                        ),
                    )
                    any_change = True

        for o in data.get("orders", []):
            assigned_at = int(o.get("assignedAt", 0))
            if assigned_at <= 0:
                continue
            agent_dpi = str(o.get("assignedAgentDpi", "")).strip().replace(" ", "")
            resolved_agent_id = int(o.get("assignedAgentId", 1))
            if agent_dpi:
                cur.execute("SELECT id FROM users WHERE dpi = ?", (agent_dpi,))
                u_row = cur.fetchone()
                if u_row:
                    resolved_agent_id = int(u_row["id"])

            incoming_status = str(o.get("status", "ASSIGNED")).strip()
            completed_at = o.get("completedAt", None)
            recalc = int(o.get("recalculationCount", 0))

            cur.execute("SELECT * FROM emergency_orders WHERE assigned_at = ?", (assigned_at,))
            existing_order = cur.fetchone()
            if existing_order is None:
                cur.execute(
                    """
                    INSERT INTO emergency_orders (
                        assigned_agent_id, assigned_agent_dpi, dispatcher_admin_id,
                        origin_node_id, origin_lat, origin_lng,
                        destination_node_id, destination_lat, destination_lng,
                        description, status, assigned_at, completed_at, recalculation_count
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        resolved_agent_id,
                        agent_dpi,
                        int(o.get("dispatcherAdminId", 1)),
                        int(o.get("originNodeId", DEFAULT_STATION_NODE_ID)),
                        float(o.get("originLat", DEFAULT_STATION_LAT)),
                        float(o.get("originLng", DEFAULT_STATION_LNG)),
                        int(o.get("destinationNodeId", DEFAULT_STATION_NODE_ID)),
                        float(o.get("destinationLat", 14.6195)),
                        float(o.get("destinationLng", -90.5153)),
                        str(o.get("description", "Emergencia SOS")),
                        incoming_status,
                        assigned_at,
                        completed_at,
                        recalc,
                    ),
                )
                any_change = True
            else:
                if (
                    status_priority(incoming_status) > status_priority(existing_order["status"])
                    or recalc > existing_order["recalculation_count"]
                ):
                    merged_status = (
                        incoming_status
                        if status_priority(incoming_status) > status_priority(existing_order["status"])
                        else existing_order["status"]
                    )
                    merged_completed = completed_at if completed_at is not None else existing_order["completed_at"]
                    merged_recalc = max(recalc, int(existing_order["recalculation_count"]))
                    cur.execute(
                        """
                        UPDATE emergency_orders
                        SET status = ?, completed_at = ?, recalculation_count = ?
                        WHERE id = ?
                        """,
                        (merged_status, merged_completed, merged_recalc, existing_order["id"]),
                    )
                    any_change = True

        enforce_single_active_order_per_agent(cur)
        conn.commit()
        conn.close()
    return any_change


def setup_adb_usb_bridges():
    global adb_bridge_active
    try:
        res1 = subprocess.run(
            ["adb", "reverse", f"tcp:{ADB_REVERSE_PORT}", f"tcp:{WEB_HTTP_PORT}"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=2.0,
        )
        res2 = subprocess.run(
            ["adb", "forward", f"tcp:{ADB_FORWARD_PORT}", f"tcp:{P2P_HTTP_PORT}"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=2.0,
        )
        adb_bridge_active = (res1.returncode == 0 or res2.returncode == 0)
    except Exception:
        adb_bridge_active = False


def get_peer_sync_urls() -> list:
    urls = [f"http://127.0.0.1:{ADB_FORWARD_PORT}/sync"]
    for ip in list(discovered_peer_ips):
        urls.append(f"http://{ip}:{P2P_HTTP_PORT}/sync")
    return urls


def push_and_pull_sync_cycle(force_push: bool = False):
    global last_published_hash
    # 1. Pull desde telefonos por USB ADB y Wi-Fi LAN
    for url in get_peer_sync_urls():
        try:
            req = urllib.request.Request(url, method="GET")
            with urllib.request.urlopen(req, timeout=1.0) as resp:
                if resp.status == 200:
                    body = resp.read().decode("utf-8", errors="ignore")
                    merge_remote_state_dict(json.loads(body))
        except Exception:
            pass

    # 2. Pull desde el relay en la nube HTTPS
    try:
        req = urllib.request.Request(f"{CLOUD_RELAY_URL}/json?poll=1&since=20m", method="GET")
        with urllib.request.urlopen(req, timeout=2.0) as resp:
            if resp.status == 200:
                lines = resp.read().decode("utf-8", errors="ignore").splitlines()
                for line in lines:
                    if not line.strip():
                        continue
                    wrapper = json.loads(line)
                    msg_id = wrapper.get("id", "")
                    if wrapper.get("event") == "message" and msg_id and msg_id not in processed_cloud_msg_ids:
                        processed_cloud_msg_ids.add(msg_id)
                        embedded = wrapper.get("message", "")
                        if embedded.startswith("{"):
                            merge_remote_state_dict(json.loads(embedded))
    except Exception:
        pass

    # 3. Push si cambio el estado o si force_push es True
    state_dict = build_local_state_dict()
    state_json = json.dumps(state_dict, ensure_ascii=False)
    curr_hash = str(hash(state_json))

    if force_push or curr_hash != last_published_hash:
        payload_bytes = state_json.encode("utf-8")
        pushed_any = False
        for url in get_peer_sync_urls():
            try:
                req = urllib.request.Request(
                    url,
                    data=payload_bytes,
                    headers={"Content-Type": "application/json; charset=utf-8"},
                    method="POST",
                )
                with urllib.request.urlopen(req, timeout=1.0) as resp:
                    if 200 <= resp.status < 300:
                        pushed_any = True
            except Exception:
                pass

        try:
            req = urllib.request.Request(
                CLOUD_RELAY_URL,
                data=payload_bytes,
                headers={"Content-Type": "text/plain; charset=utf-8"},
                method="POST",
            )
            with urllib.request.urlopen(req, timeout=2.0) as resp:
                if 200 <= resp.status < 300:
                    pushed_any = True
        except Exception:
            pass

        if pushed_any:
            last_published_hash = curr_hash


def udp_discovery_worker():
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.bind(("", P2P_UDP_PORT))
        sock.settimeout(1.5)
    except Exception:
        return

    my_ip = get_local_lan_ip()
    while True:
        try:
            data, addr = sock.recvfrom(512)
            msg = data.decode("utf-8", errors="ignore")
            peer_ip = addr[0]
            if msg.startswith(UDP_BEACON_PREFIX) and peer_ip not in ("127.0.0.1", my_ip):
                discovered_peer_ips.add(peer_ip)
        except socket.timeout:
            pass
        except Exception:
            pass

        try:
            my_ip = get_local_lan_ip()
            beacon = f"{UDP_BEACON_PREFIX}{my_ip}".encode("utf-8")
            sock.sendto(beacon, ("255.255.255.255", P2P_UDP_PORT))
            parts = my_ip.split(".")
            if len(parts) == 4:
                sock.sendto(beacon, (f"{parts[0]}.{parts[1]}.{parts[2]}.255", P2P_UDP_PORT))
        except Exception:
            pass


def background_sync_loop():
    tick = 0
    while True:
        try:
            if tick % 3 == 0:
                setup_adb_usb_bridges()
            push_and_pull_sync_cycle(force_push=False)
        except Exception:
            pass
        tick += 1
        time.sleep(1.5)


class ThreadedHTTPServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


class GeoRutaWebHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format_str, *args):
        pass

    def _send_json(self, payload: dict, status_code: int = 200):
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(raw)

    def _read_json_body(self) -> dict:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0:
            return {}
        raw = self.rfile.read(length).decode("utf-8", errors="ignore")
        return json.loads(raw) if raw.strip() else {}

    def _record_client_ip(self):
        client_ip = self.client_address[0]
        my_ip = get_local_lan_ip()
        if client_ip and client_ip not in ("127.0.0.1", "::1", my_ip):
            discovered_peer_ips.add(client_ip)

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_GET(self):
        self._record_client_ip()
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        qs = urllib.parse.parse_qs(parsed.query)

        if path in ("/", "/index.html"):
            if INDEX_HTML_PATH.exists():
                content = INDEX_HTML_PATH.read_bytes()
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(content)))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(content)
            else:
                self.send_error(404, "index.html no encontrado")
            return

        if path == "/sync":
            self._send_json(build_local_state_dict())
            return

        if path == "/api/state":
            state = build_local_state_dict()
            state["lanIp"] = get_local_lan_ip()
            state["webPort"] = WEB_HTTP_PORT
            state["p2pPort"] = P2P_HTTP_PORT
            state["adbBridgeActive"] = adb_bridge_active
            state["discoveredPeers"] = sorted(list(discovered_peer_ips))
            self._send_json(state)
            return

        if path == "/api/nearby_stations":
            service = qs.get("service", ["POLICIA"])[0].upper()
            lat = float(qs.get("lat", [str(DEFAULT_STATION_LAT)])[0])
            lng = float(qs.get("lng", [str(DEFAULT_STATION_LNG)])[0])
            area = qs.get("area", [""])[0]
            stations = get_dynamic_stations_near_applicant(service, lat, lng, area)
            self._send_json({"ok": True, "stations": stations})
            return

        if path == "/api/route":
            olat = float(qs.get("originLat", [str(DEFAULT_STATION_LAT)])[0])
            olng = float(qs.get("originLng", [str(DEFAULT_STATION_LNG)])[0])
            dlat = float(qs.get("destLat", ["14.6195"])[0])
            dlng = float(qs.get("destLng", ["-90.5153"])[0])
            poly = compute_dijkstra_route(olat, olng, dlat, dlng)
            self._send_json({"ok": True, "polyline": poly})
            return

        if path == "/api/search_dpi":
            dpi = qs.get("dpi", [""])[0].strip().replace(" ", "")
            push_and_pull_sync_cycle(force_push=False)
            with db_lock:
                conn = sqlite3.connect(str(WEB_SYNC_DB_PATH))
                conn.row_factory = sqlite3.Row
                cur = conn.cursor()
                cur.execute("SELECT * FROM users WHERE dpi = ?", (dpi,))
                u = cur.fetchone()
                if not u:
                    conn.close()
                    self._send_json({"ok": False, "error": f"No se encontro ningun agente con el DPI: {dpi}"})
                    return

                cur.execute(
                    """
                    SELECT * FROM emergency_orders
                    WHERE assigned_agent_dpi = ? OR assigned_agent_id = ?
                    ORDER BY assigned_at DESC
                    """,
                    (dpi, u["id"]),
                )
                orders_rows = cur.fetchall()
                conn.close()

            user_obj = {
                "id": u["id"],
                "fullName": u["full_name"],
                "dpi": u["dpi"],
                "phones": u["phones"],
                "address": u["address"],
                "nip": u["nip"] or "N/A",
                "role": u["role"],
                "serviceType": u["service_type"],
                "approvalStatus": u["approval_status"],
                "stationNodeId": u["station_node_id"],
                "stationLat": u["station_lat"],
                "stationLng": u["station_lng"],
            }
            orders_list = []
            for o in orders_rows:
                orders_list.append({
                    "id": o["id"],
                    "status": o["status"],
                    "description": o["description"] or "Emergencia SOS",
                    "originLat": o["origin_lat"],
                    "originLng": o["origin_lng"],
                    "destinationLat": o["destination_lat"],
                    "destinationLng": o["destination_lng"],
                    "assignedAt": o["assigned_at"],
                    "completedAt": o["completed_at"],
                    "recalculationCount": o["recalculation_count"],
                })
            self._send_json({"ok": True, "agent": user_obj, "orders": orders_list})
            return

        self.send_error(404, "Ruta no encontrada")

    def do_POST(self):
        self._record_client_ip()
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        if path == "/sync":
            body = self._read_json_body()
            merge_remote_state_dict(body)
            self._send_json(build_local_state_dict())
            return

        if path == "/api/register":
            body = self._read_json_body()
            full_name = str(body.get("fullName", "")).strip()
            dpi = str(body.get("dpi", "")).strip().replace(" ", "")
            password = str(body.get("password", "")).strip() or "1234"
            phones = str(body.get("phones", "")).strip() or "5500-0000"
            role = str(body.get("role", "AGENTE")).strip().upper()
            service_type = str(body.get("serviceType", "POLICIA")).strip().upper()
            nip = str(body.get("nip", "")).strip() or None
            address = str(body.get("address", "")).strip()
            station_lat = float(body.get("stationLat", DEFAULT_STATION_LAT))
            station_lng = float(body.get("stationLng", DEFAULT_STATION_LNG))
            station_node_id = int(body.get("stationNodeId", 0))

            if not full_name:
                self._send_json({"ok": False, "error": "El nombre completo es obligatorio."}, 400)
                return
            if not dpi:
                self._send_json({"ok": False, "error": "El numero de DPI es obligatorio."}, 400)
                return

            if role == "ADMINISTRADOR":
                service_type = "ADMINISTRACION"
                if not address:
                    address = "Centro de Comando y Despacho PC"

            if station_node_id <= 0:
                snapped = find_nearest_graph_node(station_lat, station_lng, 0.02)
                station_node_id = snapped["nodeId"]

            now_ms = int(time.time() * 1000)
            with db_lock:
                conn = sqlite3.connect(str(WEB_SYNC_DB_PATH))
                conn.row_factory = sqlite3.Row
                cur = conn.cursor()
                cur.execute("SELECT id FROM users WHERE dpi = ?", (dpi,))
                existing = cur.fetchone()
                if existing:
                    cur.execute(
                        """
                        UPDATE users
                        SET full_name = ?, password = ?, phones = ?, address = ?, nip = ?,
                            role = ?, service_type = ?, approval_status = 'APPROVED',
                            station_node_id = ?, station_lat = ?, station_lng = ?
                        WHERE dpi = ?
                        """,
                        (
                            full_name, password, phones, address, nip,
                            role, service_type, station_node_id, station_lat, station_lng, dpi
                        ),
                    )
                else:
                    cur.execute(
                        """
                        INSERT INTO users (
                            full_name, dpi, password, phones, address, nip, role,
                            service_type, approval_status, station_node_id, station_lat, station_lng, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'APPROVED', ?, ?, ?, ?)
                        """,
                        (
                            full_name, dpi, password, phones, address, nip, role,
                            service_type, station_node_id, station_lat, station_lng, now_ms
                        ),
                    )
                conn.commit()
                cur.execute("SELECT * FROM users WHERE dpi = ?", (dpi,))
                saved = cur.fetchone()
                conn.close()

            threading.Thread(target=push_and_pull_sync_cycle, args=(True,), daemon=True).start()

            user_payload = {
                "id": saved["id"],
                "fullName": saved["full_name"],
                "dpi": saved["dpi"],
                "password": saved["password"],
                "phones": saved["phones"],
                "address": saved["address"],
                "nip": saved["nip"] or "",
                "role": saved["role"],
                "serviceType": saved["service_type"],
                "approvalStatus": saved["approval_status"],
                "stationNodeId": saved["station_node_id"],
                "stationLat": saved["station_lat"],
                "stationLng": saved["station_lng"],
            }
            self._send_json({"ok": True, "user": user_payload})
            return

        if path == "/api/login":
            body = self._read_json_body()
            dpi = str(body.get("dpi", "")).strip().replace(" ", "")
            password = str(body.get("password", "")).strip()
            push_and_pull_sync_cycle(force_push=False)

            with db_lock:
                conn = sqlite3.connect(str(WEB_SYNC_DB_PATH))
                conn.row_factory = sqlite3.Row
                cur = conn.cursor()
                cur.execute("SELECT * FROM users WHERE dpi = ?", (dpi,))
                u = cur.fetchone()
                conn.close()

            if not u:
                self._send_json(
                    {"ok": False, "error": f"No existe ningun usuario con el DPI '{dpi}'. Verifique el DPI o creelo primero."},
                    404,
                )
                return
            if u["password"] != password:
                self._send_json({"ok": False, "error": "Clave incorrecta para el DPI ingresado."}, 401)
                return

            user_payload = {
                "id": u["id"],
                "fullName": u["full_name"],
                "dpi": u["dpi"],
                "password": u["password"],
                "phones": u["phones"],
                "address": u["address"],
                "nip": u["nip"] or "",
                "role": u["role"],
                "serviceType": u["service_type"],
                "approvalStatus": u["approval_status"],
                "stationNodeId": u["station_node_id"],
                "stationLat": u["station_lat"],
                "stationLng": u["station_lng"],
            }
            self._send_json({"ok": True, "user": user_payload})
            return

        if path == "/api/dispatch":
            body = self._read_json_body()
            services = body.get("services", [])
            dest_lat = float(body.get("destinationLat", 14.6195))
            dest_lng = float(body.get("destinationLng", -90.5153))
            description = str(body.get("description", "Emergencia SOS")).strip() or "Emergencia SOS"
            admin_id = int(body.get("adminId", 1))

            if not services:
                self._send_json(
                    {"ok": False, "error": "Seleccione al menos un servicio (Policia, Hospital o Bomberos)."},
                    400,
                )
                return

            push_and_pull_sync_cycle(force_push=False)
            dest_node = find_nearest_graph_node(dest_lat, dest_lng, 0.03)
            dispatched = []
            missing_services = []
            busy_services = []

            with db_lock:
                conn = sqlite3.connect(str(WEB_SYNC_DB_PATH))
                conn.row_factory = sqlite3.Row
                cur = conn.cursor()

                base_ts = int(time.time() * 1000)
                for idx, svc in enumerate(services):
                    svc_upper = str(svc).strip().upper()
                    cur.execute(
                        "SELECT * FROM users WHERE role = 'AGENTE' AND service_type = ?",
                        (svc_upper,),
                    )
                    candidates = cur.fetchall()
                    if not candidates:
                        missing_services.append(svc_upper)
                        continue

                    # Filtrar agentes que ya tienen una orden activa (ASSIGNED o IN_PROGRESS)
                    free_candidates = []
                    for ag in candidates:
                        cur.execute(
                            """
                            SELECT id FROM emergency_orders
                            WHERE (assigned_agent_dpi = ? OR assigned_agent_id = ?)
                              AND status IN ('ASSIGNED', 'IN_PROGRESS')
                            LIMIT 1
                            """,
                            (ag["dpi"], ag["id"]),
                        )
                        if cur.fetchone() is None:
                            free_candidates.append(ag)

                    if not free_candidates:
                        busy_services.append(svc_upper)
                        continue

                    nearest_agent = min(
                        free_candidates,
                        key=lambda ag: haversine_meters(ag["station_lat"], ag["station_lng"], dest_lat, dest_lng),
                    )
                    dist_m = haversine_meters(
                        nearest_agent["station_lat"], nearest_agent["station_lng"], dest_lat, dest_lng
                    )
                    assigned_at = base_ts + idx
                    full_desc = f"[{svc_upper}] {description}"

                    cur.execute(
                        """
                        INSERT INTO emergency_orders (
                            assigned_agent_id, assigned_agent_dpi, dispatcher_admin_id,
                            origin_node_id, origin_lat, origin_lng,
                            destination_node_id, destination_lat, destination_lng,
                            description, status, assigned_at, completed_at, recalculation_count
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ASSIGNED', ?, NULL, 0)
                        """,
                        (
                            nearest_agent["id"],
                            nearest_agent["dpi"],
                            admin_id if admin_id > 0 else 1,
                            nearest_agent["station_node_id"],
                            nearest_agent["station_lat"],
                            nearest_agent["station_lng"],
                            dest_node["nodeId"],
                            dest_lat,
                            dest_lng,
                            full_desc,
                            assigned_at,
                        ),
                    )
                    order_id = cur.lastrowid
                    polyline = compute_dijkstra_route(
                        nearest_agent["station_lat"],
                        nearest_agent["station_lng"],
                        dest_lat,
                        dest_lng,
                    )
                    dispatched.append({
                        "orderId": order_id,
                        "serviceType": svc_upper,
                        "agentName": nearest_agent["full_name"],
                        "agentDpi": nearest_agent["dpi"],
                        "stationName": nearest_agent["address"],
                        "originLat": nearest_agent["station_lat"],
                        "originLng": nearest_agent["station_lng"],
                        "destinationLat": dest_lat,
                        "destinationLng": dest_lng,
                        "distanceKm": round(dist_m / 1000.0, 2),
                        "polyline": polyline,
                    })

                conn.commit()
                conn.close()

            if not dispatched:
                if busy_services:
                    self._send_json(
                        {
                            "ok": False,
                            "error": (
                                "VALIDACION DE DESPACHO: No se puede asignar una segunda ruta a ["
                                + ", ".join(busy_services)
                                + "] porque todas sus unidades activas ya tienen una mision en curso. "
                                + "El agente debe finalizar su mision actual desde la App Movil antes de recibir otro despacho."
                            ),
                        },
                        400,
                    )
                    return
                self._send_json(
                    {
                        "ok": False,
                        "error": (
                            "No hay unidades/agentes registrados para los servicios seleccionados ("
                            + ", ".join(missing_services)
                            + "). Cree primero un agente de ese servicio en la App Movil o en la pestaña Crear Usuario."
                        ),
                    },
                    400,
                )
                return

            threading.Thread(target=push_and_pull_sync_cycle, args=(True,), daemon=True).start()
            self._send_json({
                "ok": True,
                "dispatched": dispatched,
                "missingServices": missing_services,
                "busyServices": busy_services,
            })
            return

        if path == "/api/complete_order":
            body = self._read_json_body()
            caller_role = str(body.get("callerRole", "AGENTE")).strip().upper()
            if caller_role == "ADMINISTRADOR":
                self._send_json(
                    {
                        "ok": False,
                        "error": "Por seguridad operativa, un Administrador no puede finalizar misiones. Solo el Agente en campo puede completar la emergencia desde su vista de usuario.",
                    },
                    403,
                )
                return
            order_id = int(body.get("orderId", 0))
            agent_dpi = str(body.get("agentDpi", "")).strip()
            now_ms = int(time.time() * 1000)
            with db_lock:
                conn = sqlite3.connect(str(WEB_SYNC_DB_PATH))
                cur = conn.cursor()
                if not agent_dpi and order_id > 0:
                    cur.execute("SELECT assigned_agent_dpi FROM emergency_orders WHERE id = ?", (order_id,))
                    r = cur.fetchone()
                    if r:
                        agent_dpi = r[0]
                cur.execute(
                    "UPDATE emergency_orders SET status = 'COMPLETED', completed_at = ? WHERE id = ?",
                    (now_ms, order_id),
                )
                if agent_dpi:
                    cur.execute(
                        "UPDATE emergency_orders SET status = 'COMPLETED', completed_at = ? WHERE assigned_agent_dpi = ? AND status IN ('ASSIGNED', 'IN_PROGRESS')",
                        (now_ms, agent_dpi),
                    )
                conn.commit()
                conn.close()
            threading.Thread(target=push_and_pull_sync_cycle, args=(True,), daemon=True).start()
            self._send_json({"ok": True})
            return

        if path == "/api/delete_user":
            body = self._read_json_body()
            dpi = str(body.get("dpi", "")).strip()
            with db_lock:
                conn = sqlite3.connect(str(WEB_SYNC_DB_PATH))
                cur = conn.cursor()
                cur.execute("DELETE FROM emergency_orders WHERE assigned_agent_dpi = ?", (dpi,))
                cur.execute("DELETE FROM users WHERE dpi = ?", (dpi,))
                conn.commit()
                conn.close()
            self._send_json({"ok": True})
            return

        if path == "/api/clear_users":
            with db_lock:
                conn = sqlite3.connect(str(WEB_SYNC_DB_PATH))
                cur = conn.cursor()
                cur.execute("DELETE FROM emergency_orders")
                cur.execute("DELETE FROM users")
                conn.commit()
                conn.close()
            self._send_json({"ok": True})
            return

        if path == "/api/add_peer":
            body = self._read_json_body()
            ip = str(body.get("ip", "")).strip()
            if ip:
                discovered_peer_ips.add(ip)
                threading.Thread(target=push_and_pull_sync_cycle, args=(True,), daemon=True).start()
            self._send_json({"ok": True, "discoveredPeers": sorted(list(discovered_peer_ips))})
            return

        self.send_error(404, "Endpoint POST no encontrado")


def main():
    init_web_sync_db()
    setup_adb_usb_bridges()

    threading.Thread(target=udp_discovery_worker, daemon=True).start()
    threading.Thread(target=background_sync_loop, daemon=True).start()

    # Servidor P2P en puerto 45891 para que los telefonos Android en la misma red sincronizen directo
    try:
        p2p_server = ThreadedHTTPServer(("0.0.0.0", P2P_HTTP_PORT), GeoRutaWebHandler)
        threading.Thread(target=p2p_server.serve_forever, daemon=True).start()
    except Exception as e:
        print(f"[Aviso] Puerto P2P {P2P_HTTP_PORT} en uso o no disponible: {e}")

    web_server = ThreadedHTTPServer(("0.0.0.0", WEB_HTTP_PORT), GeoRutaWebHandler)
    lan_ip = get_local_lan_ip()
    print("=" * 72)
    print("GEORUTA SOS - APLICACION WEB DE ESCRITORIO Y CENTRO DE DESPACHO ACTIVO")
    print(f"-> Abrir en navegador de PC: http://localhost:{WEB_HTTP_PORT}")
    print(f"-> Direccion en Red Local:   http://{lan_ip}:{WEB_HTTP_PORT}")
    print("=" * 72)
    web_server.serve_forever()


if __name__ == "__main__":
    main()
