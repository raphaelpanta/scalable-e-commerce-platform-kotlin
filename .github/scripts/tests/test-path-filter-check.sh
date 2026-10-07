#!/usr/bin/env bash
# T108 (feature 004, user story 9): a change confined to one service triggers only that service's workflow, a change to
# shared code triggers every service workflow, and the workflows are wired as the pipeline requires. Static checks of
# .github/workflows/<ctx>.yml, service-ci.yml and platform.yml (grep based; YAML syntax is checked when ruby exists)
# plus the path-filter simulation of .github/scripts/path-filter-check.sh. No GitHub, Docker or act needed.
. "$(dirname "$0")/lib.sh"
W="$REPO_ROOT/.github/workflows"
SERVICES="gateway identity catalog cart order payment notification"
ALL_SEVEN="cart.yml catalog.yml gateway.yml identity.yml notification.yml order.yml payment.yml"
T_OUT="$(mktemp -d "${TMPDIR:-/tmp}/pathfiltertest.XXXXXX")"; T_TMPS="$T_OUT"

# check_triggers EXPECTED_FILES...  (set CHANGED first): the workflows triggered by CHANGED are exactly EXPECTED
check_triggers() {
  local expected="$1"; shift
  # shellcheck disable=SC2086  # CHANGED is a deliberate word list
  run_script path-filter-check.sh $CHANGED
  assert_exit 0 "path-filter-check $CHANGED"
  [ "$(tr '\n' ' ' <"$T_OUT/stdout" | sed 's/ $//')" = "$expected" ] ||
    _t_fail "change [$CHANGED] triggers [$(tr '\n' ' ' <"$T_OUT/stdout")], expected [$expected]"
}

