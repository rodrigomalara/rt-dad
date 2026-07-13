output "cluster_name" { value = module.eks.cluster_name }
output "cluster_arn" { value = module.eks.cluster_arn }
output "cluster_endpoint" { value = module.eks.cluster_endpoint }
output "cluster_ca" { value = module.eks.cluster_certificate_authority_data }
output "oidc_provider_arn" { value = module.eks.oidc_provider_arn }
output "oidc_provider_url" { value = module.eks.cluster_oidc_issuer_url }
output "experiment_sg_id" { value = aws_security_group.experiment.id }
