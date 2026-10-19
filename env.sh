# Source of truth for paths. Override by exporting before running.
export MB="${MB:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"
export J17="${J17:-${JAVA_HOME_17_X64:-/usr/lib/jvm/temurin-17-jdk-amd64}/bin/java}"   # runs the server
export J25="${J25:-${JAVA_HOME_25_X64:-/usr/lib/jvm/temurin-25-jdk-amd64}/bin/java}"   # runs the bots (CLaJ classes need Java 25)
export JAVAC25="${JAVAC25:-$(dirname "$J25")/javac}"
