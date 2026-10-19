#!/bin/bash
source "$(cd "$(dirname "$0")/.." && pwd)/env.sh"
pkill -f "Dpname=.*_[0-9]" ; for p in $(cat $MB/arena/live/pids 2>/dev/null); do kill -- -$p 2>/dev/null; done; echo stopped
