#!/usr/bin/env bash
# srcx — source symbol plugin installer
#
# Usage:
#   bash install.sh                  Install (interactive if piped)
#   bash install.sh --uninstall      Remove init script
#   curl -fsSL .../install.sh | bash Piped install (interactive via /dev/tty)

set -euo pipefail

if [ -n "${SRCX_VERSION:-}" ]; then
    VERSION="$SRCX_VERSION"
else
    VERSION=$(curl -fsSL https://api.github.com/repos/ClankerGuru/srcx/releases/latest 2>/dev/null \
        | sed -n 's/.*"tag_name": *"v\{0,1\}\([0-9.]*\)".*/\1/p' || true)
    VERSION="${VERSION:-0.1.0}"
fi

INIT_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/init.d"
INIT_FILE="$INIT_DIR/00-srcx.init.gradle.kts"

echo ""
echo "  srcx v${VERSION}"
echo "  ─────────────────────"
echo ""

if [ "${1:-}" = "--uninstall" ]; then
    if [ -f "$INIT_FILE" ]; then
        rm -f "$INIT_FILE"
        echo "  Removed $INIT_FILE"
    else
        echo "  Nothing to remove."
    fi
    echo ""
    exit 0
fi

mkdir -p "$INIT_DIR"

cat > "$INIT_FILE" << INITSCRIPT
initscript {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("zone.clanker:plugin-srcx:$VERSION")
    }
}

beforeSettings {
    apply<zone.clanker.gradle.srcx.Srcx.SettingsPlugin>()
}
INITSCRIPT

echo "  Installed init script:"
echo "    $INIT_FILE"
echo ""
echo "  All Gradle projects now have srcx tasks available."
echo "  Run './gradlew srcx-generate' to extract source symbols."
echo ""
echo "  To uninstall:"
echo "    bash install.sh --uninstall"
echo ""
