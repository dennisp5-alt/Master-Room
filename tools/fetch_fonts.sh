#!/usr/bin/env bash
# Fonts come from the Google Fonts repository at a pinned commit, with their licences.
set -euo pipefail
F=app/src/main/assets/fonts
B=https://raw.githubusercontent.com/google/fonts/023eedd8780dd254e5905901473ed55e5475ee73/ofl
mkdir -p "$F"
curl -fsSL -o "$F/Barlow-Regular.ttf" "$B/barlow/Barlow-Regular.ttf"
curl -fsSL -o "$F/Barlow-SemiBold.ttf" "$B/barlow/Barlow-SemiBold.ttf"
curl -fsSL -o "$F/BarlowCondensed-SemiBold.ttf" "$B/barlowcondensed/BarlowCondensed-SemiBold.ttf"
curl -fsSL -o "$F/BarlowCondensed-Bold.ttf" "$B/barlowcondensed/BarlowCondensed-Bold.ttf"
curl -fsSL -o "$F/JetBrainsMono.ttf" "$B/jetbrainsmono/JetBrainsMono%5Bwght%5D.ttf"
curl -fsSL -o "$F/OFL-Barlow.txt" "$B/barlow/OFL.txt"
curl -fsSL -o "$F/OFL-JetBrainsMono.txt" "$B/jetbrainsmono/OFL.txt"
