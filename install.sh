#!/bin/sh
set -e
FILE="word.tar.gz"
trap 'rm -f "$FILE"' EXIT
curl -L https://github.com/8ta4/word/releases/download/v0.1.7/word.tar.gz -o $FILE
echo "48363a9289097c511326c64ac3fb753783d3e7a722da3b39cad004052fe77457  $FILE" | shasum -a 256 -c
tar -xzf $FILE