terraform {
  backend "s3" {
    key          = "rtdad/staging/terraform.tfstate"
    encrypt      = true
    use_lockfile = true # S3-native locking (Terraform >= 1.10)
  }
}
