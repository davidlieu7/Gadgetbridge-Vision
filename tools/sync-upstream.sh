#!/usr/bin/env bash
# Merges the upstream branch into the current branch (never rewrites history) and pushes it.
#
# Usage: tools/sync-upstream.sh [--no-push]
# Environment:
#   UPSTREAM_URL     upstream repository (default: Freeyourgadget/Gadgetbridge on Codeberg)
#   UPSTREAM_BRANCH  branch to merge (default: master)
#   PUSH_REMOTE      remote to push to (default: origin)
set -euo pipefail

UPSTREAM_URL="${UPSTREAM_URL:-https://codeberg.org/Freeyourgadget/Gadgetbridge.git}"
UPSTREAM_BRANCH="${UPSTREAM_BRANCH:-master}"
PUSH_REMOTE="${PUSH_REMOTE:-origin}"

if ! git diff --quiet || ! git diff --cached --quiet; then
    echo "Working tree has uncommitted changes, aborting" >&2
    exit 1
fi

git fetch --quiet "$UPSTREAM_URL" "$UPSTREAM_BRANCH"
upstream=$(git rev-parse FETCH_HEAD)

if git merge-base --is-ancestor "$upstream" HEAD; then
    echo "Already up to date with upstream ($upstream)"
    exit 0
fi

if ! git merge --no-edit -m "Merge upstream $UPSTREAM_BRANCH" "$upstream"; then
    git merge --abort
    echo "Merge conflicts with upstream, resolve them manually" >&2
    exit 2
fi

if [ "${1:-}" = "--no-push" ]; then
    echo "Merged upstream, not pushing"
else
    git push "$PUSH_REMOTE" HEAD
fi
