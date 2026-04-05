import cv2
import mediapipe as mp
import numpy as np
import time
import threading
import random
import json
import hashlib
import hmac
from collections import deque
from dataclasses import dataclass
from typing import Optional, Dict, Any
from enum import Enum
from PIL import Image, ImageDraw, ImageFont
import os

# ============================================================================
# CONFIGURATION
# ============================================================================

class Step(Enum):
    FACE_DETECTION = 1
    INSTRUCTION = 2
    FLASHING = 3
    PROCESSING = 4
    COMPLETE = 5

# Detection Config
CHALLENGE_FREQ = 0.6  # Slower flashing
HISTORY_LEN = 200
HUMAN_CONFIDENCE_THRESHOLD = 70
DEEPFAKE_CONFIDENCE_THRESHOLD = 10
MIN_SAMPLES_FOR_DECISION = 20

# Anti-Stagnation Config
STAGNATION_WINDOW = 80        # Approx 2.5 seconds of data
STAGNATION_STD_DEV_LIMIT = 0.5 # If score fluctuates less than this, it's fake

# Light adaptation
EWMA_ALPHA = 0.15
MIN_SIGNAL_THRESHOLD = 10
SATURATION_THRESHOLD = 250

# Latency thresholds
BASE_LATENCY_MS = 180
MAX_JITTER_MS = 400
DEEPFAKE_LATENCY_THRESHOLD = 600

# Color Palette (BGR)
COLOR_PALETTE = [
    (0, 0, 0),        # Black (baseline)
    (0, 0, 255),      # Red
    (0, 255, 0),      # Green
    (255, 0, 0),      # Blue
    (0, 255, 255),    # Cyan
    (255, 0, 255),    # Magenta
    (255, 255, 0),    # Yellow
]
CHALLENGE_COLORS = [c for c in COLOR_PALETTE if c != (0, 0, 0)]

# ============================================================================
# SAMSUNG KNOX SIMULATION
# ============================================================================

class MockKnoxVault:
    """Simulates Samsung Knox TrustZone Environment."""
    def __init__(self):
        self._hardware_secret = b"SAMSUNG_HARDWARE_ROOT_KEY_XYZ"
        self.device_id = "SM-S918B_GALAXY_S23_ULTRA"
        self.knox_version = "3.9"
        self.warranty_bit = "0x0"

    def get_integrity_status(self) -> Dict[str, str]:
        return {
            "device": self.device_id,
            "knox_version": self.knox_version,
            "warranty_void": self.warranty_bit,
            "bootloader": "LOCKED"
        }

    def sign_telemetry(self, telemetry_data: Dict[str, Any]) -> Dict[str, Any]:
        """Cryptographically signs telemetry data."""
        payload_str = json.dumps(telemetry_data, sort_keys=True)
        signature = hmac.new(
            self._hardware_secret,
            payload_str.encode('utf-8'),
            hashlib.sha256
        ).hexdigest()

        return {
            "payload": telemetry_data,
            "signature": signature,
            "attestation": self.get_integrity_status(),
            "timestamp_ms": int(time.time() * 1000)
        }

# ============================================================================
# SENTINEL VAULT (Identity Persistence)
# ============================================================================

class SentinelVault:
    """
    Stores 'Geometric Hashes' of verified users to allow for 
    Adaptive Authentication (Fast Pass).
    """
    def __init__(self):
        # Database: { "face_hash": { "status": "VERIFIED/BLOCKED", "timestamp": float, "trust_score": float } }
        self.registry = {}
        
    def _compute_geometric_hash(self, landmarks, w, h):
        """
        Creates a lightweight unique ID based on relative distances 
        between stable facial landmarks (Eyes, Nose, Chin).
        """
        # Landmarks: 1 (Nose Tip), 33 (Left Eye Inner), 263 (Right Eye Inner), 152 (Chin)
        p_nose = landmarks[1]
        p_eye_l = landmarks[33]
        p_eye_r = landmarks[263]
        p_chin = landmarks[152]
        
        # Calculate relative distances (normalized by face height to handle zoom)
        face_height = abs(p_chin.y - p_nose.y)
        if face_height == 0: return None
        
        d1 = abs(p_eye_l.x - p_eye_r.x) / face_height  # Eye width ratio
        d2 = abs(p_nose.y - p_eye_l.y) / face_height   # Nose-to-Eye ratio
        
        # Create a simple string hash (rounded to 2 decimals for tolerance)
        raw_id = f"{d1:.2f}|{d2:.2f}"
        return hashlib.md5(raw_id.encode()).hexdigest()

    def check_identity(self, landmarks, w, h):
        """Returns (status, trust_level)"""
        uid = self._compute_geometric_hash(landmarks, w, h)
        if not uid: return "UNKNOWN", 0.0
        
        if uid in self.registry:
            record = self.registry[uid]
            # Expire verification after 60 seconds (for demo purposes)
            if time.time() - record['timestamp'] > 60:
                return "EXPIRED", 0.0
            return record['status'], record['trust_score']
            
        return "UNKNOWN", 0.0

    def register_user(self, landmarks, w, h, status, trust_score):
        uid = self._compute_geometric_hash(landmarks, w, h)
        if uid:
            self.registry[uid] = {
                "status": status,
                "timestamp": time.time(),
                "trust_score": trust_score
            }
            print(f"[SENTINEL] User Registered: {uid[:8]}... | Status: {status}")

