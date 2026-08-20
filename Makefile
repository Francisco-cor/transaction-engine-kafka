POWERSHELL ?= powershell

.PHONY: build test quality scan up down logs smoke load chaos clean-data

build:
	$(POWERSHELL) -NoProfile -File ./scripts/Invoke-Project.ps1 -Command build

test:
	$(POWERSHELL) -NoProfile -File ./scripts/Invoke-Project.ps1 -Command test

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

load:
	$(POWERSHELL) -NoProfile -File ./scripts/Invoke-Project.ps1 -Command load

chaos:
	$(POWERSHELL) -NoProfile -File ./scripts/Invoke-Project.ps1 -Command chaos

clean-data:
	$(POWERSHELL) -NoProfile -File ./scripts/Invoke-Project.ps1 -Command clean-data -RemoveData
