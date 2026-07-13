data "aws_iam_policy_document" "eso_trust" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [var.oidc_provider_arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${var.oidc_provider_host}:sub"
      values   = ["system:serviceaccount:external-secrets:external-secrets"]
    }
  }
}

resource "aws_iam_role" "eso" {
  name               = "${var.name_prefix}-external-secrets"
  assume_role_policy = data.aws_iam_policy_document.eso_trust.json
  tags               = var.tags
}

resource "aws_iam_role_policy_attachment" "eso_read" {
  role       = aws_iam_role.eso.name
  policy_arn = var.secret_read_policy_arn
}

resource "helm_release" "external_secrets" {
  name             = "external-secrets"
  repository       = "https://charts.external-secrets.io"
  chart            = "external-secrets"
  version          = "0.10.4"
  namespace        = "external-secrets"
  create_namespace = true

  set {
    name  = "installCRDs"
    value = "true"
  }
  set {
    name  = "serviceAccount.annotations.eks\\.amazonaws\\.com/role-arn"
    value = aws_iam_role.eso.arn
  }
}

resource "kubernetes_namespace" "rtdad" {
  metadata { name = "rtdad" }
}

# ClusterSecretStore pointing at AWS Secrets Manager (uses ESO SA IRSA).
resource "kubectl_manifest" "secret_store" {
  yaml_body = yamlencode({
    apiVersion = "external-secrets.io/v1beta1"
    kind       = "ClusterSecretStore"
    metadata   = { name = "aws-secretsmanager" }
    spec = {
      provider = {
        aws = {
          service = "SecretsManager"
          region  = data.aws_region.current.name
          auth = {
            jwt = {
              serviceAccountRef = {
                name      = "external-secrets"
                namespace = "external-secrets"
              }
            }
          }
        }
      }
    }
  })
  depends_on = [helm_release.external_secrets]
}

data "aws_region" "current" {}

# ExternalSecret materializing the RabbitMQ creds into the rtdad namespace.
resource "kubectl_manifest" "rabbitmq_external_secret" {
  yaml_body = yamlencode({
    apiVersion = "external-secrets.io/v1beta1"
    kind       = "ExternalSecret"
    metadata = {
      name      = "rabbitmq-credentials"
      namespace = "rtdad"
    }
    spec = {
      refreshInterval = "1h"
      secretStoreRef  = { name = "aws-secretsmanager", kind = "ClusterSecretStore" }
      target = {
        name           = "rabbitmq-credentials"
        creationPolicy = "Owner"
      }
      data = [
        {
          secretKey = "username"
          remoteRef = { key = var.secret_name, property = "username" }
        },
        {
          secretKey = "password"
          remoteRef = { key = var.secret_name, property = "password" }
        },
      ]
    }
  })
  depends_on = [kubectl_manifest.secret_store, kubernetes_namespace.rtdad]
}

# Reuse Secrets Manager for a Grafana admin password too.
resource "random_password" "grafana_admin" {
  length  = 20
  special = false
}

resource "aws_secretsmanager_secret" "grafana" {
  name = "${var.name_prefix}/grafana-admin"
  tags = var.tags
}

resource "aws_secretsmanager_secret_version" "grafana" {
  secret_id     = aws_secretsmanager_secret.grafana.id
  secret_string = jsonencode({ admin-user = "admin", admin-password = random_password.grafana_admin.result })
}

resource "kubectl_manifest" "grafana_external_secret" {
  yaml_body = yamlencode({
    apiVersion = "external-secrets.io/v1beta1"
    kind       = "ExternalSecret"
    metadata   = { name = "grafana-admin", namespace = "monitoring" }
    spec = {
      refreshInterval = "1h"
      secretStoreRef  = { name = "aws-secretsmanager", kind = "ClusterSecretStore" }
      target          = { name = "grafana-admin", creationPolicy = "Owner" }
      data = [
        { secretKey = "admin-user", remoteRef = { key = aws_secretsmanager_secret.grafana.name, property = "admin-user" } },
        { secretKey = "admin-password", remoteRef = { key = aws_secretsmanager_secret.grafana.name, property = "admin-password" } },
      ]
    }
  })
  depends_on = [kubectl_manifest.secret_store, kubernetes_namespace.monitoring]
}

resource "kubernetes_namespace" "monitoring" {
  metadata { name = "monitoring" }
}
