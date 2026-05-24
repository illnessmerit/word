#!/bin/sh
set -e
FILE="word.tar.gz"
trap 'rm -f "$FILE"' EXIT
curl -L https://github.com/8ta4/word/releases/download/v0.1.9/word.tar.gz -o $FILE
echo "284264fc529c3ace09ab0d74ba1e8884e86a9d19feab5c5d4b804965cd468693  $FILE" | shasum -a 256 -c
tar -xzf $FILE