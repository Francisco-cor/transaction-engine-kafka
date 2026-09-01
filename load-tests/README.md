# Load Tests

10k benchmark con hot keys.

```powershell
# Requiere k6 instalado (https://k6.io/docs/getting-started/installation/)
k6 run load-tests/k6-transactions.js
k6 run --env BASE_URL=http://localhost:8080 load-tests/k6-transactions.js

# Via Invoke-Project
powershell -File scripts/Invoke-Project.ps1 -Command load
```

Outputs `reports/load/k6-summary.json` para reporte Fase 7/11.
