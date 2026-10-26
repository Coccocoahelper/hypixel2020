#!/usr/bin/env bash
# Build the plugin, install it plus the maps into run/, and start Spigot 1.8.8 on Java 8.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
RUN="$ROOT/run"
# Java 8 (Spigot 1.8.8 needs it): .tools/jdk8*, then $JAVA8_HOME
JAVA8_DIR="$(ls -d "$ROOT"/.tools/jdk8*/bin 2>/dev/null | head -1)"
[ -n "$JAVA8_DIR" ] || JAVA8_DIR="${JAVA8_HOME:+$JAVA8_HOME/bin}"
if [ -z "$JAVA8_DIR" ]; then
  echo "Java 8 not found. Install Temurin 8 (https://adoptium.net) and set JAVA8_HOME to it, then rerun."
  exit 1
fi
JAVA8="$JAVA8_DIR/java"
SPIGOT="$(ls ~/.gradle/caches/modules-2/files-2.1/org.spigotmc/spigot/1.8.8-R0.1-SNAPSHOT/*/spigot-1.8.8-R0.1-SNAPSHOT.jar | head -1)"

cd "$ROOT" && ./gradlew build -q
mkdir -p "$RUN/plugins/Hypixel2020/maps"
cp build/libs/Hypixel2020.jar "$RUN/plugins/"
cp "$SPIGOT" "$RUN/spigot-1.8.8.jar"
# bundled maps (maps/) and any you imported yourself (import/ready/)
for m in "$ROOT"/maps/*/ "$ROOT"/import/ready/*/; do
  [ -d "$m" ] || continue
  name="$(basename "$m")"
  [ -d "$RUN/plugins/Hypixel2020/maps/$name" ] || cp -r "$m" "$RUN/plugins/Hypixel2020/maps/$name"
done
# first start: default server settings (offline mode, RCON with a random password, long player tracking range)
if [ ! -f "$RUN/server.properties" ]; then
  pw="$(LC_ALL=C tr -dc 'a-z0-9' </dev/urandom | head -c 20)"
  sed "s/CHANGE_ME/$pw/" "$ROOT/server-defaults/server.properties" > "$RUN/server.properties"
fi
[ -f "$RUN/spigot.yml" ] || cp "$ROOT/server-defaults/spigot.yml" "$RUN/spigot.yml"
if ! grep -q "eula=true" "$RUN/eula.txt" 2>/dev/null; then
  echo "Minecraft servers need you to accept the Minecraft EULA: https://aka.ms/MinecraftEULA"
  read -r -p "Do you accept it? (y/n) " eula
  if [ "$eula" != "y" ] && [ "$eula" != "Y" ]; then echo "The server can't start without accepting the EULA."; exit 1; fi
  echo "eula=true" > "$RUN/eula.txt"
fi
cd "$RUN" && exec "$JAVA8" -Xms2G -Xmx4G -XX:+UseG1GC ${H2020_JVM_OPTS:-} -cp spigot-1.8.8.jar org.bukkit.craftbukkit.Main nogui
