#!/bin/sh
set -e
FILE="word.tar.gz"
trap 'rm -f "$FILE"' EXIT
curl -L https://github.com/8ta4/word/releases/download/v0.1.4/word.tar.gz -o $FILE
echo "c6b56d4e061b3b8d03c4430bae7aa74f19662f86af08e4d9c8c14973fc0b5742  $FILE" | shasum -a 256 -c
tar -xzf $FILE