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

# Application namespace. Also created by `helm --create-namespace` on deploy,
# but owned here so the RBAC below can reference it deterministically.
resource "kubernetes_namespace" "rtdad" {
  metadata {
    name = "rtdad"
  }
}

# The managed AmazonEKSEditPolicy on the deploy access entry covers standard
# namespaced resources but not the ServiceMonitor CRD. Grant just that, scoped
# to the rtdad namespace, to the group the deploy role is mapped into.
resource "kubernetes_role" "rtdad_deploy_servicemonitors" {
  metadata {
    name      = "rtdad-deploy-servicemonitors"
    namespace = kubernetes_namespace.rtdad.metadata[0].name
  }
  rule {
    api_groups = ["monitoring.coreos.com"]
    resources  = ["servicemonitors"]
    verbs      = ["get", "list", "watch", "create", "update", "patch", "delete"]
  }
}

resource "kubernetes_role_binding" "rtdad_deploy_servicemonitors" {
  metadata {
    name      = "rtdad-deploy-servicemonitors"
    namespace = kubernetes_namespace.rtdad.metadata[0].name
  }
  role_ref {
    api_group = "rbac.authorization.k8s.io"
    kind      = "Role"
    name      = kubernetes_role.rtdad_deploy_servicemonitors.metadata[0].name
  }
  subject {
    kind      = "Group"
    name      = "rtdad-deployers" # matches access_entries.deployer.kubernetes_groups
    api_group = "rbac.authorization.k8s.io"
  }
}