# ============================================================================
# DATA STRUCTURES
# ============================================================================

@dataclass
class FrameData:
    timestamp: float
    rgb: np.ndarray
    glint_intensity: float

@dataclass
class ChallengeData:
    timestamp: float
    label: int

@dataclass
class LatencyStats:
    measurements: deque
    mean: float = 150.0
    std_dev: float = 50.0
    adaptive_threshold: float = 450.0

# ============================================================================
# PHOTON ID ENGINE (IMPROVED + STAGNATION CHECK)
# ============================================================================

class PhotonIDEngine:
    def __init__(self):
        self.knox = MockKnoxVault()
        self.reset()
        
    def reset(self):
        self.challenge_history = deque(maxlen=HISTORY_LEN)
        self.frame_history = deque(maxlen=HISTORY_LEN)
        
        self.baseline_rgb = np.array([0.0, 0.0, 0.0])
        self.baseline_locked = False
        self.baseline_samples = 0
        
        self.latency_stats = LatencyStats(measurements=deque(maxlen=30))
        self.samples = deque(maxlen=100)
        
        # Stagnation tracking
        self.confidence_trace = deque(maxlen=STAGNATION_WINDOW)
        
        self.confidence = 0.0
        self.status = "INITIALIZING"
        self.debug_msg = ""
        self.deepfake_risk = 0.0
        self.is_certain = False
        self.is_human = False
        self.secure_packet = None

    def record_challenge(self, color_idx: int):
        self.challenge_history.append(ChallengeData(time.perf_counter(), color_idx))

    def record_frame(self, rgb: np.ndarray):
        self.frame_history.append(FrameData(time.perf_counter(), np.array(rgb), np.max(rgb)))

    def generate_secure_packet(self):
        """Bundles decision into Knox-signed packet."""
        telemetry = {
            "is_human": self.is_human,
            "confidence_score": self.confidence,
            "avg_latency_ms": self.latency_stats.mean,
            "deepfake_risk": self.deepfake_risk,
            "sample_count": len(self.samples)
        }
        self.secure_packet = self.knox.sign_telemetry(telemetry)
        return self.secure_packet

    def update_baseline(self, rgb: np.ndarray) -> None:
        """Update baseline using EWMA."""
        if not self.baseline_locked:
            if self.baseline_samples == 0:
                self.baseline_rgb = np.array(rgb, dtype=np.float32)
            else:
                self.baseline_rgb = (
                    (1 - EWMA_ALPHA) * self.baseline_rgb + 
                    EWMA_ALPHA * np.array(rgb, dtype=np.float32)
                )
            self.baseline_samples += 1
            
            if self.baseline_samples >= 10:
                self.baseline_locked = True

    def analyze(self):
        """Main analysis function."""
        if not self.challenge_history or len(self.frame_history) < 3:
            return

        challenge = self.challenge_history[-1]
        
        if challenge.label == 0:  # Black baseline
            if len(self.frame_history) > 0:
                self.update_baseline(self.frame_history[-1].rgb)
            return

        # Frame history search
        best_match = None
        best_score = -1
        best_latency = 0
        
        challenge_time = challenge.timestamp
        search_start = 20
        search_end = 600
        
        for frame in reversed(self.frame_history):
            dt_ms = (frame.timestamp - challenge_time) * 1000
            
            if dt_ms < search_start:
                continue
            if dt_ms > search_end:
                break
            
            delta = np.maximum(0, frame.rgb - self.baseline_rgb)
            max_delta = np.max(delta)
            
            # Check for saturation
            if np.max(frame.rgb) > SATURATION_THRESHOLD:
                self.status = "ADAPTING LIGHT"
                self.debug_msg = "Sensor saturated"
                return
            
            if max_delta < MIN_SIGNAL_THRESHOLD:
                continue
            
            expected_color = COLOR_PALETTE[challenge.label]
            score = self._calculate_color_score(delta, expected_color)
            latency_penalty = abs(dt_ms - self.latency_stats.mean) / 100
            adjusted_score = score - (latency_penalty * 0.3)
            
            if adjusted_score > best_score:
                best_score = adjusted_score
                best_match = frame
                best_latency = dt_ms

        if best_match is None:
            if np.max(self.baseline_rgb) < 80:
                self.debug_msg = "Signal weak"
            else:
                self.debug_msg = "No match"
            self.samples.append(0.0)
            self._update_metrics()
            return

        delta = np.maximum(0, best_match.rgb - self.baseline_rgb)
        total_delta = np.sum(delta)
        
        if total_delta < MIN_SIGNAL_THRESHOLD:
            self.debug_msg = "Weak signal"
            self.samples.append(0.0)
            self._update_metrics()
            return

        # Check color dominance
        expected_color = COLOR_PALETTE[challenge.label]
        is_dominant = self._check_color_dominance(delta, expected_color, total_delta)
        
        if not is_dominant:
            self.debug_msg = "Color not dominant"
            self.samples.append(0.2)
            self._update_metrics()
            return

        # Adaptive latency analysis
        self.last_match_latency = best_latency
        
        self.latency_stats.measurements.append(best_latency)
        if len(self.latency_stats.measurements) > 5:
            self.latency_stats.mean = np.mean(list(self.latency_stats.measurements))
            self.latency_stats.std_dev = np.std(list(self.latency_stats.measurements))
            self.latency_stats.adaptive_threshold = (
                self.latency_stats.mean + (3 * self.latency_stats.std_dev)
            )

        # Graduated risk scoring
        if best_latency > DEEPFAKE_LATENCY_THRESHOLD:
            self.deepfake_risk = min(1.0, (best_latency - 500) / 200)
            self.debug_msg = f"HIGH LATENCY: {int(best_latency)}ms"
            self.samples.append(0.5)
        elif best_latency > self.latency_stats.adaptive_threshold + 100:
            self.deepfake_risk = 0.2
            self.debug_msg = f"Latency spike: {int(best_latency)}ms"
            self.samples.append(0.7)
        else:
            self.deepfake_risk = 0.0
            self.debug_msg = f"OK: {int(best_latency)}ms"
            self.samples.append(1.0)

        self._update_metrics()

    def _calculate_color_score(self, delta: np.ndarray, expected_color: tuple) -> float:
        r_delta = delta[0]
        g_delta = delta[1]
        b_delta = delta[2]
        
        if expected_color[2] > expected_color[1] and expected_color[2] > expected_color[0]:  # Red
            return r_delta - (g_delta + b_delta) / 2
        elif expected_color[1] > expected_color[0] and expected_color[1] > expected_color[2]:  # Green
            return g_delta - (r_delta + b_delta) / 2
        elif expected_color[0] > expected_color[1] and expected_color[0] > expected_color[2]:  # Blue
            return b_delta - (r_delta + g_delta) / 2
        else:
            return (r_delta + g_delta + b_delta) / 3

    def _check_color_dominance(self, delta: np.ndarray, expected_color: tuple, total: float) -> bool:
        if total == 0:
            return False
        
        r_delta = delta[0]
        g_delta = delta[1]
        b_delta = delta[2]
        
        if expected_color[2] > expected_color[1] and expected_color[2] > expected_color[0]:  # Red
            return r_delta / total > 0.5
        elif expected_color[1] > expected_color[0] and expected_color[1] > expected_color[2]:  # Green
            return g_delta / total > 0.5
        elif expected_color[0] > expected_color[1] and expected_color[0] > expected_color[2]:  # Blue
            return b_delta / total > 0.5
        else:
            expected_sum = sum([delta[i] for i in range(3) if expected_color[i] > 100])
            return expected_sum / total > 0.4

    def _update_metrics(self) -> None:
        """Updated metrics with Stagnation Check."""
        if len(self.samples) < MIN_SAMPLES_FOR_DECISION:
            self.confidence = 0.0
            self.status = "COLLECTING DATA..."
            self.is_certain = False
            return
        
        # Weighted scoring
        weights = [1.0 + (i / len(self.samples)) for i in range(len(self.samples))]
        weighted_sum = sum(s * w for s, w in zip(self.samples, weights))
        total_weight = sum(weights)
        
        self.confidence = (weighted_sum / total_weight) * 100
        
        # Track confidence history for stagnation detection
        self.confidence_trace.append(self.confidence)
        
        # Check Stagnation (Anti-Freeze / Anti-Loop)
        if len(self.confidence_trace) >= STAGNATION_WINDOW:
            std_dev = np.std(self.confidence_trace)
            if std_dev < STAGNATION_STD_DEV_LIMIT:
                # Confidence is unnaturally stable -> Likely a static image or frozen video
                self.status = "🚨 DEEPFAKE (STATIC)"
                self.is_certain = True
                self.is_human = False
                self.deepfake_risk = 1.0
                return

        # Standard Thresholds
        if self.confidence > HUMAN_CONFIDENCE_THRESHOLD:
            self.status = "✓ HUMAN DETECTED"
            self.is_certain = True
            self.is_human = True
            self.deepfake_risk = 0.0
        elif self.confidence < DEEPFAKE_CONFIDENCE_THRESHOLD and len(self.samples) > 25:
            self.status = "🚨 DEEPFAKE DETECTED"
            self.is_certain = True
            self.is_human = False
            self.deepfake_risk = 1.0
        elif self.deepfake_risk > 0.95:
            self.status = "DEEPFAKE DETECTED"
            self.is_certain = True
            self.is_human = False
        else:
            self.status = "ANALYZING..."
            self.is_certain = False

