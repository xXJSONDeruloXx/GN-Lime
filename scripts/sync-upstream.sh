#!/usr/bin/env bash
# Rebase GN-Lime fork commits on top of latest upstream/master
# Usage: ./scripts/sync-upstream.sh
set -euo pipefail

UPSTREAM_REMOTE="upstream"
UPSTREAM_BRANCH="master"
FORK_BRANCH="main"

echo "→ Fetching $UPSTREAM_REMOTE..."
git fetch "$UPSTREAM_REMOTE"

echo "→ Switching to $FORK_BRANCH..."
git checkout "$FORK_BRANCH"

echo "→ Rebasing $FORK_BRANCH onto $UPSTREAM_REMOTE/$UPSTREAM_BRANCH..."
git rebase "$UPSTREAM_REMOTE/$UPSTREAM_BRANCH"

echo "→ Force-pushing to origin/$FORK_BRANCH..."
git push origin "$FORK_BRANCH" --force-with-lease

echo "✓ Done. $(git log --oneline "$UPSTREAM_REMOTE/$UPSTREAM_BRANCH"..HEAD | wc -l | tr -d ' ') fork commits now sit on top of upstream."
