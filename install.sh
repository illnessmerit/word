#!/bin/sh
set -e
FILE="word.tar.gz"
trap 'rm -f "$FILE"' EXIT
curl -L https://github.com/8ta4/word/releases/download/v0.1.10/word.tar.gz -o $FILE
echo "01a9779919ba8000fee4260823a39fdda91e9cc51975fcfce48216b53a68b1b7  $FILE" | shasum -a 256 -c
tar -xzf $FILE