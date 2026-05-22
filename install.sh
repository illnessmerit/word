#!/bin/sh
set -e
FILE="word.tar.gz"
trap 'rm -f "$FILE"' EXIT
curl -L https://github.com/8ta4/word/releases/download/v0.1.3/word.tar.gz -o $FILE
echo "0d1eb67b00add38dcdadea40647dec1bc83b094e74b4c9f8131e3f6f60aa1e45  $FILE" | shasum -a 256 -c
tar -xzf $FILE