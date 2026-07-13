variable "name_prefix" { type = string }
variable "cluster_name" { type = string }
variable "oidc_provider_arn" { type = string }
variable "oidc_provider_host" {
  description = "OIDC issuer host without https:// (e.g. oidc.eks.eu-west-1.amazonaws.com/id/ABC)"
  type        = string
}
variable "secret_read_policy_arn" { type = string }
variable "secret_arn" { type = string }
variable "secret_name" { type = string }
variable "nodeports" {
  type = object({
    grafana    = number
    prometheus = number
    rabbitmq   = number
  })
  default = {
    grafana    = 30300
    prometheus = 30909
    rabbitmq   = 30672
  }
}
variable "tags" {
  type    = map(string)
  default = {}
}
