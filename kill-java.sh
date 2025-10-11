#!/bin/bash
# Java Process Killer - Tüm Java işlemlerini öldürür

echo "🔍 Finding all Java processes..."
echo ""

# Tüm Java işlemlerini listele
ps aux | grep '[j]ava' | grep -v grep

echo ""
echo "🔪 Killing all Java processes..."

# Tüm Java işlemlerini öldür
pkill -9 java

sleep 1

echo ""
echo "✅ All Java processes terminated"
echo ""

# Kontrol
REMAINING=$(ps aux | grep '[j]ava' | grep -v grep | wc -l)
if [ $REMAINING -eq 0 ]; then
    echo "✅ No Java processes remaining"
else
    echo "⚠️  Warning: $REMAINING Java processes still running:"
    ps aux | grep '[j]ava' | grep -v grep
fi

# Port kontrolü
echo ""
echo "📊 Port status:"
sudo lsof -i:443 2>/dev/null || echo "  ✅ Port 443 is free"
sudo lsof -i:45000 2>/dev/null || echo "  ✅ Port 45000 is free"
