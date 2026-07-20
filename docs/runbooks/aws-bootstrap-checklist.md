# AWS Bootstrap Checklist (Console or CLI)

Welcome! This guide covers the one-time, manual steps that must be performed in AWS before Terraform can take over and provision the rest of the infrastructure. This is a prerequisite to unblocking the GitHub Actions CI/CD pipeline.

**Date:** 2026-07-11 (CLI commands added 2026-07-12)

## Why is this needed?

The pipeline authenticates to AWS securely using GitHub OIDC, meaning static access keys are never stored. However, because the GitHub repository is public, it is not trusted with the power to create or destroy core infrastructure (`terraform apply`).

Instead, a hybrid model is used:
1. GitHub CI runs a read-only `terraform plan` to show what will change.
2. `terraform apply` is run locally from an administrator's machine to actually make the changes.

Before that very first local `apply` can be run, a place is needed for Terraform to store its state (an S3 bucket) along with an OIDC provider in AWS so GitHub can authenticate. This checklist covers creating those foundation pieces.

## The Easy Way (Automated Script)

It is recommended to use the provided script, which securely automates Steps 1-3 using the AWS CLI. It's idempotent, meaning it is safe to run multiple times.

```bash
# First, ensure an AWS administrator is logged in:
# (aws sts get-caller-identity should succeed)

./scripts/aws-bootstrap.sh
```

**What the script handles:**
- **Step 1:** Creates a secure, versioned, and encrypted S3 bucket to store the Terraform state.
- **Step 2:** (No action needed here because Terraform 1.10+ uses native S3 locking instead of DynamoDB).
- **Step 3:** Sets up the account-wide GitHub Actions OIDC provider, but only if it doesn't already exist.
- **Step 4 Preflight:** It double-checks that the execution is running as an AWS admin before starting.

If clicking through the AWS Console or running the commands manually is preferred, follow the steps below!

## Manual Setup Steps

Before beginning, gather a few details:
- [ ] AWS admin access is available (Console or CLI).
- [ ] **Target region:** `___` (e.g., `us-west-2`)
- [ ] **Account ID:** `___`
- [ ] **GitHub repository:** `rodrigomalara/rt-dad`

If using the CLI, set these variables to make copy-pasting easier:
```bash
REGION=us-west-2
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
BUCKET=rt-dad-tfstate-${ACCOUNT_ID}-${REGION}-an   # this deployment's bucket
```

### Step 1: Create the Terraform State Bucket

Terraform needs a secure place in the cloud to remember the state of the infrastructure.

**Using the AWS Console:**
Go to S3 → Create bucket. Turn **Block all public access** ON, enable **Versioning**, and set the Default encryption to **SSE-S3**.

**Using the CLI:**
```bash
# Note: us-west-2 requires the LocationConstraint flag
aws s3api create-bucket --bucket "$BUCKET" --region "$REGION" \
  --create-bucket-configuration LocationConstraint="$REGION"

aws s3api put-bucket-versioning --bucket "$BUCKET" \
  --versioning-configuration Status=Enabled

aws s3api put-bucket-encryption --bucket "$BUCKET" \
  --server-side-encryption-configuration \
  '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'

aws s3api put-public-access-block --bucket "$BUCKET" \
  --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
```
- [ ] **Record the exact bucket name:** This will be needed later for the `terraform init` command.

### Step 2: Configure State Locking

State locking prevents two people from applying infrastructure changes at the exact same time.

- [ ] **Terraform 1.10 or newer:** Nothing is needed! It uses S3-native locking (`use_lockfile = true`), so no DynamoDB table is needed.
- [ ] **Older Terraform versions:** A DynamoDB table must be created a DynamoDB table named `rtdad-tflock` with a String partition key called `LockID`.

### Step 3: Add the GitHub OIDC Provider

This provider tells AWS to trust GitHub Actions. It is a one-time, account-wide setup. This is kept manual so an accidental `terraform destroy` won't wipe out the trust anchor.

**Using the AWS Console:**
Go to IAM → Identity providers → Add provider → Select OpenID Connect.
- **Provider URL:** `https://token.actions.githubusercontent.com`
- **Audience:** `sts.amazonaws.com`

**Using the CLI:**
```bash
# Create the OIDC provider only if it is missing:
aws iam create-open-id-connect-provider \
  --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com \
  --query OpenIDConnectProviderArn --output text
```
- [ ] **Record the Provider ARN:** It will look like `arn:aws:iam::ACCOUNT_ID:oidc-provider/token.actions.githubusercontent.com`.

### Step 4: Run the First Terraform Apply

With the prerequisites in place, the first `terraform apply` can now be run locally. This run is special because it provisions the CI roles (like the plan and deploy roles) that GitHub Actions will use in the future.

The staging deployment is split into two waves. The base infrastructure and EKS cluster must exist before add-ons like RabbitMQ can be installed.

```bash
cd infra/envs/staging
terraform init -reconfigure \
  -backend-config="bucket=$BUCKET" \
  -backend-config="region=$REGION"

# Wave 1: Build the base AWS network and EKS cluster
terraform apply -target=module.vpc -target=module.eks -target=module.ecr \
  -target=module.github_oidc -target=module.secrets -var-file=staging.tfvars

# Wave 2: Install cluster add-ons
terraform apply -var-file=staging.tfvars
```

### What's Next?

1. Grab the **plan role ARN**, **deploy role ARN**, and the **S3 bucket name** from the Terraform outputs.
2. Head over to the **[GitHub Bootstrap Checklist](github-bootstrap-checklist.md)** to configure the repository and environments!