# need FILE FIXED-STRING... MESSAGE: every string must occur in FILE (fixed-string match)
need() {
  local f="$1" msg="${*: -1}" i
  for ((i = 2; i < $#; i++)); do grep -qF -- "${!i}" "$f" || { _t_fail "$msg"; return; }; done
}

# --- path filters: the user-story-9 independent test (T108)
CHANGED="services/cart/domain/src/main/kotlin/com/ecommerce/cart/domain/Cart.kt"; check_triggers "cart.yml"
CHANGED="services/cart/infrastructure/build.gradle.kts"; check_triggers "cart.yml"
CHANGED="services/gateway/src/main/resources/application.yml"; check_triggers "gateway.yml"
CHANGED="services/notification/application/src/Foo.kt"; check_triggers "notification.yml"
CHANGED="libs/platform-core/src/main/kotlin/Foo.kt"; check_triggers "$ALL_SEVEN"
CHANGED="libs/platform-messaging/build.gradle.kts"; check_triggers "$ALL_SEVEN"
CHANGED="build-logic/src/main/kotlin/pact.gradle.kts"; check_triggers "$ALL_SEVEN"
CHANGED="gradle/libs.versions.toml"; check_triggers "$ALL_SEVEN"
CHANGED="platform/docker/Dockerfile"; check_triggers "$ALL_SEVEN platform.yml"
CHANGED="contracts/openapi/cart.yaml"; check_triggers "$ALL_SEVEN platform.yml"
CHANGED=".github/workflows/service-ci.yml"; check_triggers "$ALL_SEVEN"
CHANGED=".github/workflows/cart.yml"; check_triggers "cart.yml"
CHANGED="platform/compose/docker-compose.yml"; check_triggers "platform.yml"
CHANGED="acceptance/src/test/kotlin/Journey.kt"; check_triggers "platform.yml"
CHANGED="services/cart/domain/Foo.kt services/order/domain/Bar.kt"; check_triggers "cart.yml order.yml"
CHANGED="docs/ci-cd.md README.md"; check_triggers ""
# the storefront (feature 005, T099): its own workflow, plus platform.yml for the browser acceptance suite
CHANGED="frontend/src/ui/App.tsx"; check_triggers "platform.yml storefront.yml"
CHANGED="platform/docker/Dockerfile.storefront"; check_triggers "$ALL_SEVEN platform.yml storefront.yml"  # the services filter platform/docker/**
CHANGED="contracts/openapi/telemetry.yaml"; check_triggers "$ALL_SEVEN platform.yml storefront.yml"
CHANGED=".github/workflows/storefront.yml"; check_triggers "storefront.yml"

printf 'services/payment/domain/Foo.kt\ndocs/x.md\n' | "$SCRIPTS_DIR/path-filter-check.sh" - >"$T_OUT/stdout" 2>"$T_OUT/stderr"
RC=$?; assert_exit 0 "stdin"
[ "$(cat "$T_OUT/stdout")" = "payment.yml" ] || _t_fail "stdin: expected payment.yml, got $(cat "$T_OUT/stdout")"
run_script path-filter-check.sh; assert_exit 64 "no files is a usage error"

# --- the per-service callers
for s in $SERVICES; do
  f="$W/$s.yml"
  [ -f "$f" ] || { _t_fail "missing $f"; continue; }
  grep -q "^name: $s\$" "$f" || _t_fail "$s.yml: workflow name must be $s"
  grep -q 'uses: \./\.github/workflows/service-ci\.yml' "$f" || _t_fail "$s.yml must call service-ci.yml"
  grep -q "^      service: $s\$" "$f" || _t_fail "$s.yml must pass service: $s"
  grep -q 'secrets: inherit' "$f" || _t_fail "$s.yml must inherit the secrets"
  grep -q 'github.event.pull_request.head.repo.full_name == github.repository' "$f" || _t_fail "$s.yml: fork guard missing"
  grep -v '^[[:space:]]*#' "$f" | grep -q 'pull_request_target' && _t_fail "$s.yml: pull_request_target must never be used"
  need "$f" '  push:' '    branches: [main]' "$s.yml must trigger on push to main"
  grep -q '^  pull_request:' "$f" || _t_fail "$s.yml must trigger on pull_request"
  # T130: a Pact Broker webhook dispatches the provider's pipeline with a reason (and the pact to verify)
  need "$f" '  workflow_dispatch:' '      reason:' '      pact-url:' '      consumer:' "$s.yml must accept workflow_dispatch with reason, pact-url and consumer"
  # shellcheck disable=SC2016  # literal workflow expressions
  need "$f" 'reason: ${{ inputs.reason || github.event_name }}' 'pact-url: ${{ inputs.pact-url' "$s.yml must pass the dispatch inputs to service-ci"
  [ "$(grep -c "      - 'services/$s/\*\*'" "$f")" = 2 ] || _t_fail "$s.yml: services/$s/** must filter push and pull_request"
  for p in 'libs/**' 'build-logic/**' 'gradle/**' 'contracts/**' 'platform/docker/**' \
    '.github/workflows/service-ci.yml' ".github/workflows/$s.yml"; do
    [ "$(grep -cF "      - '$p'" "$f")" = 2 ] || _t_fail "$s.yml: path filter $p missing on push or pull_request"
  done
  for other in $SERVICES; do
    [ "$other" = "$s" ] || ! grep -q "services/$other/" "$f" || _t_fail "$s.yml must not filter on services/$other"
  done
  awk '/^permissions:/{f=1;next} f&&/^[^ ]/{f=0} f&&NF{print}' "$f" | sed 's/^ *//' >"$T_OUT/perm"
  [ "$(cat "$T_OUT/perm")" = "contents: read" ] || _t_fail "$s.yml: permissions must be exactly contents: read"
done

# --- the storefront workflow (T099): same safeguards as the service callers, in one file
F="$W/storefront.yml"
[ -f "$F" ] || _t_fail "missing $F"
grep -q '^name: storefront$' "$F" || _t_fail "storefront.yml: workflow name must be storefront"
need "$F" '  push:' '    branches: [main]' '  workflow_dispatch:' "storefront.yml must trigger on push to main and workflow_dispatch"
grep -q '^  pull_request:' "$F" || _t_fail "storefront.yml must trigger on pull_request"
grep -v '^[[:space:]]*#' "$F" | grep -q 'pull_request_target' && _t_fail "storefront.yml: pull_request_target must never be used"
for p in 'frontend/**' 'platform/docker/Dockerfile.storefront' 'platform/docker/storefront/**' \
  'contracts/openapi/gateway-browser-session.yaml' 'contracts/openapi/telemetry.yaml' \
  'specs/005-storefront-dev-bootstrap/contracts/openapi/**' '.github/workflows/storefront.yml'; do
  [ "$(grep -cF "      - '$p'" "$F")" = 2 ] || _t_fail "storefront.yml: path filter $p missing on push or pull_request"
done
grep -hE '^[[:space:]]*(-[[:space:]]+)?uses:' "$F" | grep -vqE '[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40} # v[0-9]+\.[0-9]+\.[0-9]+$' &&
  _t_fail "storefront.yml: every uses: must be pinned to a full SHA with a trailing # vX.Y.Z comment"
grep -q 'runs-on: \[self-hosted, linux, ecommerce\]' "$F" || _t_fail "storefront.yml: self-hosted runner labels missing"
grep -q 'github.event.pull_request.head.repo.full_name == github.repository' "$F" || _t_fail "storefront.yml: fork guard missing"
awk '/^permissions:/{f=1;next} f&&/^[^ ]/{f=0} f&&NF{print}' "$F" | sed 's/^ *//' >"$T_OUT/perm"
[ "$(cat "$T_OUT/perm")" = "contents: read" ] || _t_fail "storefront.yml: permissions must be exactly contents: read"
need "$F" 'npm ci --silent --no-audit --no-fund' 'npm run lint' 'npm run test' 'npm run mutate' 'npm run pact' \
  "storefront.yml: the gate must install, lint, test, mutate and run the pact tests"
# shellcheck disable=SC2016  # literal workflow text
need "$F" 'build/pacts/storefront-*.json' '--consumer-app-version "$GITHUB_SHA"' '--pacticipant storefront' \
  "storefront.yml: publish the storefront pacts and ask can-i-deploy for the pacticipant storefront"
need "$F" 'osv-scanner' 'frontend:/src:ro' '/src/package-lock.json' "storefront.yml: osv-scanner over frontend/package-lock.json"
need "$F" 'platform/docker/Dockerfile.storefront' '--exit-code 1' '--severity CRITICAL' 'cyclonedx-json' \
  '.github/scripts/image-health.sh storefront' "storefront.yml: image build, Trivy (CRITICAL), SBOM and start-and-health"
awk '/^  image:/{f=1} f&&/^    needs:/{print; exit}' "$F" | grep -q 'needs: gate$' || _t_fail "storefront.yml: image must need gate"
awk '/^  publish:/{f=1} f&&/^    needs:/{print; exit}' "$F" | grep -q 'needs: \[gate, image\]$' || _t_fail "storefront.yml: publish must need gate and image"
grep -q -- '--password-stdin' "$F" || _t_fail "storefront.yml must docker login with --password-stdin"
grep -q '^    name: service-ci / storefront$' "$F" || _t_fail "storefront.yml: the aggregate job must be named service-ci / storefront"
grep -q 'needs: \[gate, image, publish\]' "$F" || _t_fail "storefront.yml: the aggregate job must need gate, image and publish"
# every tool image is pinned exactly as in service-ci.yml
for v in PACT_CLI_IMAGE OSV_SCANNER_IMAGE TRIVY_IMAGE SYFT_IMAGE; do
  [ "$(grep -E "^  $v:" "$F")" = "$(grep -E "^  $v:" "$W/service-ci.yml")" ] || _t_fail "storefront.yml: $v differs from service-ci.yml"
done

# --- service-ci.yml and platform.yml
S="$W/service-ci.yml"; P="$W/platform.yml"
for f in "$S" "$P"; do
  [ -f "$f" ] || { _t_fail "missing $f"; continue; }
  grep -hE '^[[:space:]]*(-[[:space:]]+)?uses:' "$f" | sed -E 's/.*uses:[[:space:]]*//; s/[[:space:]].*//' |
    while read -r u; do echo "$u" | grep -Eq '^[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40}$' || echo "$u"; done >"$T_OUT/unpinned"
  [ ! -s "$T_OUT/unpinned" ] || _t_fail "$(basename "$f"): unpinned actions: $(tr '\n' ' ' <"$T_OUT/unpinned")"
  grep -hE '^[[:space:]]*(-[[:space:]]+)?uses:' "$f" | grep -vqE '@[0-9a-f]{40} # v[0-9]+\.[0-9]+\.[0-9]+$' &&
    _t_fail "$(basename "$f"): every uses: needs a trailing # vX.Y.Z comment"
  grep -v '^[[:space:]]*#' "$f" | grep -q 'pull_request_target' && _t_fail "$(basename "$f"): pull_request_target must never be used"
  grep -q 'runs-on: \[self-hosted, linux, ecommerce\]' "$f" || _t_fail "$(basename "$f"): self-hosted runner labels missing"
  grep -q 'github.event.pull_request.head.repo.full_name == github.repository' "$f" || _t_fail "$(basename "$f"): fork guard missing"
  awk '/^permissions:/{f=1;next} f&&/^[^ ]/{f=0} f&&NF{print}' "$f" | sed 's/^ *//' >"$T_OUT/perm"
  [ "$(cat "$T_OUT/perm")" = "contents: read" ] || _t_fail "$(basename "$f"): permissions must be exactly contents: read"
  grep -q 'persist-credentials: false' "$f" || _t_fail "$(basename "$f"): checkout must not persist credentials"
  grep -q '^concurrency:' "$f" || _t_fail "$(basename "$f"): concurrency group missing"
  grep -v '^[[:space:]]*#' "$P" | grep -q 'secrets\.' && _t_fail "platform.yml must not use secrets"
done
grep -q '^  workflow_call:' "$S" || _t_fail "service-ci.yml must be reusable (workflow_call)"
grep -q 'publish-image:' "$S" || _t_fail "service-ci.yml needs the publish-image input"
[ "$(grep -c 'timeout-minutes: 15' "$S")" -ge 2 ] || _t_fail "service-ci.yml: gate and image need timeout-minutes: 15"
# shellcheck disable=SC2016  # literal workflow expression
grep -qF 'name: ${{ inputs.service }}' "$S" || _t_fail "service-ci.yml: the aggregate job must be named after the service"
grep -q 'needs: \[gate, image, publish\]' "$S" || _t_fail "service-ci.yml: the aggregate job must need gate, image and publish"
# T149/T150: gate and jar run in parallel, image needs only jar, publish needs gate AND image, the image is started
grep -q '^  jar:' "$S" || _t_fail "service-ci.yml: the jar job is missing"
awk '/^  image:/{f=1} f&&/^    needs:/{print; exit}' "$S" | grep -q 'needs: jar$' || _t_fail "service-ci.yml: image must need only jar (it runs in parallel with gate)"
awk '/^  publish:/{f=1} f&&/^    needs:/{print; exit}' "$S" | grep -q 'needs: \[gate, image\]$' || _t_fail "service-ci.yml: publish must need gate and image"
grep -q 'APP_JAR=ci-jar/app.jar' "$S" || _t_fail "service-ci.yml: the image build must reuse the jar (APP_JAR)"
grep -q 'image-health.sh' "$S" || _t_fail "service-ci.yml: the image job must start the image and wait for its health (image-health.sh)"
grep -q 'platform/docker/Dockerfile' "$S" || _t_fail "service-ci.yml must build platform/docker/Dockerfile"
grep -q 'BUILDAH_FORMAT: docker' "$S" || _t_fail "service-ci.yml must set BUILDAH_FORMAT=docker"
grep -q -- '--password-stdin' "$S" || _t_fail "service-ci.yml must docker login with --password-stdin"
grep -q 'can-i-deploy' "$S" || _t_fail "service-ci.yml must run can-i-deploy"
# T130: can-i-deploy waits for the verification a webhook-dispatched provider run publishes; the broker verification
# gets the provider branch and the pact a webhook named; only the service's own pacts are published
# shellcheck disable=SC2016  # literal workflow text
need "$S" '--retry-while-unknown "$RETRIES"' 'PACT_CAN_I_DEPLOY_RETRIES' "service-ci.yml: can-i-deploy must retry while unknown"
# shellcheck disable=SC2016  # literal workflow text
need "$S" 'PACT_PROVIDER_BRANCH:' 'PACT_URL: ${{ inputs.pact-url }}' 'PACT_CONSUMER: ${{ inputs.consumer }}' \
  "service-ci.yml: provider verification needs the branch and the dispatched pact"
# shellcheck disable=SC2016  # literal workflow text
need "$S" 'build/pacts/"$SERVICE"-*.json' 'platform-probe-"$SERVICE".json' '--build-url' \
  "service-ci.yml: publish only the service's own pacts"
need "$S" 'trivy' 'osv-scanner' 'syft' "service-ci.yml must scan (trivy, osv-scanner) and produce an SBOM (syft)"
need "$S" '--exit-code 1' '--severity CRITICAL' "service-ci.yml: Trivy must fail on CRITICAL"
# shellcheck disable=SC2016  # literal shell text of the workflow
need "$S" ':services:$SERVICE:domain:check :services:$SERVICE:application:check :services:$SERVICE:infrastructure:check' \
  "service-ci.yml must check the three modules of a service"
grep -q ':services:gateway:check' "$S" || _t_fail "service-ci.yml must check :services:gateway"
# T152: pull requests are path-filtered; every push to main runs (paths-ignore for documentation only, so that the
# path-filter simulation, which reads every `paths:` list, stays the pull_request one); nightly and manual runs exist
need "$P" '  push:' '    branches: [main]' '  pull_request:' '  schedule:' '  workflow_dispatch:' "platform.yml: push to main, pull_request, schedule and manual triggers"
for p in 'platform/**' 'acceptance/**' 'contracts/**' '.github/workflows/platform.yml'; do
  [ "$(grep -cF "      - '$p'" "$P")" = 1 ] || _t_fail "platform.yml: path filter $p must be on pull_request only"
done
grep -q '^    paths-ignore:' "$P" || _t_fail "platform.yml: the push trigger needs paths-ignore (documentation only), not a paths list"
grep -q '^  platform:' "$P" || _t_fail "platform.yml: the job named platform is the check to keep"
grep -q '^  acceptance-slow:' "$P" || _t_fail "platform.yml: the slow and chaos suites need their own job"
[ "$(grep -c 'timeout-minutes: 45' "$P")" = 1 ] && [ "$(grep -c 'timeout-minutes: 75' "$P")" = 1 ] ||
  _t_fail "platform.yml: platform needs timeout-minutes: 45 and acceptance-slow 75"
need "$P" 'compose.perf.yml' 'GATEWAY_PORT:' 'GATEWAY_URL:' "platform.yml: rate-limit override and gateway port/URL"
need "$P" 'cucumber.filter.tags="not @slow and not @chaos"' 'cucumber.filter.tags="@slow and not @chaos"' 'cucumber.filter.tags="@chaos"' \
  "platform.yml: the acceptance suite must be split by tags"
need "$P" 'smoke.sh --no-build --keep' ':acceptance:test' 'down -v' "platform.yml: smoke, acceptance and teardown steps"
# shellcheck disable=SC2016  # literal workflow expression
need "$P" 'npm ci --prefix frontend' 'npx playwright install' 'npm --prefix frontend run acceptance' 'STOREFRONT_URL: ${{ env.GATEWAY_URL }}' \
  'MAILPIT_URL:' 'STOREFRONT_VIEWPORT: mobile' 'CUCUMBER_TAGS: "@us1"' "platform.yml: the storefront acceptance steps (desktop and the 360 px @us1 run)"
[ "$(grep -cF "      - 'frontend/**'" "$P")" = 1 ] || _t_fail "platform.yml: path filter frontend/** must be on pull_request only"
grep -A1 'name: Tear down the stack' "$P" | grep -q 'if: always()' || _t_fail "platform.yml: teardown must run always"

if command -v ruby >/dev/null 2>&1; then
  for f in "$W"/*.yml; do ruby -ryaml -e 'YAML.load_file(ARGV[0])' "$f" 2>/dev/null || _t_fail "invalid YAML: $f"; done
fi
test_done
