#!/bin/sh
# Deterministic release flow: push dev, wait for Test, tag, wait for Release,
# verify the arm64 APK and the Feishu notify. It never force-pushes and never
# moves or deletes a tag. Policy: AGENTS.md "Release flow".
#
#   scripts/release.sh [--note "..."] [--agent-time 1h20m] [--agent-loops 2]
#   scripts/release.sh --check
#   scripts/release.sh --watch <tag>
set -e

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

REPO=wonder-missing/sing-box-for-android
BRANCH=dev
MODE=release
NOTE=""
AGENT_TIME=""
AGENT_LOOPS=""
WATCH_TAG=""

while [ $# -gt 0 ]; do
    case "$1" in
        --check)
            MODE=check
            ;;
        --watch)
            MODE=watch
            shift
            WATCH_TAG=${1:-}
            ;;
        --note)
            shift
            NOTE=${1:-}
            ;;
        --agent-time)
            shift
            AGENT_TIME=${1:-}
            ;;
        --agent-loops)
            shift
            AGENT_LOOPS=${1:-}
            ;;
        -h|--help)
            sed -n '2,10p' "$0"
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            exit 2
            ;;
    esac
    shift
done

command -v gh >/dev/null 2>&1 || { echo "gh not found" >&2; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "gh is not authenticated" >&2; exit 1; }

version_value() {
    sed -n "s/^$1=//p" version.properties | head -n 1
}

run_info() {
    gh run view "$1" -R "$REPO" --json status,conclusion \
        --jq '"\(.status) \(.conclusion)"' 2>/dev/null || true
}

