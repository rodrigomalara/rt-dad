locals {
  name = "rtdad-staging"
  tags = {
    Project     = "rtdad"
    Environment = "staging"
  }
}

# Module blocks are added task-by-task below.

data "aws_availability_zones" "available" {
  state = "available"
}

module "vpc" {
  source = "../../modules/vpc"

  name            = local.name
  cidr            = var.vpc_cidr
  azs             = slice(data.aws_availability_zones.available.names, 0, 2)
  public_subnets  = [cidrsubnet(var.vpc_cidr, 4, 0), cidrsubnet(var.vpc_cidr, 4, 1)]
  private_subnets = [cidrsubnet(var.vpc_cidr, 4, 2), cidrsubnet(var.vpc_cidr, 4, 3)]
  tags            = local.tags
}

module "ecr" {
  source           = "../../modules/ecr"
  repository_names = ["rtdad-producer", "rtdad-consumer"]
  tags             = local.tags
}

module "github_oidc" {
  source              = "../../modules/github-oidc"
  name_prefix         = local.name
  github_repo         = var.github_repo
  oidc_provider_arn   = var.oidc_provider_arn
  ecr_repository_arns = module.ecr.repository_arns
  eks_cluster_arn     = module.eks.cluster_arn
  tags                = local.tags
}

module "secrets" {
  source      = "../../modules/secrets"
  name_prefix = local.name
  tags        = local.tags
}

module "eks" {
  source = "../../modules/eks"

  name               = local.name
  cluster_version    = var.cluster_version
  vpc_id             = module.vpc.vpc_id
  public_subnet_ids  = module.vpc.public_subnets
  node_instance_type = var.node_instance_type
  whitelist_cidrs    = var.whitelist_cidrs
  deploy_role_arn    = module.github_oidc.deploy_role_arn
  tags               = local.tags
}

module "addons" {
  source = "../../modules/addons"

  name_prefix            = local.name
  cluster_name           = module.eks.cluster_name
  oidc_provider_arn      = module.eks.oidc_provider_arn
  oidc_provider_host     = replace(module.eks.oidc_provider_url, "https://", "")
  secret_read_policy_arn = module.secrets.read_policy_arn
  secret_arn             = module.secrets.secret_arn
  secret_name            = module.secrets.secret_name
  tags                   = local.tags
}