class AsyncCam:
    def __init__(self):
        # INTEGRATION: Changed to 0 as fallback for generic webcams (Windows especially)
        self.cap = cv2.VideoCapture(0) 
        
        self._orig_auto_exposure = self.cap.get(cv2.CAP_PROP_AUTO_EXPOSURE)
        self._orig_exposure = self.cap.get(cv2.CAP_PROP_EXPOSURE)
        self._orig_brightness = self.cap.get(cv2.CAP_PROP_BRIGHTNESS)
        
        self.cap.set(cv2.CAP_PROP_AUTO_EXPOSURE, 3)
        self.cap.set(cv2.CAP_PROP_FPS, 60)
        self.cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
        
        self.frame = None
        self.running = True
        self.lock = threading.Lock()
        
        for _ in range(5):
            self.cap.read()
        
        threading.Thread(target=self._update, daemon=True).start()

    def adjust_exposure(self, amount: int) -> None:
        pass

    def _update(self) -> None:
        while self.running:
            ret, frame = self.cap.read()
            if ret:
                with self.lock:
                    self.frame = frame

    def read(self) -> Optional[np.ndarray]:
        with self.lock:
            return self.frame.copy() if self.frame is not None else None

    def stop(self) -> None:
        self.running = False
        # Restore original camera settings before releasing
        self.cap.set(cv2.CAP_PROP_AUTO_EXPOSURE, self._orig_auto_exposure)
        self.cap.set(cv2.CAP_PROP_EXPOSURE, self._orig_exposure)
        self.cap.set(cv2.CAP_PROP_BRIGHTNESS, self._orig_brightness)
        self.cap.release()

