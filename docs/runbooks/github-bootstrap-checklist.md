# GitHub Bootstrap Checklist

Welcome! This checklist covers the one-time manual settings that must be configured in the GitHub repository to get the CI/CD pipeline running smoothly. 

**Date:** 2026-07-12

## Why is this needed?

The pipeline is designed to be secure and hands-off. It authenticates to AWS using OIDC (so static access keys are not stored) and deploys the code using Helm. To make this work, GitHub needs a few things:
1. Branch protection to ensure code is reviewed and tested before it merges.
2. Deployment environments to track where code is going.
3. Actions Variables to store the AWS Role ARNs that Terraform created.

> **Note:** GitHub **Secrets** are not used for any AWS configuration! OIDC eliminates the need for static keys, and the release jobs simply use the built-in `GITHUB_TOKEN`.

## Before beginning...

Because the systems rely on each other, make sure these steps are done in the correct order:
1. Complete Steps 1-3 of the **[AWS Bootstrap Checklist](aws-bootstrap-checklist.md)**.
2. Run the first **local `terraform apply`** for the staging environment. This creates the CI plan and deploy roles in AWS and outputs their ARNs.
3. **Now this checklist is ready!** Those role ARNs will be needed for Step 4.

---

### Step 1: Enable Actions and Set Permissions

First, ensure GitHub Actions has exactly the permissions it needs—no more, no less.
Go to **Settings → Actions → General**.

- [ ] Ensure **Actions permissions** are set to "Allow all actions and reusable workflows" (or restrict it to GitHub-hosted Ubuntu runners if preferred).
- [ ] Under **Workflow permissions**, choose **Read repository contents**. (This is the principle of least privilege. When workflows need more access—like writing packages—they request it temporarily).
- [ ] Under **Fork pull request workflows from outside collaborators**, check **Require approval for first-time contributors**. This protects against malicious pull requests.

### Step 2: Protect the `main` Branch

It must be ensured that all code going into `main` is safe and approved.
Go to **Settings → Branches → Add branch protection rule** (or use Rulesets if preferred).

- **Branch name pattern:** `main`
- [ ] **Require a pull request before merging.**
- [ ] **Require status checks to pass before merging:**
  - `build-test` (This ensures the app code compiles and tests pass).
  - `plan` (This runs `terraform plan` when infrastructure files change).
- [ ] **Require branches to be up to date before merging.**
- [ ] *(Optional but recommended)* Require linear history.

### Step 3: Set Up Deployment Environments

Environments allow tracking deployments and adding approval gates.
Go to **Settings → Environments → New environment**.

- [ ] Create an environment named `staging`.
  - Leave this to auto-deploy (no required reviewers).
- [ ] Create an environment named `production`.
  - Add **Yourself** (and any other leads) under **Required reviewers**.
- [ ] *(Optional)* Restrict both environments so they can only deploy from the `main` branch or release tags.

### Step 4: Add Actions Variables

Here is where GitHub is told how to talk to the newly built AWS infrastructure.
Go to **Settings → Secrets and variables → Actions → Variables**.

> Remember, these are standard Variables, not Secrets. They are low-sensitivity identifiers (like ARNs or region names). Because this repository is public, it is assumed all logs are readable by anyone. Security comes from the OIDC trust condition in AWS, not from hiding these values.

**Add these at the Repository level:**
- [ ] `AWS_REGION` = The target region (e.g., `us-west-2`)
- [ ] `TF_STATE_BUCKET` = The name of the S3 state bucket (e.g., `rt-dad-tfstate-<ACCOUNT_ID>-<REGION>-<suffix>`)
- [ ] `PLAN_ROLE_ARN` = The read-only role ARN (from `terraform output ci_plan_role_arn`)
- [ ] `PUBLISH_ROLE_ARN` = The CI publish role ARN (from `terraform output ci_publish_role_arn`)
- [ ] `WHITELIST_CIDRS` = The IP addresses allowed to reach the admin surfaces (formatted as an HCL list, e.g., `["203.0.113.10/32"]`)
- [ ] `OIDC_PROVIDER_ARN` = The GitHub Actions OIDC provider ARN created in the AWS bootstrap.

**Add these as Environment-specific variables:**
Go back to the `staging` environment settings to add these:
- [ ] `DEPLOY_ROLE_ARN` = The staging deploy role ARN (from `terraform output ci_deploy_role_arn`)
- [ ] `EKS_CLUSTER_NAME` = The staging cluster name (from `terraform output cluster_name`)

### Step 5: Configure GitHub Packages

GitHub Packages are used to store the release artifacts (like `rtdad-common`).
- [ ] **No immediate setup needed!** The first time a release is tagged, the pipeline will automatically publish the package using the `GITHUB_TOKEN`.
- [ ] **After the first publish:** If desired, one can go to the **Packages** tab on the repository homepage and ensure the package visibility is set to **Public** (to match the repository).

### Step 6: (Optional) Protect Release Tags

To prevent accidental or malicious release triggers:
- [ ] Create a new Ruleset for **Tags** targeting `v*`. Configure it so only authorized users can push release tags.
