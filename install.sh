#!/bin/sh
set -e
FILE="word.tar.gz"
trap 'rm -f "$FILE"' EXIT
curl -L https://github.com/8ta4/word/releases/download/v0.1.11/word.tar.gz -o $FILE
echo "6e2b6c4a65b42aa619e1880ad553cc928fa1397e788c62a3d5cbdd49b1fb4b2d  $FILE" | shasum -a 256 -c
tar -xzf $FILE