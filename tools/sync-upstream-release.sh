#!/usr/bin/env bash
# Mira el ultimo tag de version (X.Y[.Z]) de upstream; si este fork aun no lo tiene, lo mergea en la
# rama actual (conservando las modificaciones del fork, sin reescribir historia), crea el mismo tag
# en el fork y lo sube. Ese tag dispara el workflow release.yaml (build + publicacion en F-Droid).
#
# Uso: tools/sync-upstream-release.sh [--no-push]
# Entorno:
#   UPSTREAM_URL  repositorio upstream (por defecto Freeyourgadget/Gadgetbridge en Codeberg)
#   PUSH_REMOTE   remote al que subir (por defecto origin)
set -euo pipefail

UPSTREAM_URL="${UPSTREAM_URL:-https://codeberg.org/Freeyourgadget/Gadgetbridge.git}"
PUSH_REMOTE="${PUSH_REMOTE:-origin}"

if ! git diff --quiet || ! git diff --cached --quiet; then
    echo "Working tree has uncommitted changes, aborting" >&2
    exit 1
fi

latest=$(git ls-remote --tags --refs "$UPSTREAM_URL" \
    | sed 's|.*refs/tags/||' \
    | grep -E '^[0-9]+\.[0-9]+(\.[0-9]+)*$' \
    | sort -V | tail -n1)
if [ -z "$latest" ]; then
    echo "No version tags found in upstream" >&2
    exit 1
fi

if git ls-remote --exit-code --tags "$PUSH_REMOTE" "refs/tags/$latest" >/dev/null 2>&1; then
    echo "Tag $latest already exists in this fork, nothing to do"
    exit 0
fi

echo "New upstream tag: $latest"
git fetch --quiet --no-tags "$UPSTREAM_URL" "refs/tags/$latest:refs/upstream-tags/$latest"
commit=$(git rev-parse "refs/upstream-tags/$latest^{commit}")

if git merge-base --is-ancestor "$commit" HEAD; then
    echo "Upstream $latest is already part of HEAD"
elif ! git merge --no-edit -m "Merge upstream $latest" "$commit"; then
    git merge --abort
    echo "Merge conflicts with upstream $latest, resolve them manually" >&2
    exit 2
fi

git tag -a "$latest" -m "Gadgetbridge $latest + arnyminerz modifications"

if [ "${1:-}" = "--no-push" ]; then
    echo "Merged and tagged $latest, not pushing"
else
    git push "$PUSH_REMOTE" HEAD "refs/tags/$latest"
fi
