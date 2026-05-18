#!/bin/sh
set -e
FILE="word.tar.gz"
trap 'rm -f "$FILE"' EXIT
curl -L https://github.com/8ta4/word/releases/download/v0.1.0/word.tar.gz -o $FILE
echo "ff23cdb3811461d1a7e0b751d6fccc9f017cd5f4d04b58ba3f9231fd3ab4f564  $FILE" | shasum -a 256 -c
tar -xzf $FILE