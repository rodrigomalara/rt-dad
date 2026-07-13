resource "helm_release" "rabbitmq" {
  name       = "rabbitmq"
  repository = "https://charts.bitnami.com/bitnami"
  chart      = "rabbitmq"
  version    = "14.7.0"
  namespace  = "rtdad"

  # Give the pod more time to pull the image + start on a fresh single node,
  # and clean up the release if an install still fails so retries are clean.
  timeout         = 600
  cleanup_on_fail = true

  # Bitnami deprecated its public Docker Hub catalog (Aug 2025) and deleted the
  # old versioned tags from docker.io/bitnami/*; the free copies live under
  # docker.io/bitnamilegacy/*. Repoint the image there so the pinned tag resolves.
  set {
    name  = "image.repository"
    value = "bitnamilegacy/rabbitmq"
  }

  # Use the ESO-synced Secret for credentials.
  set {
    name  = "auth.username"
    value = "rtdad"
  }
  set {
    name  = "auth.existingPasswordSecret"
    value = "rabbitmq-credentials"
  }
  set {
    name  = "auth.existingSecretPasswordKey"
    value = "password"
  }

  # Single replica, EBS-backed persistence via gp3.
  set {
    name  = "replicaCount"
    value = "1"
  }
  set {
    name  = "persistence.enabled"
    value = "true"
  }
  set {
    name  = "persistence.storageClass"
    value = "gp3"
  }
  set {
    name  = "persistence.size"
    value = "8Gi"
  }

  # Expose the management UI on a pinned NodePort.
  set {
    name  = "service.type"
    value = "NodePort"
  }
  set {
    name  = "service.nodePorts.manager"
    value = tostring(var.nodeports.rabbitmq)
  }

  depends_on = [
    kubectl_manifest.rabbitmq_external_secret,
    kubernetes_storage_class.gp3,
  ]
}
