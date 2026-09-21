#!/usr/bin/env bash
# Commit and push the working tree safely.
#
#   ./scripts/git-push.sh "commit message"            # push current branch
#   ./scripts/git-push.sh "commit message" my-branch  # switch/create branch first
#
# Refuses to commit secrets (deploy/compose/secrets/*, .env, keys, Vault init JSON).
set -euo pipefail

msg="${1:-}"
branch="${2:-}"
[ -n "$msg" ] || { echo "usage: $0 \"commit message\" [branch]" >&2; exit 2; }

cd "$(git rev-parse --show-toplevel)"

if [ -n "$branch" ] && [ "$(git branch --show-current)" != "$branch" ]; then
  if git show-ref --verify --quiet "refs/heads/$branch"; then
    git switch "$branch"
  else
    git switch -c "$branch"
  fi
fi
branch="$(git branch --show-current)"

if [ "$branch" = "main" ] && [ "${ALLOW_MAIN:-0}" != "1" ]; then
  echo "Refusing to push directly to main. Pass a branch name, or set ALLOW_MAIN=1." >&2
  exit 1
fi

git add -A

# Secret guard: anything matching these patterns aborts the commit.
blocked='^deploy/compose/secrets/[^/]*$|(^|/)\.env$|\.pem$|\.key$|id_rsa|id_ed25519|vault-dev-init\.json$'
bad="$(git diff --cached --name-only | grep -E "$blocked" | grep -v '/\.gitkeep$' || true)"
if [ -n "$bad" ]; then
  echo "ABORT: these staged files look like secrets:" >&2
  echo "$bad" >&2
  git reset -q
  exit 1
fi

if git diff --cached --quiet; then
  echo "Nothing to commit."
else
  git commit -m "$msg"
fi

git push -u origin "$branch"

remote="$(git remote get-url origin | sed -E 's#^git@github.com:#https://github.com/#; s#\.git$##')"
echo
echo "Pushed '$branch'. Open a PR (CI runs on it): $remote/compare/main...$branch?expand=1"
