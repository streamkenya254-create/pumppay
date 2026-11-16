#!/usr/bin/env bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$DIR"

echo "======================================================================"
echo "    PUMPPAY & FIELD SHIFT: NASA SPACE APPS LIVE DEMO LAUNCHER"
echo "======================================================================"

# 1. Start ML Microservice if not running
if ! curl -s http://127.0.0.1:5055/health > /dev/null 2>&1; then
    echo "⚙️  Starting ML Microservice on port 5055..."
    .venv/bin/python scripts/ml_service.py 5055 > /dev/null 2>&1 &
    sleep 2
else
    echo "✅ ML Microservice is active on http://127.0.0.1:5055"
fi

# 2. Start Landing Page Website if not running
if ! curl -s http://localhost:5174 > /dev/null 2>&1; then
    echo "⚙️  Starting Landing Page Website on port 5174..."
    python3 -m http.server --directory frontend/website 5174 > /dev/null 2>&1 &
    sleep 1
else
    echo "✅ Landing Page Website is active on http://localhost:5174"
fi

# 3. Check for connected Android phone
if adb devices 2>/dev/null | grep -q "device$"; then
    echo "✅ Connected phone detected via ADB. Launching PumpPay..."
    adb shell am start -n ke.tafiti.pumppay/.MainActivity > /dev/null 2>&1 || true
fi

echo ""
echo "🎯 PROJECT SERVICES READY FOR SHOWCASE:"
echo "----------------------------------------------------------------------"
echo "1. Public Website:        http://localhost:5174"
echo "2. Android Mobile App:    Running on connected phone / Android Studio"
echo "3. ML Inference API:      http://127.0.0.1:5055/health"
echo "======================================================================"