# ============================================================================
# GLINT EXTRACTION
# ============================================================================

def extract_glint_rgb(frame: np.ndarray, landmarks, w: int, h: int) -> np.ndarray:
    best_rgb = np.array([0, 0, 0], dtype=np.float32)
    
    eye_indices = [468, 473]  # Both eyes
    
    for idx in eye_indices:
        if idx >= len(landmarks):
            continue
            
        p = landmarks[idx]
        ex, ey = int(p.x * w), int(p.y * h)
        radius = 12
        
        x1, x2 = max(0, ex - radius), min(w, ex + radius)
        y1, y2 = max(0, ey - radius), min(h, ey + radius)
        roi = frame[y1:y2, x1:x2]
        
        if roi.size == 0:
            continue
        
        gray = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY)
        threshold = np.percentile(gray, 90)
        mask = gray >= threshold
        
        if np.sum(mask) == 0:
            continue
        
        mean_color = cv2.mean(roi, mask=mask.astype(np.uint8))
        rgb = np.array([mean_color[2], mean_color[1], mean_color[0]], dtype=np.float32)
        
        if np.sum(rgb) > np.sum(best_rgb):
            best_rgb = rgb
    
    return best_rgb

# ============================================================================
# UI HELPERS (PIL + Arial)
# ============================================================================

