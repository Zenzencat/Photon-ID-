# 🔦 Photon ID

> **Corneal-glint liveness detection** — defeating deepfakes with the physics of light, no ML model required.

[![Python 3.9+](https://img.shields.io/badge/python-3.9%2B-blue)](https://python.org)

---

## 🧠 Concept

Most anti-spoofing systems rely on ML models that can themselves be fooled by sophisticated deepfakes.
**Photon ID** takes a physics-first approach:

1. **Flash** a randomised sequence of coloured lights (R / G / B / Cyan / Magenta / Yellow) at the user's face — no two consecutive flashes share the same colour, and a black calibration frame is inserted every 3 flashes to keep the baseline accurate.
2. **Capture** the corneal glint — the tiny reflection on the surface of each eye.
3. **Validate** that the correct colour appears in the glint with physiologically plausible latency (~150–400 ms).
4. **Reject** anything that can't react in real-time: printed photos, screen replays, video loops, deepfake streams.

A **stagnation detector** additionally flags signals that are unnaturally stable — a hallmark of static or looped media.

---

## ✨ Features

| Feature | Description |
|---|---|
| **Random color sequence** | No two consecutive flashes share the same colour — harder to spoof |
| **Physics-based detection** | No ML model — uses corneal optics to verify liveness |
| **Adaptive latency model** | Learns your camera's response curve (EWMA) |
| **Stagnation analysis** | Catches frozen / looping video attacks |
| **SentinelVault** | Geometric face hash → fast re-verification within session |
| **Knox-style signing** | HMAC-SHA256 attestation packet simulating Samsung TrustZone |
| **Async camera** | Background-thread capture, zero main-loop blocking |
| **CLI flags** | `--camera`, `--no-fullscreen`, `--vault-ttl` |

---

## 📦 Requirements

```bash
pip install opencv-python mediapipe numpy
```

| Package | Version |
|---|---|
| opencv-python | ≥ 4.8 |
| mediapipe | ≥ 0.10 |
| numpy | ≥ 1.24 |

---

## 🚀 Quick Start

```bash
# Clone
git clone https://github.com/<your-username>/photon-id.git
cd photon-id

# Install dependencies
pip install -r requirements.txt

# Run (default camera)
python photon_id.py

# Run windowed (no fullscreen) — great for demos / laptops
python photon_id.py --no-fullscreen

# Use a different camera
python photon_id.py --camera 1

# Keep verified identities for 5 minutes
python photon_id.py --vault-ttl 300
```

### Controls

| Key | Action |
|---|---|
| `SPACE` | Start enrollment (after face detected) |
| `R` | Restart verification |
| `Q` / `ESC` | Quit |

---

## 🔬 How It Works

```
Screen flashes color  →  Corneal glint captured  →  Color + latency validated
        ↓                                                      ↓
  ChallengeData                                         FrameData
  (timestamp, label)                              (timestamp, RGB, intensity)
        ↓                                                      ↓
                    PhotonIDEngine.analyze()
                    ├─ Baseline subtraction (EWMA)
                    ├─ Color dominance check
                    ├─ Latency window [20ms – 600ms]
                    ├─ Adaptive latency model
                    ├─ Stagnation detection
                    └─ Confidence scoring (recency-weighted)
                                  ↓
                    Knox attestation packet (HMAC-SHA256)
                                  ↓
                    SentinelVault registration
```

### Detection Pipeline

| Stage | What happens |
|---|---|
| **Step 1** | Face detection + SentinelVault check (known / blocked / new) |
| **Step 2** | Epilepsy warning + instructions (3 s) |
| **Step 3** | Continuous color challenge with real-time confidence display |
| **Step 4** | Knox TrustZone telemetry signing simulation |
| **Step 5** | Verdict display + automatic Vault registration |

---

## 🏗️ Architecture

```
photon_id.py
├── MockKnoxVault       — Samsung Knox TrustZone simulation (HMAC-SHA256)
├── SentinelVault       — Identity persistence via geometric face hashing
├── PhotonIDEngine      — Core detection logic
│   ├── analyze()       — Challenge-response matching
│   ├── _color_score()  — BGR channel dominance scoring
│   └── _update_metrics() — Confidence + stagnation check
├── AsyncCam            — Background-thread camera reader
├── extract_glint_rgb() — MediaPipe iris landmark → corneal RGB
└── draw_*()            — OpenCV UI rendering functions
```

---

## ⚠️ Limitations & Future Work

- **Lighting conditions**: Bright ambient light can saturate the cornea and reduce signal quality.
- **Screen brightness**: Detection works best at ≥50% screen brightness.
- **Multiple faces**: Currently processes only the primary detected face.
- **Knox simulation**: Real deployment would use the Knox SDK; this is a cryptographic mock.
- **Potential improvement**: Multi-frequency temporal analysis, IR illumination support.

---

## 📄 License

MIT License — see [LICENSE](LICENSE) for details.

---

