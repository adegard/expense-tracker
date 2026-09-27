#!/bin/sh
# Publish ExpenseTracker to a new GitHub repo, with the debug APK as a release asset.
#
# Run it from Termux on the device (that is where git, the JDK and the network are):
#     sh release.sh                 # first release
#     sh release.sh "your message"  # with a custom commit message
#
# Needs: git, and gh for the release step  ->  pkg install git gh
#         then authenticate once:           gh auth login
set -e
cd "$(dirname "$0")"

# ---------------------------------------------------------------- settings
REPO="${REPO:-expense-tracker}"   # name of the new GitHub repository
VISIBILITY="${VISIBILITY:-public}" # public | private
MSG="${1:-Expense Tracker: CSV import, statistics and automatic categories}"

VERSION=$(sed -n 's/.*versionName *= *"\([^"]*\)".*/\1/p' app/build.gradle.kts)
[ -n "$VERSION" ] || VERSION="1.0"
APK=app/build/outputs/apk/debug/app-debug.apk

command -v git >/dev/null 2>&1 || { echo "git missing: pkg install git" >&2; exit 1; }
command -v gh  >/dev/null 2>&1 || echo "WARNING: gh not installed, the release step is skipped (pkg install gh)" >&2

# ---------------------------------------------------------------- build
# always rebuild: publishing an APK that is older than the sources would ship the
# wrong features
echo "--- building"
sh build.sh
[ -f "$APK" ] || { echo "No APK at $APK - see build-errors.txt" >&2; exit 1; }
echo "APK: $APK ($(wc -c < "$APK") bytes)"

# ---------------------------------------------------------------- commit
# the project lives on shared storage, whose owner is not the shell user: git
# refuses to touch the repo until this directory is declared safe
if ! git config --global --get-all safe.directory 2>/dev/null | grep -qx "$PWD"; then
  git config --global --add safe.directory "$PWD"
  echo "--- registered $PWD as a safe git directory"
fi

if [ ! -d .git ]; then
  git init -q
fi
# a repository initialised by an earlier run may still be on master
git branch -M main
git config user.name >/dev/null 2>&1 || git config user.name "Expense Tracker"
git config user.email >/dev/null 2>&1 || git config user.email "noreply@users.noreply.github.com"

git add -A
if git diff --cached --quiet; then
  echo "--- nothing to commit"
else
  git commit -q -m "$MSG"
fi

# ---------------------------------------------------------------- remote + push
if gh auth status >/dev/null 2>&1; then
  USER=$(gh api user --jq .login)
  SLUG="${REPO_SLUG:-$USER/$REPO}"
  if git remote get-url origin >/dev/null 2>&1; then
    echo "--- origin already set: $(git remote get-url origin)"
  else
    if ! gh repo view "$SLUG" >/dev/null 2>&1; then
      # it does not exist yet: ask gh to create it
      if ! gh repo create "$SLUG" --"$VISIBILITY" \
           --description "Android expense tracker with Italian bank CSV import" >/dev/null 2>&1; then
        echo >&2
        echo "The current GITHUB_TOKEN cannot create repositories." >&2
        echo "Pick one:" >&2
        echo "  a) open https://github.com/new  ->  name '$REPO', $VISIBILITY, no README," >&2
        echo "     then run this script again" >&2
        echo "  b) unset GITHUB_TOKEN && gh auth login   (a token allowed to create repos)" >&2
        echo "  c) point the script at a repo that already exists:" >&2
        echo "     REPO_SLUG=$SLUG sh release.sh" >&2
        exit 1
      fi
      echo "--- created github.com/$SLUG ($VISIBILITY)"
    else
      echo "--- github.com/$SLUG already exists"
    fi
    git remote add origin "https://github.com/$SLUG.git"
  fi
  git push -u origin main

  # ---------------------------------------------------------------- release
  if command -v gh >/dev/null 2>&1; then
    TAG="v$VERSION"
    git tag -f "$TAG" >/dev/null
    git push -f origin "$TAG" >/dev/null
    if gh release view "$TAG" --repo "$SLUG" >/dev/null 2>&1; then
      echo "--- release $TAG exists, asset skipped"
    else
      gh release create "$TAG" --repo "$SLUG" \
        --title "Expense Tracker $TAG" \
        --notes "APK allegato. Install: scarica, abilita 'fonti sconosciute', apri e installa.
Min Android 7.0 (API 24). Nessun permesso richiesto." \
        "$APK#expense-tracker-$TAG.apk"
      echo "--- release $TAG published with the APK"
    fi
    echo
    echo "Repo:    https://github.com/$SLUG"
    echo "Release: https://github.com/$SLUG/releases/tag/$TAG"
  fi
else
  echo
  echo "gh is not authenticated - finish manually:"
  echo "  1. create an empty repository '$REPO' on github.com"
  echo "  2. git remote add origin https://github.com/<user>/$REPO.git"
  echo "  3. git push -u origin main"
  echo "  4. gh auth login   (or upload $APK on the repo's Releases page)"
fi
