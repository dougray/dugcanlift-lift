#!/usr/bin/env bash
# Writes keystore.properties (gitignored) for release signing. Run this
# yourself — it never prints or logs the passwords you type, and nothing here
# transmits them anywhere.
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ -f keystore.properties ]]; then
    read -r -p "keystore.properties already exists. Overwrite? [y/N] " confirm
    [[ "$confirm" == "y" || "$confirm" == "Y" ]] || { echo "Aborted."; exit 1; }
fi

default_store="$HOME/Desktop/dugcanlift-calculator/dugcanlift.jks"
read -r -p "Path to your .jks keystore [$default_store]: " store_file
store_file="${store_file:-$default_store}"

if [[ ! -f "$store_file" ]]; then
    echo "No file at: $store_file" >&2
    exit 1
fi

read -r -p "Key alias: " key_alias

read -r -s -p "Keystore password: " store_password
echo
read -r -s -p "Key password (press Enter if same as keystore password): " key_password
echo
key_password="${key_password:-$store_password}"

cat > keystore.properties <<EOF
storeFile=$store_file
storePassword=$store_password
keyAlias=$key_alias
keyPassword=$key_password
EOF
chmod 600 keystore.properties

echo "Wrote keystore.properties (mode 600, gitignored)."
