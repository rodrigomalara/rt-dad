#!/usr/bin/env bash
#
# RT-DAD — GitHub repo bootstrap (idempotent)
# Automates docs/runbooks/github-bootstrap-checklist.md via `gh`.
#
# Prereqs:
#   - gh CLI authenticated with admin on the repo:  gh auth login
#   - jq + terraform installed; the env(s) in ENVIRONMENTS applied.
#
# Usage — no arguments; static inputs come from scripts/.env, calculated
# values (role ARNs, cluster names) are read from `terraform output`:
#   ./scripts/github-bootstrap.sh
#
# Dry run — print every mutating gh call (and its payload) without executing.
# Read-only calls (auth, user id, terraform output) still run so the plan is
# real. Requires gh auth + terraform state, but changes nothing:
#   DRY_RUN=1 ./scripts/github-bootstrap.sh
#
# Everything is safe to re-run; PUT endpoints are idempotent, `gh variable set`
# upserts. No secrets are created — OIDC removes static AWS keys.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Shared static config (region, account id, repo, bucket). Same file the AWS
# bootstrap script sources. Calculated values (role ARNs, cluster names) are
# passed in the environment at run time, not stored here.
if [ -f "${SCRIPT_DIR}/.env" ]; then
  set -a; . "${SCRIPT_DIR}/.env"; set +a
fi

REPO="${REPO:-rodrigomalara/rt-dad}"

# --- Static inputs (from .env; fail fast if unset) -------------------------
: "${AWS_REGION:?set AWS_REGION (scripts/.env)}"
: "${ACCOUNT_ID:?set ACCOUNT_ID (scripts/.env)}"
# Source CIDRs allowed to reach NodePorts / admin surfaces. Published verbatim
# as the WHITELIST_CIDRS Actions Variable and injected UNQUOTED into staging.tfvars,
# so it must be a valid HCL list literal, e.g. '["x.x.x.x/32"]'.
: "${WHITELIST_CIDRS:?set WHITELIST_CIDRS (scripts/.env; HCL list, e.g. [\"x.x.x.x/32\"])}"

# Environments to bootstrap. Each must have a terraform env dir at
# ${INFRA_DIR}/<env>. Override in .env, e.g. ENVIRONMENTS="staging production".
INFRA_DIR="${INFRA_DIR:-${SCRIPT_DIR}/../infra/envs}"
read -r -a ENVIRONMENTS <<< "${ENVIRONMENTS:-staging}"

# Status-check contexts required on main. These are GitHub Actions *job* names.
# infra.yml → job "plan". Add the app CI job name once that workflow lands.
# Override with e.g. STATUS_CHECKS='plan,CI / build'
IFS=',' read -r -a STATUS_CHECKS <<< "${STATUS_CHECKS:-plan,build-test}"

# All calculated values (bucket, role ARNs, cluster names) are DERIVED here,
# never stored. Bucket from account id; the rest from `terraform output`.
TF_STATE_BUCKET="${TF_STATE_BUCKET:-rt-dad-tfstate-${ACCOUNT_ID}}"

# GitHub Actions OIDC provider ARN — env-independent, one per account. The URL
# host is fixed, so derive from ACCOUNT_ID; override if the provider was created
# under a different path.
OIDC_PROVIDER_ARN="${OIDC_PROVIDER_ARN:-arn:aws:iam::${ACCOUNT_ID}:oidc-provider/token.actions.githubusercontent.com}"

# run <cmd...> — execute a MUTATING command, or just print it under DRY_RUN=1.
# Captures piped/heredoc stdin so payloads are shown (dry) or forwarded (live).
run() {
  local stdin=""
  [ -t 0 ] || stdin="$(cat)"
  if [ -n "${DRY_RUN:-}" ]; then
    printf '   [dry-run] %s\n' "$*" >&2
    [ -n "$stdin" ] && printf '%s\n' "$stdin" | sed 's/^/             > /' >&2
    return 0
  fi
  if [ -n "$stdin" ]; then printf '%s' "$stdin" | "$@"; else "$@"; fi
}

# tf_out <env> <output-name> — read a terraform output for an env dir.
tf_out() {
  local env="$1" name="$2" dir="${INFRA_DIR}/$1"
  [ -d "$dir" ] || { echo "!! no terraform env dir: $dir" >&2; return 1; }
  terraform -chdir="$dir" output -raw "$name" 2>/dev/null \
    || { echo "!! terraform output '$name' missing in $dir (apply first?)" >&2; return 1; }
}

# PLAN_ROLE_ARN is repo-level (read-only, env-independent). Take it from the
# first environment; override by exporting PLAN_ROLE_ARN before the run.
PLAN_ROLE_ARN="${PLAN_ROLE_ARN:-$(tf_out "${ENVIRONMENTS[0]}" ci_plan_role_arn)}"

# PUBLISH_ROLE_ARN is repo-level too (trusts main + v* tags, env-independent).
PUBLISH_ROLE_ARN="${PUBLISH_ROLE_ARN:-$(tf_out "${ENVIRONMENTS[0]}" ci_publish_role_arn)}"

