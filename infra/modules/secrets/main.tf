resource "random_password" "rabbitmq" {
  length  = 24
  special = false # RabbitMQ URIs are simpler without special chars
}

resource "aws_secretsmanager_secret" "rabbitmq" {
  name        = "${var.name_prefix}/rabbitmq"
  description = "RabbitMQ username/password for rtdad staging"
  tags        = var.tags
}

resource "aws_secretsmanager_secret_version" "rabbitmq" {
  secret_id = aws_secretsmanager_secret.rabbitmq.id
  secret_string = jsonencode({
    username = var.rabbitmq_username
    password = random_password.rabbitmq.result
  })
}

data "aws_iam_policy_document" "read" {
  statement {
    effect    = "Allow"
    actions   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
    resources = ["arn:aws:secretsmanager:*:*:secret:${var.name_prefix}/*"]
  }
}

resource "aws_iam_policy" "read" {
  name   = "${var.name_prefix}-rabbitmq-secret-read"
  policy = data.aws_iam_policy_document.read.json
  tags   = var.tags
}
