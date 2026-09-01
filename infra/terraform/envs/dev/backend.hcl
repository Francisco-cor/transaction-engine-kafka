# Example backend config for dev — do not commit real bucket names for prod; use -backend-config
bucket         = "transaction-engine-tfstate-dev"
key            = "dev/terraform.tfstate"
region         = "us-east-1"
dynamodb_table = "transaction-engine-tfstate-lock"
encrypt        = true
