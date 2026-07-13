output "cluster_name" {
  value = module.eks.cluster_name
}

output "ecr_repository_urls" {
  value = module.ecr.repository_urls
}

output "ci_plan_role_arn" {
  value = module.github_oidc.plan_role_arn
}

output "ci_deploy_role_arn" {
  value = module.github_oidc.deploy_role_arn
}

output "nodeport_access_hint" {
  description = "Get a node public DNS, then reach services at <dns>:<port>"
  value       = "kubectl get nodes -o wide  # then: grafana :30300, actuator :30808, prometheus :30909, rabbitmq :30672"
}
