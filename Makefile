POWERSHELL ?= powershell

.PHONY: build test integration-test quality scan up down logs smoke inspect verify-invariants load chaos clean-data k8s-up k8s-down k8s-smoke helm-lint kind-up kind-down tf-plan tf-apply tf-destroy tf-fmt

build:
	$(POWERSHELL) -NoProfile -File ./scripts/Invoke-Project.ps1 -Command build

test:
	$(POWERSHELL) -NoProfile -File ./scripts/Invoke-Project.ps1 -Command test

integration-test:
	$(POWERSHELL) -NoProfile -File ./scripts/Invoke-Project.ps1 -Command integration-test

quality:
	$(POWERSHELL) -NoProfile -File ./scripts/Invoke-Project.ps1 -Command quality

scan:
	$(POWERSHELL) -NoProfile -File ./scripts/Invoke-Project.ps1 -Command scan

up:
	$(POWERSHELL) -NoProfile -File ./scripts/Invoke-Project.ps1 -Command up

down:
	$(POWERSHELL) -NoProfile -File ./scripts/Invoke-Project.ps1 -Command down

logs:
	$(POWERSHELL) -NoProfile -File ./scripts/Invoke-Project.ps1 -Command logs

smoke:
	$(POWERSHELL) -NoProfile -File ./scripts/Invoke-Project.ps1 -Command smoke

inspect:
	$(POWERSHELL) -NoProfile -File ./scripts/Invoke-Project.ps1 -Command inspect

verify-invariants:
	$(POWERSHELL) -NoProfile -File ./scripts/Invoke-Project.ps1 -Command verify-invariants

load:
	$(POWERSHELL) -NoProfile -File ./scripts/Invoke-Project.ps1 -Command load

chaos:
	$(POWERSHELL) -NoProfile -File ./scripts/Invoke-Project.ps1 -Command chaos -Seed $(or $(SEED),42) -RunId $(RUN_ID) -Duration $(or $(DURATION),200) -Rate $(or $(RATE),50) -KillEvery $(or $(KILL_EVERY),30)

benchmark:
	$(POWERSHELL) -NoProfile -File ./scripts/Invoke-Project.ps1 -Command chaos -Seed $(or $(SEED),42) -RunId $(RUN_ID) -Duration $(or $(DURATION),200) -Rate $(or $(RATE),50) -KillEvery $(or $(KILL_EVERY),30)

clean-data:
	$(POWERSHELL) -NoProfile -File ./scripts/Invoke-Project.ps1 -Command clean-data -RemoveData

k8s-up:
	$(POWERSHELL) -NoProfile -File ./scripts/Invoke-Project.ps1 -Command k8s-up

k8s-down:
	$(POWERSHELL) -NoProfile -File ./scripts/Invoke-Project.ps1 -Command k8s-down

k8s-smoke:
	$(POWERSHELL) -NoProfile -File ./scripts/Invoke-Project.ps1 -Command k8s-smoke

helm-lint:
	$(POWERSHELL) -NoProfile -File ./scripts/Invoke-Project.ps1 -Command helm-lint

kind-up:
	$(POWERSHELL) -NoProfile -File ./scripts/kind-up.ps1

kind-down:
	$(POWERSHELL) -NoProfile -File ./scripts/kind-down.ps1

tf-plan:
	terraform -chdir=infra/terraform/envs/dev init -backend-config=backend.hcl -reconfigure
	terraform -chdir=infra/terraform/envs/dev plan

tf-apply:
	terraform -chdir=infra/terraform/envs/dev apply

tf-destroy:
	terraform -chdir=infra/terraform/envs/dev destroy

tf-fmt:
	terraform fmt -recursive infra/terraform
