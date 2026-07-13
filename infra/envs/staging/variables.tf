variable "region" {
  description = "AWS region for the staging environment"
  type        = string
}

variable "whitelist_cidrs" {
  description = "Source CIDRs allowed to reach NodePorts and any public admin surface"
  type        = list(string)
}

variable "vpc_cidr" {
  description = "CIDR block for the staging VPC"
  type        = string
  default     = "10.20.0.0/16"
}

variable "cluster_version" {
  description = "EKS Kubernetes version"
  type        = string
  default     = "1.31"
}

variable "node_instance_type" {
  description = "Instance type for the single spot node"
  type        = string
  default     = "t3.medium"
}

variable "github_repo" {
  description = "owner/repo the OIDC roles trust"
  type        = string
  default     = "rodrigomalara/rt-dad"
}

variable "oidc_provider_arn" {
  description = "ARN of the pre-created GitHub Actions OIDC provider"
  type        = string
}
