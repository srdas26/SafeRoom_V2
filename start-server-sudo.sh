#!/bin/bash
# SafeRoom Server Starter with sudo
# Bu script server'ı root yetkileri ile başlatır

echo "🚀 Starting SafeRoom Server with root privileges..."
echo ""

# Önce eski işlemleri temizle
echo "🧹 Cleaning up old processes..."
./kill-java.sh

echo ""
echo "🔐 Starting server (requires sudo)..."
echo ""

# Server'ı sudo ile çalıştır
sudo ./gradlew run
