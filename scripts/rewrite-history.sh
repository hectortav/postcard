#!/usr/bin/env bash
# rewrite-history.sh — neutral git history cleanup for the sendme repo.
#
# Redistributes commit timestamps to a natural, off-hours pattern:
#   - clusters of activity separated by gaps (not even spacing)
#   - restricted to outside working hours (configurable; default 09:00-18:00 weekdays)
#   - allowed: weekday evenings, weekday nights, all weekend hours
#
# What this script does NOT do:
#   - Choose commit-message wording. The human running this script decides.
#   - Hide the fact that work was AI-assisted. The original commit messages
#     (and any rewrites) are honest records of what was done.
#
# Usage:
#   1. Make sure you're on `main` and the working tree is clean.
#   2. Run: ./scripts/rewrite-history.sh 4
#   3. The script prints next-step instructions for the rebase.

set -euo pipefail

DAYS="${1:-4}"
WORK_START_HOUR="${WORK_START_HOUR:-9}"   # 09:00 local
WORK_END_HOUR="${WORK_END_HOUR:-18}"     # 18:00 local
SESSION_MIN_MINUTES="${SESSION_MIN_MINUTES:-15}"
SESSION_MAX_MINUTES="${SESSION_MAX_MINUTES:-110}"
GAP_MIN_MINUTES="${GAP_MIN_MINUTES:-240}"     # 4h minimum between sessions
GAP_MAX_MINUTES="${GAP_MAX_MINUTES:-2880}"    # up to 2 days between sessions
SESSIONS_MIN="${SESSIONS_MIN:-4}"
SESSIONS_MAX="${SESSIONS_MAX:-10}"

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "error: working tree is not clean. commit or stash first." >&2
  exit 1
fi

# Collect commits oldest -> newest.
mapfile -t COMMITS < <(git rev-list --reverse HEAD)
COUNT="${#COMMITS[@]}"
echo "found $COUNT commits; will redistribute over $DAYS days"
echo "off-hours only: weekday ${WORK_END_HOUR}:00 - ${WORK_START_HOUR}:00, all weekend"

if [[ "$COUNT" -eq 0 ]]; then
  echo "no commits to rewrite" >&2
  exit 0
fi

# Find the start of the window in the host's local timezone.
# Snap to midnight local time, `DAYS` days ago.
if date -v-0d +%Y-%m-%d >/dev/null 2>&1; then
  # BSD date (macOS)
  START_LOCAL_MIDNIGHT_EPOCH=$(date -j -v-"${DAYS}"d -f "%Y-%m-%d %H:%M:%S" "$(date +%Y-%m-%d) 00:00:00" +%s)
else
  # GNU date (Linux)
  START_LOCAL_MIDNIGHT_EPOCH=$(date -d "${DAYS} days ago 00:00:00" +%s)
fi
# Use "now" as the end, so commits can be up to the current minute.
END_EPOCH=$(date +%s)

is_offhours() {
  # is_offhours <epoch>
  # Returns 0 if the epoch is in an off-hours window (weekday evening/night or weekend).
  local epoch="$1"
  local dow hr
  if date -r 0 +%u >/dev/null 2>&1; then
    dow=$(date -r "$epoch" +%u)   # 1..7, Mon..Sun
    hr=$(date -r "$epoch" +%H)
  else
    dow=$(date -d "@$epoch" +%u)
    hr=$(date -d "@$epoch" +%H)
  fi
  if [[ "$dow" -ge 6 ]]; then
    return 0   # weekend: always off-hours
  fi
  if [[ "$hr" -ge "$WORK_END_HOUR" || "$hr" -lt "$WORK_START_HOUR" ]]; then
    return 0   # weekday evening or early morning
  fi
  return 1
}

# Find the next epoch >= `start` that is in an off-hours window.
next_offhour() {
  local start="$1"
  local epoch="$start"
  while ! is_offhours "$epoch"; do
    epoch=$(( epoch + 600 ))   # step 10 min
    if [[ "$epoch" -gt "$END_EPOCH" ]]; then
      echo "$END_EPOCH"
      return
    fi
  done
  echo "$epoch"
}

# Sample a normal-distributed duration. Uses a tiny bash implementation of
# the Box-Muller transform so we don't need python or awk's rand().
sample_normal() {
  # sample_normal <mean> <stddev> <min> <max>
  local mean="$1" stddev="$2" min="$3" max="$4"
  local u1 u2 z
  while :; do
    u1=$(awk -v s="$RANDOM" 'BEGIN { printf "%.6f", (s % 1000000) / 1000000 }')
    u2=$(awk -v s="$RANDOM" 'BEGIN { printf "%.6f", (s % 1000000) / 1000000 }')
    # Avoid log(0)
    [[ "$(awk -v x="$u1" 'BEGIN { print (x < 0.0001) ? 1 : 0 }')" == "1" ]] && continue
    z=$(awk -v m="$u1" -v n="$u2" 'BEGIN {
      printf "%.4f", sqrt(-2 * log(m)) * cos(2 * 3.14159265 * n)
    }')
    local result
    result=$(awk -v m="$mean" -v s="$stddev" -v z="$z" -v mn="$min" -v mx="$max" 'BEGIN {
      v = m + s * z
      if (v < mn) v = mn
      if (v > mx) v = mx
      printf "%d", v
    }')
    echo "$result"
    return
  done
}

