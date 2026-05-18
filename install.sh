#!/bin/sh
set -e
FILE="word.tar.gz"
trap 'rm -f "$FILE"' EXIT
curl -L https://github.com/8ta4/word/releases/download/v0.1.1/word.tar.gz -o $FILE
echo "4b60fd4982d0717a3c670b19ebc1149148330ce72554e8fdc7c9ac858d30186a  $FILE" | shasum -a 256 -c
tar -xzf $FILE