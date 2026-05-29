#!/bin/sh
set -e
FILE="word.tar.gz"
trap 'rm -f "$FILE"' EXIT
curl -L https://github.com/8ta4/word/releases/download/v0.1.13/word.tar.gz -o $FILE
echo "31f35eb265a9a752444ac952d6d3556ae9ea94743b5c60426ef2c403ad136c98  $FILE" | shasum -a 256 -c
tar -xzf $FILE