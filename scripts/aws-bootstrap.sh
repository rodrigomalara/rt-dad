#!/usr/bin/env bash
#
# RT-DAD — AWS account bootstrap (idempotent)
# Automates docs/runbooks/2026-07-11-aws-bootstrap-checklist.md via the AWS CLI.
#
# Covers the one-time manual prerequisites for the first local `terraform apply`:
#   Step 1 — S3 state bucket (versioned, encrypted, public access blocked)
#   Step 2 — state locking (no-op for Terraform >= 1.10; S3-native lockfile)
#   Step 3 — IAM OIDC identity provider (account-wide singleton)
# Step 4 (dev admin creds) is SSO/manual by design — this script only VERIFIES
# an admin identity is active; it never creates or modifies credentials.
#
# Prereqs:
#   - AWS CLI v2 authenticated with admin (`aws sts get-caller-identity` works).
#   - Static inputs in scripts/.env (AWS_REGION, ACCOUNT_ID, TF_STATE_BUCKET).
#
# Usage — no arguments; static inputs come from scripts/.env:
#   ./scripts/aws-bootstrap.sh
#
# Everything is safe to re-run: bucket / OIDC creation is guarded by existence
# checks, and the sub-config PUTs are idempotent.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Shared static config (region, account id, bucket). Same file the GitHub
# bootstrap script sources — single source of truth for the bucket name.
if [ -f "${SCRIPT_DIR}/.env" ]; then
  set -a; . "${SCRIPT_DIR}/.env"; set +a
fi

# --- Static inputs (from .env; fail fast if unset) -------------------------
: "${AWS_REGION:?set AWS_REGION (scripts/.env)}"
: "${ACCOUNT_ID:?set ACCOUNT_ID (scripts/.env)}"
: "${TF_STATE_BUCKET:?set TF_STATE_BUCKET (scripts/.env)}"

REGION="$AWS_REGION"
BUCKET="$TF_STATE_BUCKET"
OIDC_URL="token.actions.githubusercontent.com"
OIDC_ARN="arn:aws:iam::${ACCOUNT_ID}:oidc-provider/${OIDC_URL}"

echo ">> Bootstrapping AWS account ${ACCOUNT_ID} (${REGION})"

# ---------------------------------------------------------------------------
# Step 4 (preflight only) — verify an admin identity is active. No creds are
# created here; the hybrid model keeps dev-admin manual (SSO preferred).
# ---------------------------------------------------------------------------
echo ">> Preflight: caller identity"
caller="$(aws sts get-caller-identity --query Account --output text)"
if [ "$caller" != "$ACCOUNT_ID" ]; then
  echo "!! active account ${caller} != .env ACCOUNT_ID ${ACCOUNT_ID}" >&2
  echo "   (aws sts get-caller-identity must return the target account)" >&2
  exit 1
fi
echo "   ok — $(aws sts get-caller-identity --query Arn --output text)"

# ---------------------------------------------------------------------------
# Step 1 — S3 bucket for Terraform state
# ---------------------------------------------------------------------------
echo ">> Step 1: S3 state bucket ${BUCKET}"
if aws s3api head-bucket --bucket "$BUCKET" 2>/dev/null; then
  echo "   bucket exists — ensuring versioning/encryption/public-access-block"
else
  # us-west-2 (every region except us-east-1) REQUIRES LocationConstraint.
  aws s3api create-bucket --bucket "$BUCKET" --region "$REGION" \
    --create-bucket-configuration LocationConstraint="$REGION" >/dev/null
  echo "   created"
fi

aws s3api put-bucket-versioning --bucket "$BUCKET" \
  --versioning-configuration Status=Enabled

aws s3api put-bucket-encryption --bucket "$BUCKET" \
  --server-side-encryption-configuration \
  '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'

aws s3api put-public-access-block --bucket "$BUCKET" \
  --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true

echo "   versioned + encrypted + public access blocked"

# ---------------------------------------------------------------------------
# Step 2 — State locking
#   Terraform >= 1.10 uses S3-native locking (use_lockfile in backend.tf).
#   No DynamoDB table needed; nothing to provision here.
# ---------------------------------------------------------------------------
echo ">> Step 2: state locking — S3-native (Terraform >= 1.10), no DynamoDB"

# ---------------------------------------------------------------------------
# Step 3 — IAM OIDC identity provider (per-account singleton for this URL)
# ---------------------------------------------------------------------------
echo ">> Step 3: GitHub Actions OIDC provider"
if aws iam get-open-id-connect-provider --open-id-connect-provider-arn "$OIDC_ARN" >/dev/null 2>&1; then
  echo "   exists — ${OIDC_ARN}"
else
  aws iam create-open-id-connect-provider \
    --url "https://${OIDC_URL}" \
    --client-id-list sts.amazonaws.com \
    --query OpenIDConnectProviderArn --output text
  echo "   created — ${OIDC_ARN}"
fi

echo ">> Done. Next: local 'terraform apply' (see the runbook's After the"
echo "   bootstrap section), then ./scripts/github-bootstrap.sh."
