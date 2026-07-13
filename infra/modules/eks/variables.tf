variable "name" { type = string }
variable "cluster_version" { type = string }
variable "vpc_id" { type = string }
variable "public_subnet_ids" { type = list(string) }
variable "node_instance_type" { type = string }
variable "whitelist_cidrs" { type = list(string) }
variable "deploy_role_arn" { type = string }
variable "nodeports" {
  type    = list(number)
  default = [30300, 30808, 30909, 30672] # grafana, actuator, prometheus, rabbitmq mgmt
}
variable "tags" {
  type    = map(string)
  default = {}
}
