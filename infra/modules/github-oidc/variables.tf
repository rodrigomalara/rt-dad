variable "name_prefix" { type = string }
variable "github_repo" { type = string }
variable "oidc_provider_arn" { type = string }
variable "ecr_repository_arns" { type = list(string) }
variable "eks_cluster_arn" { type = string }
variable "tags" {
  type    = map(string)
  default = {}
}
