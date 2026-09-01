output "vpc_id" {
  description = "VPC ID"
  value       = aws_vpc.this.id
}

output "vpc_cidr" {
  value = aws_vpc.this.cidr_block
}

output "private_subnets" {
  value = aws_subnet.private[*].id
}

output "public_subnets" {
  value = aws_subnet.public[*].id
}

output "private_subnet_cidrs" {
  value = var.private_subnets
}

output "nat_gateway_id" {
  value = var.enable_nat_gateway ? aws_nat_gateway.this[0].id : null
}