echo ">> Bootstrapping ${REPO}${DRY_RUN:+  (DRY RUN — no changes will be made)}"
gh auth status >/dev/null

# ---------------------------------------------------------------------------
# Step 1 — Actions: default workflow token read-only; fork PR approval
# ---------------------------------------------------------------------------
echo ">> Step 1: Actions permissions"
run gh api -X PUT "repos/${REPO}/actions/permissions/workflow" \
  -f default_workflow_permissions=read \
  -F can_approve_pull_request_reviews=false >/dev/null

# Require approval for first-time contributors' fork PR runs.
run gh api -X PUT "repos/${REPO}/actions/permissions/fork-pr-contributor-approval" \
  -f approval_policy=first_time_contributors >/dev/null 2>&1 \
  || echo "   (fork-pr approval endpoint not available on this plan; set in UI)"

# ---------------------------------------------------------------------------
# Step 2 — Branch protection on main
# ---------------------------------------------------------------------------
echo ">> Step 2: Branch protection on main (checks: ${STATUS_CHECKS[*]})"
checks_json=$(printf '%s\n' "${STATUS_CHECKS[@]}" \
  | jq -R '{context: .}' | jq -s '.')

jq -n --argjson checks "$checks_json" '{
  required_status_checks:       { strict: true, checks: $checks },
  enforce_admins:               true,
  required_pull_request_reviews:null,
  required_linear_history:      true,
  restrictions:                 null,
  allow_force_pushes:           false,
  allow_deletions:              false
}' | run gh api -X PUT "repos/${REPO}/branches/main/protection" \
       -H "Accept: application/vnd.github+json" --input - >/dev/null

# ---------------------------------------------------------------------------
# Step 3 — Environments  +  Step 4 (env-scoped) Variables
#   production gets the authenticated user as required reviewer; others don't.
#   Deploy role ARN + cluster name are derived per env via terraform output.
# ---------------------------------------------------------------------------
MY_ID=$(gh api user --jq '.id')
for env in "${ENVIRONMENTS[@]}"; do
  echo ">> Step 3/4: environment '${env}'"

  if [ "$env" = "production" ]; then
    reviewers="[ { \"type\": \"User\", \"id\": ${MY_ID} } ]"
  else
    reviewers="[]"
  fi
  run gh api -X PUT "repos/${REPO}/environments/${env}" --input - >/dev/null <<JSON
{
  "reviewers": ${reviewers},
  "deployment_branch_policy": { "protected_branches": true, "custom_branch_policies": false }
}
JSON

  deploy_arn="$(tf_out "$env" ci_deploy_role_arn)"
  cluster="$(tf_out "$env" cluster_name)"
  run gh variable set DEPLOY_ROLE_ARN  --repo "$REPO" --env "$env" --body "$deploy_arn"
  run gh variable set EKS_CLUSTER_NAME --repo "$REPO" --env "$env" --body "$cluster"
done

# ---------------------------------------------------------------------------
# Step 4 — Repo-level Actions Variables (no secrets)
# ---------------------------------------------------------------------------
echo ">> Step 4: repo-level Variables"
run gh variable set AWS_REGION        --repo "$REPO" --body "$AWS_REGION"
run gh variable set TF_STATE_BUCKET   --repo "$REPO" --body "$TF_STATE_BUCKET"
run gh variable set PLAN_ROLE_ARN     --repo "$REPO" --body "$PLAN_ROLE_ARN"
run gh variable set PUBLISH_ROLE_ARN  --repo "$REPO" --body "$PUBLISH_ROLE_ARN"
run gh variable set WHITELIST_CIDRS   --repo "$REPO" --body "$WHITELIST_CIDRS"
run gh variable set OIDC_PROVIDER_ARN --repo "$REPO" --body "$OIDC_PROVIDER_ARN"

# ---------------------------------------------------------------------------
# Step 5 — Packages: nothing to pre-create (published via GITHUB_TOKEN).
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# Step 6 — Tag protection ruleset (v* tags)
# ---------------------------------------------------------------------------
echo ">> Step 6: Tag protection ruleset for v*"
existing=$(gh api "repos/${REPO}/rulesets" --jq '.[] | select(.name=="protect-release-tags") | .id' 2>/dev/null || true)
ruleset_payload=$(cat <<'JSON'
{
  "name": "protect-release-tags",
  "target": "tag",
  "enforcement": "active",
  "conditions": { "ref_name": { "include": ["refs/tags/v*"], "exclude": [] } },
  "rules": [ { "type": "deletion" }, { "type": "non_fast_forward" } ]
}
JSON
)
if [ -n "$existing" ]; then
  echo "$ruleset_payload" | run gh api -X PUT "repos/${REPO}/rulesets/${existing}" --input - >/dev/null
else
  echo "$ruleset_payload" | run gh api -X POST "repos/${REPO}/rulesets" --input - >/dev/null
fi

echo ">> Done${DRY_RUN:+ (dry run — nothing changed)}."
[ -z "${DRY_RUN:-}" ] && echo "   Verify: gh api repos/${REPO}/branches/main/protection --jq '.required_status_checks'"
exit 0
