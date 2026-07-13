output "secret_arn" { value = aws_secretsmanager_secret.rabbitmq.arn }
output "secret_name" { value = aws_secretsmanager_secret.rabbitmq.name }
output "read_policy_arn" { value = aws_iam_policy.read.arn }