wait_run() {
    id=$1
    label=$2
    limit=$3
    deadline=$(( $(date +%s) + limit ))
    last=""
    while :; do
        info=$(run_info "$id")
        if [ -n "$info" ]; then
            status=${info%% *}
            conclusion=${info#* }
            if [ "$status" = "completed" ]; then
                echo "$label: $conclusion"
                if [ "$conclusion" = "success" ]; then
                    return 0
                fi
                gh run view "$id" -R "$REPO" --json jobs \
                    --jq '.jobs[] | select(.conclusion=="failure") | .name' 2>/dev/null \
                    | sed 's/^/  failed job: /' || true
                echo "  run: https://github.com/$REPO/actions/runs/$id" >&2
                return 1
            fi
            if [ "$status" != "$last" ]; then
                echo "$label: $status"
                last=$status
            fi
        fi
        if [ "$(date +%s)" -ge "$deadline" ]; then
            echo "$label did not finish in time (run $id)" >&2
            return 1
        fi
        sleep 10
    done
}

wait_for_id() {
    finder=$1
    key=$2
    label=$3
    limit=$4
    deadline=$(( $(date +%s) + limit ))
    while :; do
        id=$($finder "$key")
        if [ -n "$id" ]; then
            echo "$id"
            return 0
        fi
        if [ "$(date +%s)" -ge "$deadline" ]; then
            echo "No $label run appeared within ${limit}s" >&2
            return 1
        fi
        sleep 5
    done
}

test_run_for() {
    gh run list -R "$REPO" -w Test -L 30 --json databaseId,headSha \
        --jq "[.[] | select(.headSha==\"$1\")][0].databaseId // empty" 2>/dev/null || true
}

release_run_for() {
    gh run list -R "$REPO" -w Release -L 20 --json databaseId,headBranch \
        --jq "[.[] | select(.headBranch==\"$1\")][0].databaseId // empty" 2>/dev/null || true
}

notify_run_for() {
    gh run list -R "$REPO" -w "Release notify" -L 10 --json databaseId,createdAt \
        --jq "[.[] | select(.createdAt >= \"$1\")][0].databaseId // empty" 2>/dev/null || true
}

print_asset() {
    tag=$1
    info=$(gh release view "$tag" -R "$REPO" --json assets \
        --jq '.assets[] | select(.name | endswith("-arm64-v8a.apk")) | [.name,.size,.digest,.url] | @tsv' 2>/dev/null || true)
    if [ -z "$info" ]; then
        echo "No arm64-v8a APK asset on release $tag" >&2
        return 1
    fi
    printf 'APK asset:\n%s\n' "$info"
}

watch_release() {
    tag=$1
    rel_id=$(wait_for_id release_run_for "$tag" "Release" 120)
    wait_run "$rel_id" "Release" 7200
    print_asset "$tag"
    created=$(gh run view "$rel_id" -R "$REPO" --json createdAt --jq .createdAt 2>/dev/null || true)
    notify_id=$(wait_for_id notify_run_for "$created" "Release notify" 120)
    wait_run "$notify_id" "Release notify" 600
    echo "release: https://github.com/$REPO/releases/tag/$tag"
}

if [ "$MODE" = "watch" ]; then
    [ -n "$WATCH_TAG" ] || { echo "--watch needs a tag" >&2; exit 2; }
    watch_release "$WATCH_TAG"
    exit 0
fi

[ -z "$(git status --porcelain)" ] || { echo "Working tree is not clean" >&2; exit 1; }
[ "$(git rev-parse --abbrev-ref HEAD)" = "$BRANCH" ] || { echo "Not on $BRANCH" >&2; exit 1; }
git fetch origin "$BRANCH" --quiet

LOCAL=$(git rev-parse HEAD)
REMOTE=$(git rev-parse "origin/$BRANCH")
NEED_PUSH=0
if [ "$LOCAL" != "$REMOTE" ]; then
    if git merge-base --is-ancestor "$REMOTE" "$LOCAL"; then
        NEED_PUSH=1
    else
        echo "HEAD is not a fast-forward of origin/$BRANCH" >&2
        exit 1
    fi
fi

CORE=$(version_value CORE_VERSION)
BUILD=$(version_value WONDER_BUILD)
[ -n "$CORE" ] && [ -n "$BUILD" ] || { echo "version.properties is incomplete" >&2; exit 1; }
TAG="v$CORE-wonder.$BUILD"

if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null 2>&1; then
    echo "Tag $TAG already exists locally" >&2
    exit 1
fi
if [ -n "$(git ls-remote --tags origin "refs/tags/$TAG" 2>/dev/null)" ]; then
    echo "Tag $TAG already exists on origin" >&2
    exit 1
fi

if [ "$MODE" = "check" ]; then
    echo "commit: $LOCAL"
    echo "tag:    $TAG"
    [ "$NEED_PUSH" = 1 ] && echo "push:   needed" || echo "push:   not needed"
    test_id=$(test_run_for "$LOCAL")
    if [ -n "$test_id" ]; then
        echo "test:   $test_id $(run_info "$test_id")"
    else
        echo "test:   no Test run for this commit yet"
    fi
    exit 0
fi

if [ "$NEED_PUSH" = 1 ]; then
    git push origin "$BRANCH"
fi
SHA=$(git rev-parse HEAD)
TEST_ID=$(wait_for_id test_run_for "$SHA" "Test" 120)
wait_run "$TEST_ID" "Test" 7200

PREV_TAG=$(git describe --tags --abbrev=0 --match 'v*-wonder.*' HEAD 2>/dev/null || true)
CHANGES=""
if [ -n "$PREV_TAG" ]; then
    CHANGES=$(git log --no-merges --pretty='- %s' "$PREV_TAG..HEAD" | head -40)
fi

MESSAGE="wonder.$BUILD"
if [ -n "$NOTE" ]; then
    MESSAGE="$MESSAGE

$NOTE"
fi
if [ -n "$CHANGES" ]; then
    MESSAGE="$MESSAGE
Changes since $PREV_TAG:
$CHANGES"
fi
if [ -n "$AGENT_TIME" ]; then
    MESSAGE="$MESSAGE
Agent-Time: $AGENT_TIME"
fi
if [ -n "$AGENT_LOOPS" ]; then
    MESSAGE="$MESSAGE
Agent-Loops: $AGENT_LOOPS"
fi

if [ -z "$CHANGES" ] && [ -z "$NOTE" ]; then
    echo "refusing to tag without release notes; pass --note" >&2
    exit 1
fi

git tag -a "$TAG" "$SHA" -m "$MESSAGE"
git push origin "$TAG"
echo "tagged $TAG at $SHA"

watch_release "$TAG"
