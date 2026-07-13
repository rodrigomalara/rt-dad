output "gp3_storage_class" { value = kubernetes_storage_class.gp3.metadata[0].name }
