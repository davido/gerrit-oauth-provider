#!/usr/bin/env bash
#
# Build every Gerrit OAuth plugin release artifact with Bazel and publish them
# to the GitHub release in one shot -- no hunting through bazel-bin for the
# right jars among the __non_stamped / _deploy / tests noise.
#
# Run from a Gerrit source tree that has this plugin linked in as
# plugins/oauth (see README "Build"):
#
#   scripts/publish.bash v3.15.0 ~/projects/gerrit3
#
# or from inside the Gerrit tree itself:
#
#   plugins/oauth/scripts/publish.bash v3.15.0
#
# Builds with JDK 21 by default (override with JDK=NN). Uploads, per release:
#   - oauth.jar             all-in-one (CORE + every provider EXCEPT sapias)
#   - oauth-<provider>.jar  one standalone artifact per provider
#
# sapias ships ONLY as the standalone oauth-sapias.jar: its SAP-specific deps
# are intentionally not bundled into the all-in-one oauth.jar.

set -euo pipefail

TAG="${1:?usage: publish.bash <tag> [gerrit-tree]}"
GERRIT_DIR="${2:-$PWD}"
REPO="davido/gerrit-oauth-provider"

# Standalone provider artifacts. Keep in sync with the provider list in
# BUILD.bazel, plus sapias (which only ships standalone).
PROVIDERS=(
  airvantage azure bitbucket cas dex discovery facebook
  github gitlab google keycloak phabricator sapias
)

cd "$GERRIT_DIR"
[[ -e plugins/oauth/BUILD.bazel ]] || {
  echo "No plugins/oauth in $GERRIT_DIR -- link the plugin in first (see README)." >&2
  exit 1
}

if [[ -f set-java.sh ]]; then
  set +u; . ./set-java.sh "${JDK:-21}"; set -u
fi
echo ">> Java toolchain:"; java -version

pkg="plugins/oauth/src/main/java/com/googlesource/gerrit/plugins/oauth"

# all-in-one jar + one standalone jar per provider, from a single source list
targets=( "//plugins/oauth:oauth" )
jars=( "bazel-bin/plugins/oauth/oauth.jar" )
for p in "${PROVIDERS[@]}"; do
  targets+=( "//$pkg/$p:oauth-$p" )
  jars+=( "bazel-bin/$pkg/$p/oauth-$p.jar" )
done

if (( "${BUILD:-1}" )); then
  echo ">> Building ${#targets[@]} artifacts ..."
  bazelisk build "${targets[@]}"
else
  echo ">> Skipping build (BUILD=0); using existing bazel-bin artifacts."
fi

echo ">> Verifying ${#jars[@]} artifacts exist ..."
missing=0
for j in "${jars[@]}"; do
  [[ -f "$j" ]] || { echo "   MISSING: $j" >&2; missing=1; }
done
(( missing == 0 )) || { echo "Aborting: build did not produce all artifacts." >&2; exit 1; }

echo ">> Publishing to release $TAG on $REPO ..."
if ! gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  sha="$(git -C plugins/oauth rev-parse --short HEAD)"
  gh release create "$TAG" --repo "$REPO" \
    --target "$(git -C plugins/oauth rev-parse HEAD)" \
    --title "OAuth plugin $TAG" \
    --notes "Built from synced googlesource/GitHub merge at ${sha}."
fi
gh release upload "$TAG" --repo "$REPO" --clobber "${jars[@]}"

echo ">> Done. Assets now on $TAG:"
gh release view "$TAG" --repo "$REPO" --json assets --jq '.assets[].name'
