#!/bin/sh
set -e
FILE="word.tar.gz"
trap 'rm -f "$FILE"' EXIT
curl -L https://github.com/8ta4/word/releases/download/v0.1.14/word.tar.gz -o $FILE
echo "db445c6b8e6c24e1c35b729638ca921d7613e398846342004efd806fac1ef919  $FILE" | shasum -a 256 -c
tar -xzf $FILE