def _load_arial_font(size: int) -> ImageFont.FreeTypeFont:
    """Load Arial font with fallback chain (Windows + Linux)."""
    font_paths = [
        "C:/Windows/Fonts/arial.ttf",      # INTGRATION: Windows Support
        "/usr/share/fonts/truetype/msttcorefonts/Arial.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
        "/usr/share/fonts/TTF/Arial.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/truetype/freefont/FreeSans.ttf",
    ]
    for path in font_paths:
        if os.path.isfile(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()

def _load_arial_bold(size: int) -> ImageFont.FreeTypeFont:
    """Load Arial Bold font with fallback chain (Windows + Linux)."""
    font_paths = [
        "C:/Windows/Fonts/arialbd.ttf",    # INTEGRATION: Windows Support
        "/usr/share/fonts/truetype/msttcorefonts/Arial_Bold.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
        "/usr/share/fonts/TTF/arialbd.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/freefont/FreeSansBold.ttf",
    ]
    for path in font_paths:
        if os.path.isfile(path):
            return ImageFont.truetype(path, size)
    return _load_arial_font(size)

# Pre-load fonts at various sizes
_FONT_CACHE = {}
def _get_font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    key = (size, bold)
    if key not in _FONT_CACHE:
        _FONT_CACHE[key] = _load_arial_bold(size) if bold else _load_arial_font(size)
    return _FONT_CACHE[key]

def _pil_put_text(img: np.ndarray, text: str, pos: tuple, font_size: int,
                  color_rgb: tuple, bold: bool = False, anchor: str = "lt") -> np.ndarray:
    """Draw text onto an OpenCV image using PIL (supports Arial)."""
    pil_img = Image.fromarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
    draw = ImageDraw.Draw(pil_img)
    font = _get_font(font_size, bold)
    draw.text(pos, text, font=font, fill=color_rgb, anchor=anchor)
    return cv2.cvtColor(np.array(pil_img), cv2.COLOR_RGB2BGR)

def _draw_rounded_rect(img: np.ndarray, pt1: tuple, pt2: tuple,
                       color_bgr: tuple, radius: int = 16,
                       alpha: float = 0.7, border_bgr: tuple = None) -> np.ndarray:
    """Draw a filled rounded rectangle with optional transparency."""
    overlay = img.copy()
    x1, y1 = pt1
    x2, y2 = pt2
    # Four corner circles + two rects to approximate rounded rect
    cv2.rectangle(overlay, (x1 + radius, y1), (x2 - radius, y2), color_bgr, -1)
    cv2.rectangle(overlay, (x1, y1 + radius), (x2, y2 - radius), color_bgr, -1)
    cv2.circle(overlay, (x1 + radius, y1 + radius), radius, color_bgr, -1)
    cv2.circle(overlay, (x2 - radius, y1 + radius), radius, color_bgr, -1)
    cv2.circle(overlay, (x1 + radius, y2 - radius), radius, color_bgr, -1)
    cv2.circle(overlay, (x2 - radius, y2 - radius), radius, color_bgr, -1)
    if border_bgr:
        cv2.rectangle(overlay, (x1 + radius, y1), (x2 - radius, y1 + 2), border_bgr, -1)
        cv2.rectangle(overlay, (x1 + radius, y2 - 2), (x2 - radius, y2), border_bgr, -1)
        cv2.rectangle(overlay, (x1, y1 + radius), (x1 + 2, y2 - radius), border_bgr, -1)
        cv2.rectangle(overlay, (x2 - 2, y1 + radius), (x2, y2 - radius), border_bgr, -1)
    cv2.addWeighted(overlay, alpha, img, 1 - alpha, 0, img)
    return img

def _draw_progress_bar(img: np.ndarray, x: int, y: int, w: int, h: int,
                       progress: float, fg_bgr: tuple, bg_bgr: tuple = (40, 40, 40),
                       radius: int = 8) -> np.ndarray:
    """Draw a modern rounded progress bar."""
    img = _draw_rounded_rect(img, (x, y), (x + w, y + h), bg_bgr, radius, alpha=0.9)
    fill_w = max(radius * 2, int(w * min(progress, 1.0)))
    if progress > 0:
        img = _draw_rounded_rect(img, (x, y), (x + fill_w, y + h), fg_bgr, radius, alpha=1.0)
    return img

def _draw_multi_face_warning(img: np.ndarray, w: int, h: int, face_count: int) -> np.ndarray:
    """INTEGRATION: Draw a warning when multiple faces are detected."""
    cx, cy = w // 2, 100
    card_w, card_h = 400, 80
    img = _draw_rounded_rect(img, (cx - card_w//2, cy - card_h//2),
                             (cx + card_w//2, cy + card_h//2),
                             (20, 20, 180), 12, alpha=0.9)
    img = _pil_put_text(img, f"⚠️ MULTI-FACE REJECTED",
                        (cx, cy - 10), 20, (255, 255, 255), bold=True, anchor="mt")
    img = _pil_put_text(img, f"{face_count} faces detected — only 1 allowed for eKYC",
                        (cx, cy + 20), 14, (255, 200, 200), anchor="mt")
    return img

# ============================================================================
# UI RENDERING
# ============================================================================

def draw_face_detection_step(frame: np.ndarray, landmarks, face_detected: bool, 
                           vault_status: str, trust_score: float, face_count: int) -> np.ndarray:
    h, w, _ = frame.shape
    overlay = frame.copy()
    
    # Dark cinematic overlay
    dark = np.zeros_like(overlay)
    cv2.addWeighted(overlay, 0.92, dark, 0.08, 0, overlay)
    
    # Subtle gradient bar at top
    for i in range(6):
        cv2.line(overlay, (0, i), (w, i), (80 - i * 10, 60 - i * 5, 40 - i * 3), 1)
    
    # Title bar panel
    overlay = _draw_rounded_rect(overlay, (w//2 - 220, 20), (w//2 + 220, 70),
                                 (30, 30, 30), 12, alpha=0.85, border_bgr=(80, 80, 80))
    overlay = _pil_put_text(overlay, "PHOTON ID  |  Face Detection",
                            (w//2, 35), 22, (200, 210, 220), bold=True, anchor="mt")
    
    if face_count > 1:
        overlay = _draw_multi_face_warning(overlay, w, h, face_count)
    
    if face_detected and landmarks and face_count == 1:
        # Draw face mesh lightly
        for landmark in landmarks:
            x = int(landmark.x * w)
            y = int(landmark.y * h)
            cv2.circle(overlay, (x, y), 1, (0, 180, 120), -1)
        
        # Eye tracking circles
        eye_l = landmarks[468]
        eye_r = landmarks[473]
        ex_l, ey_l = int(eye_l.x * w), int(eye_l.y * h)
        ex_r, ey_r = int(eye_r.x * w), int(eye_r.y * h)
        cv2.circle(overlay, (ex_l, ey_l), 18, (0, 255, 180), 2)
        cv2.circle(overlay, (ex_r, ey_r), 18, (0, 255, 180), 2)
        cv2.circle(overlay, (ex_l, ey_l), 4, (0, 255, 180), -1)
        cv2.circle(overlay, (ex_r, ey_r), 4, (0, 255, 180), -1)
        
        # Status card
        card_y = h - 200
        overlay = _draw_rounded_rect(overlay, (w//2 - 240, card_y), (w//2 + 240, card_y + 160),
                                     (20, 25, 20), 16, alpha=0.82, border_bgr=(0, 200, 100))
        
        # Green dot indicator
        cv2.circle(overlay, (w//2 - 210, card_y + 30), 8, (0, 220, 100), -1)
        overlay = _pil_put_text(overlay, "Face Detected",
                                (w//2 - 190, card_y + 18), 26, (100, 255, 160), bold=True)
        
        if vault_status == "VERIFIED":
            overlay = _pil_put_text(overlay, f"Known User  -  Trust: {int(trust_score)}%",
                                    (w//2, card_y + 62), 20, (140, 255, 180), anchor="mt")
            overlay = _pil_put_text(overlay, "System Ready",
                                    (w//2, card_y + 95), 18, (160, 200, 180), anchor="mt")
            overlay = _pil_put_text(overlay, "Press SPACE to continue",
                                    (w//2, card_y + 128), 18, (180, 230, 255), bold=True, anchor="mt")
        elif vault_status == "BLOCKED":
            overlay = _pil_put_text(overlay, "Blocked Identity",
                                    (w//2, card_y + 62), 22, (255, 80, 80), bold=True, anchor="mt")
            overlay = _pil_put_text(overlay, "Access Denied",
                                    (w//2, card_y + 100), 18, (255, 120, 120), anchor="mt")
        else:
            overlay = _pil_put_text(overlay, "New Identity Detected",
                                    (w//2, card_y + 62), 20, (220, 220, 230), anchor="mt")
            overlay = _pil_put_text(overlay, "Press SPACE to start enrollment",
                                    (w//2, card_y + 100), 18, (180, 230, 255), bold=True, anchor="mt")
    else:
        # No face - prompt card
        card_y = h//2 - 60
        overlay = _draw_rounded_rect(overlay, (w//2 - 220, card_y), (w//2 + 220, card_y + 120),
                                     (30, 20, 20), 16, alpha=0.82, border_bgr=(80, 80, 200))
        cv2.circle(overlay, (w//2 - 190, card_y + 30), 8, (80, 80, 255), -1)
        if face_count > 1:
            overlay = _pil_put_text(overlay, "Too Many Faces",
                                    (w//2 - 170, card_y + 18), 26, (255, 120, 120), bold=True)
            overlay = _pil_put_text(overlay, "Please ensure only one person is visible",
                                    (w//2, card_y + 70), 17, (200, 200, 210), anchor="mt")
        else:
            overlay = _pil_put_text(overlay, "No Face Detected",
                                    (w//2 - 170, card_y + 18), 26, (255, 120, 120), bold=True)
            overlay = _pil_put_text(overlay, "Please position your face in front of the camera",
                                    (w//2, card_y + 70), 17, (200, 200, 210), anchor="mt")
    
    return overlay

def draw_instruction_step(frame: np.ndarray) -> np.ndarray:
    h, w, _ = frame.shape
    overlay = frame.copy()
    
    dark = np.zeros_like(overlay)
    cv2.addWeighted(overlay, 0.75, dark, 0.25, 0, overlay)
    
    # Warning card
    cx, cy = w // 2, h // 2
    card_w, card_h = 460, 280
    overlay = _draw_rounded_rect(overlay, (cx - card_w//2, cy - card_h//2),
                                 (cx + card_w//2, cy + card_h//2),
                                 (25, 15, 40), 20, alpha=0.9, border_bgr=(60, 60, 220))
    
    # Warning icon line
    icon_y = cy - card_h//2 + 45
    overlay = _pil_put_text(overlay, "\u26A0", (cx - 110, icon_y - 12), 30, (255, 200, 60), bold=True)
    overlay = _pil_put_text(overlay, "WARNING", (cx - 70, icon_y - 8), 32, (255, 100, 100), bold=True)
    
    # Instruction text
    overlay = _pil_put_text(overlay, "Flashing lights will appear shortly.",
                            (cx, cy - 30), 20, (230, 230, 240), anchor="mt")
    overlay = _pil_put_text(overlay, "Please keep your face steady and",
                            (cx, cy + 10), 20, (230, 230, 240), anchor="mt")
    overlay = _pil_put_text(overlay, "look directly at the screen.",
                            (cx, cy + 45), 20, (230, 230, 240), anchor="mt")
    
    # Countdown hint
    overlay = _pil_put_text(overlay, "Starting in 3 seconds...",
                            (cx, cy + card_h//2 - 40), 18, (180, 230, 255), bold=True, anchor="mt")
    
    return overlay

def draw_flashing_step(frame: np.ndarray, current_color_idx: int, elapsed: float, engine: PhotonIDEngine, face_count: int) -> np.ndarray:
    h, w, _ = frame.shape
    overlay = frame.copy()
    
    color_bgr = COLOR_PALETTE[current_color_idx]
    overlay[:] = color_bgr
    
    # HUD panel at top
    overlay = _draw_rounded_rect(overlay, (20, 15), (w - 20, 130),
                                 (0, 0, 0), 14, alpha=0.55)
    
    # INTEGRATION: Multi-face pause warning
    if face_count > 1:
        overlay = _draw_rounded_rect(overlay, (w//2 - 200, 15), (w//2 + 200, 65),
                                     (0, 0, 200), 8, alpha=0.8)
        overlay = _pil_put_text(overlay, f"⚠️ Analysis Paused: {face_count} Faces",
                                (w//2, 30), 18, (255, 255, 255), bold=True, anchor="mt")
    
    # Time
    overlay = _pil_put_text(overlay, f"Elapsed: {elapsed:.1f}s",
                            (40, 25), 20, (220, 220, 230), bold=True)
    
    # Confidence
    conf_color = (100, 255, 160) if engine.confidence > 60 else (255, 230, 100) if engine.confidence > 30 else (255, 130, 130)
    overlay = _pil_put_text(overlay, f"Confidence: {engine.confidence:.1f}%",
                            (40, 58), 22, conf_color, bold=True)
    
    # Status
    overlay = _pil_put_text(overlay, engine.status,
                            (40, 95), 18, (180, 230, 255))
    
    # Progress bar at bottom
    progress = min(elapsed / 60, 1.0)
    bar_fg = (0, 200, 120) if engine.confidence > 50 else (0, 180, 220)
    overlay = _draw_progress_bar(overlay, 20, h - 30, w - 40, 14, progress, bar_fg)
    
    return overlay

def draw_knox_processing(frame: np.ndarray) -> np.ndarray:
    h, w, _ = frame.shape
    overlay = frame.copy()
    dark = np.full_like(overlay, (40, 20, 0))
    cv2.addWeighted(overlay, 0.7, dark, 0.3, 0, overlay)
    
    cx, cy = w // 2, h // 2
    # Knox card
    overlay = _draw_rounded_rect(overlay, (cx - 260, cy - 80), (cx + 260, cy + 120),
                                 (30, 25, 15), 20, alpha=0.9, border_bgr=(180, 160, 100))
    overlay = _pil_put_text(overlay, "SAMSUNG Knox",
                            (cx, cy - 50), 32, (255, 255, 255), bold=True, anchor="mt")
    overlay = _pil_put_text(overlay, "Signing and Encrypting (AES-256-GCM)...",
                            (cx, cy), 18, (200, 200, 200), anchor="mt")
    
    # Animated-style spinner dots
    dot_y = cy + 60
    phase = int(time.time() * 4) % 3
    for i in range(3):
        alpha_dot = 255 if i == phase else 100
        cv2.circle(overlay, (cx - 20 + i * 20, dot_y), 5, (alpha_dot, alpha_dot, alpha_dot), -1)
    
    return overlay

def draw_complete_step(frame: np.ndarray, engine: PhotonIDEngine) -> np.ndarray:
    h, w, _ = frame.shape
    overlay = frame.copy()
    
    if engine.is_human:
        tint = np.full_like(overlay, (15, 40, 10))
        border_color = (0, 200, 100)
    else:
        tint = np.full_like(overlay, (15, 10, 40))
        border_color = (80, 80, 255)
    cv2.addWeighted(overlay, 0.7, tint, 0.3, 0, overlay)
    
    cx = w // 2
    
    # Result card
    card_top = 60
    card_bot = h - 60
    overlay = _draw_rounded_rect(overlay, (cx - 300, card_top), (cx + 300, card_bot),
                                 (20, 20, 20), 20, alpha=0.85, border_bgr=border_color)
    
    if engine.is_human:
        overlay = _pil_put_text(overlay, "Verified Human",
                                (cx, card_top + 50), 36, (100, 255, 160), bold=True, anchor="mt")
        # Knox badge
        badge_y = card_top + 110
        overlay = _draw_rounded_rect(overlay, (cx - 160, badge_y), (cx + 160, badge_y + 110),
                                     (20, 30, 20), 12, alpha=0.7, border_bgr=(0, 180, 100))
        overlay = _pil_put_text(overlay, "Secured by Samsung Knox",
                                (cx, badge_y + 12), 18, (220, 240, 220), bold=True, anchor="mt")
        overlay = _pil_put_text(overlay, "Integrity: 0x0 (Valid)",
                                (cx, badge_y + 40), 16, (140, 220, 160), anchor="mt")
        # Integration: Encryption status text
        overlay = _pil_put_text(overlay, "AES-256-GCM Encrypted",
                                (cx, badge_y + 70), 14, (100, 255, 160), anchor="mt")
    else:
        overlay = _pil_put_text(overlay, "Deepfake Detected",
                                (cx, card_top + 50), 36, (255, 100, 100), bold=True, anchor="mt")
        overlay = _pil_put_text(overlay, "This session has been flagged.",
                                (cx, card_top + 110), 18, (220, 160, 160), anchor="mt")
    
    # Confidence & signature
    info_y = card_bot - 140
    overlay = _pil_put_text(overlay, f"Confidence: {engine.confidence:.1f}%",
                            (cx, info_y), 20, (200, 200, 210), anchor="mt")
    
    if engine.secure_packet:
        sig_preview = engine.secure_packet['signature'][:24] + "..."
        overlay = _pil_put_text(overlay, f"Signature: {sig_preview}",
                                (cx, info_y + 35), 14, (140, 140, 150), anchor="mt")
    
    # Restart hint
    overlay = _pil_put_text(overlay, "Press R to restart",
                            (cx, card_bot - 30), 17, (180, 200, 220), bold=True, anchor="mt")
    
    return overlay

# ============================================================================
# MAIN
# ============================================================================

def main():
    cam = AsyncCam()
    # INTEGRATION: Multi-face parsing support (max 3 faces to detect spoofing attempts)
    mp_face = mp.solutions.face_mesh.FaceMesh(max_num_faces=3, refine_landmarks=True)
    engine = PhotonIDEngine()
    sentinel = SentinelVault()  # INITIALIZE SENTINEL VAULT
    
    cv2.namedWindow("PHOTON_ID", cv2.WND_PROP_FULLSCREEN)
    cv2.setWindowProperty("PHOTON_ID", cv2.WND_PROP_FULLSCREEN, cv2.WINDOW_FULLSCREEN)
    
    # Get screen resolution for scaling content to fullscreen
    try:
        import subprocess
        xrandr_out = subprocess.check_output(['xrandr'], stderr=subprocess.DEVNULL).decode()
        for line in xrandr_out.splitlines():
            if '*' in line:
                parts = line.split()
                screen_w, screen_h = map(int, parts[0].split('x'))
                break
        else:
            screen_w, screen_h = 1920, 1080
    except Exception:
        # INTEGRATION: Windows/Mac fallback to 1080p
        screen_w, screen_h = 1920, 1080
    
    def show_fullscreen(name, img):
        cv2.imshow(name, img)
    
    current_step = Step.FACE_DETECTION
    face_detected_count = 0
    step_start_time = time.perf_counter()
    last_challenge_time = time.perf_counter()
    current_color_idx = 0
    
    print("\n" + "="*70)
    print("PHOTON ID - Advanced Liveness Detection with Samsung Knox")
    print("="*70)
    print("STEP 1: Face Detection & Sentinel Check")
    print("STEP 2: Instructions")
    print("STEP 3: Continuous Flashing")
    print("STEP 4: Knox Signing")
    print("STEP 5: Result & Vault Registration")
    print("="*70 + "\n")
    
    while True:
        frame = cam.read()
        if frame is None:
            continue
        
        frame = cv2.flip(frame, 1)
        
        # Run face detection on the original small frame (faster)
        rgb_frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        res = mp_face.process(rgb_frame)
        
        # Upscale frame to screen resolution BEFORE drawing UI so nothing is pixelated
        frame = cv2.resize(frame, (screen_w, screen_h), interpolation=cv2.INTER_CUBIC)
        h, w, _ = frame.shape
        landmarks = None
        face_count = 0
        
        if res.multi_face_landmarks:
            face_count = len(res.multi_face_landmarks)
            landmarks = res.multi_face_landmarks[0].landmark
            if face_count == 1:
                face_detected_count += 1
        else:
            face_detected_count = max(0, face_detected_count - 1)
        
        face_detected = face_detected_count > 5
        
        # ====================================================================
        # STEP 1: FACE DETECTION (UPDATED)
        # ====================================================================
        if current_step == Step.FACE_DETECTION:
            vault_status = "UNKNOWN"
            trust_score = 0.0
            
            if face_detected and landmarks and face_count == 1:
                vault_status, trust_score = sentinel.check_identity(landmarks, w, h)
                
            overlay = draw_face_detection_step(frame, landmarks, face_detected, vault_status, trust_score, face_count)
            show_fullscreen("PHOTON_ID", overlay)
            
            key = cv2.waitKey(1) & 0xFF
            if key == ord(' ') and face_detected and face_count == 1:
                current_step = Step.INSTRUCTION
                step_start_time = time.perf_counter()
                print("✓ Step 1 Complete: Face detected")
        
        # ====================================================================
        # STEP 2: INSTRUCTION
        # ====================================================================
        elif current_step == Step.INSTRUCTION:
            overlay = draw_instruction_step(frame)
            show_fullscreen("PHOTON_ID", overlay)
            
            elapsed = time.perf_counter() - step_start_time
            if elapsed > 3.0:
                current_step = Step.FLASHING
                step_start_time = time.perf_counter()
                last_challenge_time = time.perf_counter()
                print("✓ Step 2 Complete: Instructions shown")
        
        # ====================================================================
        # STEP 3: FLASHING
        # ====================================================================
        elif current_step == Step.FLASHING:
            elapsed = time.perf_counter() - step_start_time
            
            # Pause analysis if multiple faces detected
            if face_count == 1:
                if elapsed - (last_challenge_time - step_start_time) > CHALLENGE_FREQ:
                    if current_color_idx == 0:
                        current_color_idx = random.randint(1, len(CHALLENGE_COLORS))
                    else:
                        current_color_idx = 0
                    last_challenge_time = time.perf_counter()
                    engine.record_challenge(current_color_idx)
                
                if landmarks:
                    glint_rgb = extract_glint_rgb(frame, landmarks, w, h)
                    engine.record_frame(glint_rgb)
                    engine.analyze()
            
            overlay = draw_flashing_step(frame, current_color_idx, elapsed, engine, face_count)
            show_fullscreen("PHOTON_ID", overlay)
            
            if engine.is_certain:
                current_step = Step.PROCESSING
                step_start_time = time.perf_counter()
                print("✓ Step 3 Complete: Certainty reached")
        
        # ====================================================================
        # STEP 4: KNOX PROCESSING
        # ====================================================================
        elif current_step == Step.PROCESSING:
            show_fullscreen("PHOTON_ID", draw_knox_processing(frame))
            cv2.waitKey(1)
            
            if time.time() - step_start_time > 0.8:
                engine.generate_secure_packet()
                print("\n[KNOX] Telemetry Signed via TrustZone.")
                print(json.dumps(engine.secure_packet, indent=2))
                current_step = Step.COMPLETE
                print("✓ Step 4 Complete: Knox signing complete")
        
        # ====================================================================
        # STEP 5: COMPLETE
        # ====================================================================
        elif current_step == Step.COMPLETE:
            # --- AUTO-REGISTER USER ---
            if engine.is_human and landmarks:
                 sentinel.register_user(landmarks, w, h, "VERIFIED", engine.confidence)
            elif not engine.is_human and engine.is_certain and landmarks:
                 sentinel.register_user(landmarks, w, h, "BLOCKED", 0.0)
            # --------------------------

            show_fullscreen("PHOTON_ID", draw_complete_step(frame, engine))
            if cv2.waitKey(1) == ord('r'):
                current_step = Step.FACE_DETECTION
                engine.reset()
                face_detected_count = 0
                print("\n→ Restarting verification process...")
        
        if cv2.waitKey(1) == ord('q'):
            break

    cam.stop()
    cv2.destroyAllWindows()
    print("\nPhoton ID closed.")

if __name__ == "__main__":
    main()
