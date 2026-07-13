resource "kubernetes_config_map" "rtdad_dashboard" {
  metadata {
    name      = "rtdad-dashboard"
    namespace = "monitoring"
    labels    = { grafana_dashboard = "1" }
    # Grafana sidecar places the dashboard in this folder (matches the Compose setup).
    annotations = { grafana_folder = "RT-DAD" }
  }
  data = {
    # The same dashboard used by the local Compose stack, so parity is exact.
    # Its panels bind to datasource uid "prometheus", which kube-prometheus-stack
    # also uses — verified.
    "rtdad-observability.json" = file("${path.module}/../../../observability/grafana/dashboards/rtdad-observability.json")
  }
  depends_on = [kubernetes_namespace.monitoring]
}

resource "helm_release" "kube_prometheus_stack" {
  name       = "kube-prometheus-stack"
  repository = "https://prometheus-community.github.io/helm-charts"
  chart      = "kube-prometheus-stack"
  version    = "65.1.1"
  namespace  = "monitoring"

  values = [yamlencode({
    alertmanager = { enabled = false }

    # Trimmed on the single-node experiment: these feed cluster/node dashboards,
    # not the RT-DAD app dashboard, and each is a pod we need for app workloads.
    nodeExporter     = { enabled = false }
    kubeStateMetrics = { enabled = false }

    # Admission webhooks run a pre-upgrade hook Job that needs a free pod slot;
    # on a full single node that deadlocks the very upgrade meant to free pods.
    # Not needed for this experiment (we author no PrometheusRules).
    prometheusOperator = {
      admissionWebhooks = { enabled = false }
    }

    grafana = {
      admin = {
        existingSecret = "grafana-admin"
        userKey        = "admin-user"
        passwordKey    = "admin-password"
      }
      service = {
        type     = "NodePort"
        nodePort = var.nodeports.grafana
      }
      sidecar = {
        dashboards = { enabled = true, label = "grafana_dashboard" }
      }
      persistence = { enabled = true, storageClassName = "gp3", size = "2Gi" }
    }

    prometheus = {
      service = {
        type     = "NodePort"
        nodePort = var.nodeports.prometheus
      }
      prometheusSpec = {
        retention = "3d"
        storageSpec = {
          volumeClaimTemplate = {
            spec = {
              storageClassName = "gp3"
              accessModes      = ["ReadWriteOnce"]
              resources        = { requests = { storage = "8Gi" } }
            }
          }
        }
        # Discover ServiceMonitors in all namespaces (app chart ships one).
        serviceMonitorSelectorNilUsesHelmValues = false
      }
    }
  })]

  depends_on = [
    kubernetes_storage_class.gp3,
    kubectl_manifest.grafana_external_secret,
    kubernetes_config_map.rtdad_dashboard,
  ]
}
