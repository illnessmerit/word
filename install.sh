#!/bin/sh
set -e
FILE="word.tar.gz"
trap 'rm -f "$FILE"' EXIT
curl -L https://github.com/8ta4/word/releases/download/v0.1.8/word.tar.gz -o $FILE
echo "d5f145ae75a30695a9106debba05af6db6903bef4dc279814c81f713304e0109  $FILE" | shasum -a 256 -c
tar -xzf $FILE