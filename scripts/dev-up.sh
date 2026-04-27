#!/usr/bin/env bash
# dev-up.sh — Launch the full dev stack in background with log files.
#
# Services: Spring Boot (9090), Vite dev (3000/3001/…), FastAPI analytics (8001).
# Each service runs detached, logs stream to .dev-logs/<name>.log. Already-running
# services are detected and skipped. The script returns once everything answers
# healthchecks (or after a 120s timeout).
#
# Usage:
#   bash scripts/dev-up.sh              # launch all three
#   bash scripts/dev-up.sh --no-python  # skip FastAPI analytics
#   bash scripts/dev-up.sh --stop       # kill PIDs tracked in .dev-logs/*.pid
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"
LOGDIR="$REPO_ROOT/.dev-logs"
mkdir -p "$LOGDIR"

NO_PYTHON=0
STOP_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --no-python) NO_PYTHON=1 ;;
    --stop)      STOP_ONLY=1 ;;
    *) echo "Unknown flag: $arg" >&2; exit 2 ;;
  esac
done

is_up() { # $1 url
  local code
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 "$1" 2>/dev/null || echo 000)
  [ "$code" = "200" ] || [ "$code" = "304" ]
}

port_listening() { # $1 port
  netstat -an 2>/dev/null | grep LISTENING | grep -q ":$1 "
}

stop_service() { # $1 name
  local pidfile="$LOGDIR/$1.pid"
  if [ -f "$pidfile" ]; then
    local pid; pid=$(cat "$pidfile")
    if kill -0 "$pid" 2>/dev/null; then
      echo "Stopping $1 (PID $pid)"
      kill "$pid" 2>/dev/null || true
      sleep 1
      kill -9 "$pid" 2>/dev/null || true
    fi
    rm -f "$pidfile"
  fi
}

if [ "$STOP_ONLY" = "1" ]; then
  for svc in backend frontend analytics; do stop_service "$svc"; done
  echo "All tracked services stopped."
  exit 0
fi

start_if_needed() { # $1 name  $2 healthUrl  $3 launch-command
  local name="$1" url="$2" cmd="$3"
  local log="$LOGDIR/$name.log"
  local pidfile="$LOGDIR/$name.pid"
  if is_up "$url"; then
    echo "✓ $name already UP ($url)"
    return 0
  fi
  echo "→ Starting $name (log: .dev-logs/$name.log)"
  nohup bash -c "$cmd" > "$log" 2>&1 &
  echo $! > "$pidfile"
}

wait_for_up() { # $1 name  $2 url  $3 timeout-s
  local name="$1" url="$2" timeout="$3"
  local waited=0
  while [ "$waited" -lt "$timeout" ]; do
    if is_up "$url"; then echo "✓ $name healthy at $url"; return 0; fi
    sleep 2; waited=$((waited + 2))
  done
  echo "✗ $name did NOT respond at $url within ${timeout}s" >&2
  return 1
}

# Backend — Spring Boot 9090
start_if_needed backend "http://localhost:9090/actuator/health" \
  "./gradlew bootRun --no-daemon"

# Frontend — Vite dev server (ports cascade from 3000/3001/5173)
FE_PORT="3001"
if is_up "http://localhost:3000"; then FE_PORT=3000
elif is_up "http://localhost:5173"; then FE_PORT=5173
fi
start_if_needed frontend "http://localhost:$FE_PORT" \
  "cd frontend && npm run dev"

# Analytics — FastAPI 8001 (optional)
if [ "$NO_PYTHON" = "0" ]; then
  start_if_needed analytics "http://localhost:8001/health" \
    "cd python && JAVA_API_HOST=127.0.0.1 JAVA_API_PORT=9090 python -m uvicorn analytics_service.main:app --host 127.0.0.1 --port 8001"
fi

# Wait for health
wait_for_up backend  "http://localhost:9090/actuator/health" 90
# Frontend may have chosen a different port if 3000 was taken — re-detect
for p in 3000 3001 5173; do
  if is_up "http://localhost:$p"; then FE_PORT=$p; break; fi
done
wait_for_up frontend "http://localhost:$FE_PORT" 30 || true
if [ "$NO_PYTHON" = "0" ]; then
  wait_for_up analytics "http://localhost:8001/health" 60 || true
fi

echo ""
echo "────────────────────────────────────────────"
echo " Stack up:"
echo "   Backend    http://localhost:9090"
echo "   Frontend   http://localhost:$FE_PORT"
[ "$NO_PYTHON" = "0" ] && echo "   Analytics  http://localhost:8001/docs"
echo "   Logs       $LOGDIR/*.log"
echo " Stop:   bash scripts/dev-up.sh --stop"
echo "────────────────────────────────────────────"
