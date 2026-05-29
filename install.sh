#!/bin/sh
set -e
FILE="word.tar.gz"
trap 'rm -f "$FILE"' EXIT
curl -L https://github.com/8ta4/word/releases/download/v0.1.12/word.tar.gz -o $FILE
echo "b05f3ce43e071ccecc9d6c648ee798ad7e6453db321f5c0299408af8d3eda6b5  $FILE" | shasum -a 256 -c
tar -xzf $FILE