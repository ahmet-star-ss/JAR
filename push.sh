#!/bin/sh
# Projeyi GitHub'a gönderir (Termux). Kullanım: sh push.sh
set -e
REPO="https://github.com/ahmet-star-ss/JAR.git"

[ -d .git ] || git init -q
git branch -M main
git config user.name  >/dev/null 2>&1 || git config user.name  "JARVIS"
git config user.email >/dev/null 2>&1 || git config user.email "jarvis@example.com"
git add -A
git commit -q -m "JARVIS Android" || true
git remote remove origin 2>/dev/null || true
git remote add origin "$REPO"

if git push -u origin main; then
  echo "Tamam. GitHub > JAR > Actions sekmesinden derlemeyi izle."
else
  echo "Push reddedildi (depoda dosya var). Üstüne yazmak için: git push -f origin main"
fi