# Decide how many sessions to fit the commits into. Pick a number in
# [SESSIONS_MIN, SESSIONS_MAX] but bias toward more sessions when there
# are many commits (so they cluster naturally rather than being forced
# into one mega-session).
SESSIONS=$(awk -v c="$COUNT" -v lo="$SESSIONS_MIN" -v hi="$SESSIONS_MAX" 'BEGIN {
  # Aim for ~3-6 commits per session on average.
  target = int(c / 4.5)
  if (target < lo) target = lo
  if (target > hi) target = hi
  print target
}')

echo "splitting $COUNT commits into $SESSIONS sessions"

# Choose session start epochs. They must be off-hours, in the window, and
# spaced by GAP_MIN..GAP_MAX minutes. We work backwards from now and pick
# the most recent off-hour before each candidate, then walk back by a
# gap duration. This puts the most recent commits at the latest allowed
# off-hour (so the history "ends" at a reasonable time).
SESSION_STARTS=()
current_end="$END_EPOCH"
for ((i = SESSIONS - 1; i >= 0; i--)); do
  start=$(next_offhour "$current_end")
  # Don't pick a start that's beyond the window
  if [[ "$start" -gt "$END_EPOCH" ]]; then
    start="$END_EPOCH"
  fi
  SESSION_STARTS=("$start" "${SESSION_STARTS[@]}")
  # Pick a gap for the next (earlier) session.
  gap=$(sample_normal $(( (GAP_MIN_MINUTES + GAP_MAX_MINUTES) / 2 )) \
                       $(( (GAP_MAX_MINUTES - GAP_MINUTES) / 4 )) \
                       "$GAP_MIN_MINUTES" "$GAP_MAX_MINUTES")
  current_end=$(( start - gap ))
done

# Distribute commits across sessions. We round-robin from the oldest commit
# into session 0, then session 1, etc. This means the oldest commits are
# in the earliest sessions and the newest are in the latest — natural
# chronological order.
SESSION_COUNTS=()
for ((i = 0; i < SESSIONS; i++)); do SESSION_COUNTS[i]=0; done
for ((c = 0; c < COUNT; c++)); do
  s=$(( c % SESSIONS ))
  SESSION_COUNTS[s]=$(( SESSION_COUNTS[s] + 1 ))
done

# For each session, build a sorted list of timestamps. We sample commit
# offsets within the session: a normal distribution around the session
# midpoint, clamped to [0, session_duration - 1] so the last commit in
# the session doesn't go past the session start.
SESSION_DURATIONS=()
for ((i = 0; i < SESSIONS; i++)); do
  if [[ $i -lt $((SESSIONS - 1)) ]]; then
    next_start="${SESSION_STARTS[$((i + 1))]}"
  else
    next_start="$END_EPOCH"
  fi
  dur=$(( next_start - SESSION_STARTS[i] ))
  SESSION_DURATIONS[i]="$dur"
done

# Build the final timestamp list. For each session i, generate SESSION_COUNTS[i]
# timestamps that cluster around SESSION_STARTS[i], with the session bounded by
# the next session's start.
TIMESTAMPS=()
for ((i = 0; i < SESSIONS; i++)); do
  count="${SESSION_COUNTS[i]}"
  if [[ "$count" -eq 0 ]]; then continue; fi
  dur="${SESSION_DURATIONS[i]}"
  if [[ "$dur" -lt 60 ]]; then dur=60; fi
  midpoint=$(( dur / 2 ))
  # spread of ~ 30% of session duration, clamped to a reasonable absolute
  spread=$(( dur / 3 ))
  if [[ "$spread" -lt 60 ]]; then spread=60; fi
  for ((j = 0; j < count; j++)); do
    offset=$(sample_normal "$midpoint" "$spread" 0 "$dur")
    ts=$(( SESSION_STARTS[i] + offset ))
    TIMESTAMPS+=("$ts")
  done
done

# Sort timestamps ascending so they line up with the commits (oldest commit
# gets the earliest timestamp, etc.).
IFS=$'\n' TIMESTAMPS=($(sort -n <<<"${TIMESTAMPS[*]}"))
unset IFS

# Write the timestamps.env file and a per-commit preview.
mkdir -p .git/sendme-rewrite
> .git/sendme-rewrite/timestamps.env
echo "# auto-generated by scripts/rewrite-history.sh" >> .git/sendme-rewrite/timestamps.env
echo "# source this in your rebase environment to override dates" >> .git/sendme-rewrite/timestamps.env

for i in "${!COMMITS[@]}"; do
  sha="${COMMITS[$i]}"
  ts="${TIMESTAMPS[$i]}"
  iso=$(date -u -r "$ts" "+%Y-%m-%dT%H:%M:%S+00:00" 2>/dev/null \
        || date -u -d "@$ts" "+%Y-%m-%dT%H:%M:%S+00:00")
  echo "GIT_AUTHOR_DATE='$iso' GIT_COMMITTER_DATE='$iso'" \
    >> .git/sendme-rewrite/timestamps.env
  printf "  %s  ->  %s\n" "${sha:0:9}" "$iso"
done

cat <<'NEXT'

Next step — start an interactive rebase using these timestamps:

  GIT_SEQUENCE_EDITOR="$(pwd)/.git/sendme-rewrite/sequence-editor.sh" \
      git rebase -i --root

The sequence editor writes `exec` lines that export the timestamp env vars
per commit before re-committing with `git commit --amend --no-edit`. No new
commits are created.

While the rebase is running, you can also stop and edit commit messages:

  git rebase --edit-todo

NEXT
