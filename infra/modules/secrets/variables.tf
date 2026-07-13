variable "name_prefix" { type = string }
variable "rabbitmq_username" {
  type    = string
  default = "rtdad"
}
variable "tags" {
  type    = map(string)
  default = {}
}
