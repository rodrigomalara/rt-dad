# Single custom "experiment" SG carrying the IP-whitelist NodePort rules.
resource "aws_security_group" "experiment" {
  name_prefix = "${var.name}-experiment-"
  description = "IP-whitelisted NodePort access for rtdad staging"
  vpc_id      = var.vpc_id
  tags        = var.tags

  lifecycle { create_before_destroy = true }
}

resource "aws_vpc_security_group_ingress_rule" "nodeports" {
  for_each = {
    for pair in setproduct(var.whitelist_cidrs, var.nodeports) :
    "${pair[0]}-${pair[1]}" => { cidr = pair[0], port = pair[1] }
  }

  security_group_id = aws_security_group.experiment.id
  description       = "NodePort ${each.value.port} from ${each.value.cidr}"
  cidr_ipv4         = each.value.cidr
  ip_protocol       = "tcp"
  from_port         = each.value.port
  to_port           = each.value.port
}

resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.experiment.id
  description       = "Allow all egress"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 20.24"

  cluster_name    = var.name
  cluster_version = var.cluster_version

  cluster_endpoint_public_access       = true
  cluster_endpoint_public_access_cidrs = var.whitelist_cidrs

  vpc_id     = var.vpc_id
  subnet_ids = var.public_subnet_ids # nodes + control-plane ENIs in public subnets

  enable_irsa = true

  authentication_mode                      = "API_AND_CONFIG_MAP"
  enable_cluster_creator_admin_permissions = true

  # Control-plane logging off (cost).
  cluster_enabled_log_types = []

  eks_managed_node_groups = {
    default = {
      instance_types = [var.node_instance_type]
      capacity_type  = "SPOT"
      min_size       = 1
      max_size       = 1
      desired_size   = 1

      subnet_ids = [var.public_subnet_ids[0]] # single AZ

      # Attach the experiment SG so NodePorts are reachable from the whitelist.
      vpc_security_group_ids = [aws_security_group.experiment.id]
    }
  }

  # Cluster-admin for the operator is granted automatically by
  # enable_cluster_creator_admin_permissions (the identity running `apply`).
  access_entries = {
    deployer = {
      principal_arn = var.deploy_role_arn
      policy_associations = {
        edit = {
          policy_arn = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSEditPolicy"
          access_scope = {
            type       = "namespace"
            namespaces = ["rtdad"]
          }
        }
      }
    }
  }

  tags = var.tags
}
