#!/bin/sh
set -e
FILE="word.tar.gz"
trap 'rm -f "$FILE"' EXIT
curl -L https://github.com/8ta4/word/releases/download/v0.1.15/word.tar.gz -o $FILE
echo "bc52fa8042a328ebdb2ef00ed7f6bc983a0dee302011a9fc4c6edd7e6e92deda  $FILE" | shasum -a 256 -c
tar -xzf $FILE