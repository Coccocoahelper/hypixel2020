#!/usr/bin/env bash
# Start the server using WindSpigot on Java 25.
# The plugin jar is downloaded from the latest GitHub release instead of being built locally.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
RUN="$ROOT/run"
mkdir -p "$RUN/plugins/Hypixel2020/maps"

PLUGIN_URL="${PLUGIN_URL:-https://github.com/Coccocoahelper/hypixel2020/releases/latest/download/Hypixel2020.jar}"
WINDSPIGOT_URL="${WINDSPIGOT_URL:-https://github.com/Wind-Development/WindSpigot/releases/latest/download/WindSpigot-2.1.4.jar}"
JAVA25_DIR="$(ls -d "$ROOT"/.tools/jdk25*/bin 2>/dev/null | head -1)"
[ -n "$JAVA25_DIR" ] || JAVA25_DIR="${JAVA25_HOME:+$JAVA25_HOME/bin}"
if [ -z "$JAVA25_DIR" ]; then
  echo "Java 25 not found. Install Temurin 25 (https://adoptium.net/temurin/releases/) and set JAVA25_HOME to it, then rerun."
  exit 1
fi
JAVA25="$JAVA25_DIR/java"

download() {
  local url="$1"
  local out="$2"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$url" -o "$out"
  elif command -v wget >/dev/null 2>&1; then
    wget -qO "$out" "$url"
  else
    echo "Neither curl nor wget is installed. Please install one of them first." >&2
    exit 1
  fi
}

if [ ! -f "$RUN/plugins/Hypixel2020.jar" ]; then
  echo "Downloading pre-compiled plugin jar..."
  download "$PLUGIN_URL" "$RUN/plugins/Hypixel2020.jar"
fi

if [ ! -f "$RUN/WindSpigot.jar" ]; then
  echo "Downloading WindSpigot server jar..."
  download "$WINDSPIGOT_URL" "$RUN/WindSpigot.jar"
fi

# install the bundled maps (maps/) and any you imported yourself (import/ready/) that are not in run/ yet
for m in "$ROOT"/maps/*/ "$ROOT"/import/ready/*/; do
  [ -d "$m" ] || continue
  name="$(basename "$m")"
  [ -d "$RUN/plugins/Hypixel2020/maps/$name" ] || cp -r "$m" "$RUN/plugins/Hypixel2020/maps/$name"
done

if [ ! -f "$RUN/server.properties" ]; then
  pw="$(LC_ALL=C tr -dc 'a-z0-9' </dev/urandom | head -c 20)"
  sed "s/CHANGE_ME/$pw/" "$ROOT/server-defaults/server.properties" > "$RUN/server.properties"
fi
[ -f "$RUN/spigot.yml" ] || cp "$ROOT/server-defaults/spigot.yml" "$RUN/spigot.yml"
if ! grep -q "eula=true" "$RUN/eula.txt" 2>/dev/null; then
  echo "Minecraft servers need you to accept the Minecraft EULA: https://aka.ms/MinecraftEULA"
  read -r -p "Do you accept it? (y/n) " eula
  if [ "$eula" != "y" ] && [ "$eula" != "Y" ]; then
    echo "The server can't start without accepting the EULA."
    exit 1
  fi
  echo "eula=true" > "$RUN/eula.txt"
fi

cd "$RUN"
exec "$JAVA25" -Xms2G -Xmx4G -XX:+UseG1GC ${H2020_JVM_OPTS:-} -jar WindSpigot.jar nogui
