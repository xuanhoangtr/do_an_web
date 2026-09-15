#!/bin/sh

set -eu

MAVEN_PROJECTBASEDIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
WRAPPER_DIR="$MAVEN_PROJECTBASEDIR/.mvn/wrapper"
WRAPPER_JAR="$WRAPPER_DIR/maven-wrapper.jar"
WRAPPER_URL="https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.4/maven-wrapper-3.3.4.jar"
WRAPPER_SHA256="4e2fbf6554bc8a4702cdfdd3bef464f423393d784ddbb037216320ce55d5e4e1"

verify_wrapper_jar() {
  actual=""
  if command -v sha256sum >/dev/null 2>&1; then
    actual=$(sha256sum "$WRAPPER_JAR" | awk '{print $1}')
  elif command -v shasum >/dev/null 2>&1; then
    actual=$(shasum -a 256 "$WRAPPER_JAR" | awk '{print $1}')
  elif command -v openssl >/dev/null 2>&1; then
    actual=$(openssl dgst -sha256 "$WRAPPER_JAR" | awk '{print $NF}')
  else
    echo "sha256sum, shasum, or openssl is required to verify Maven Wrapper." >&2
    exit 1
  fi
  if [ "$actual" != "$WRAPPER_SHA256" ]; then
    echo "Maven Wrapper checksum verification failed." >&2
    exit 1
  fi
}

if [ ! -f "$WRAPPER_JAR" ]; then
  echo "Downloading Maven Wrapper 3.3.4..."
  mkdir -p "$WRAPPER_DIR"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$WRAPPER_URL" -o "$WRAPPER_JAR"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$WRAPPER_URL" -O "$WRAPPER_JAR"
  else
    echo "curl or wget is required to download Maven Wrapper." >&2
    exit 1
  fi
fi

verify_wrapper_jar

if ! command -v java >/dev/null 2>&1; then
  echo "JAVA_HOME or java must point to JDK 21 or newer." >&2
  exit 1
fi

exec java ${MAVEN_OPTS:-} ${WRAPPER_JAVA_OPTS:-} -Dmaven.multiModuleProjectDirectory="$MAVEN_PROJECTBASEDIR" -cp "$WRAPPER_JAR" \
  org.apache.maven.wrapper.MavenWrapperMain "$@"
