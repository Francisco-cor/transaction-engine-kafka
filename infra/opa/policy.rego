package transaction

# F3 OPA Rego — replay and DLT only admin, transactions read/write by scope
# Data: input = { method, path, jwt: { scope, tenant, sub }, accountId }

default allow := false

# Health always
allow if {
  input.path == "/actuator/health/readiness"
}

allow if {
  input.path == "/actuator/health/liveness"
}

# Transactions read/write
allow if {
  input.method == "POST"
  input.path == "/transactions"
  has_scope("transactions:write")
}

allow if {
  input.method == "GET"
  startswith(input.path, "/transactions/")
  has_scope("transactions:read")
}

# Replay only admin
allow if {
  contains(input.path, "/replay")
  has_scope("admin:replay")
}

# Statement read — must be owner or admin
allow if {
  startswith(input.path, "/accounts/")
  contains(input.path, "/statement")
  has_scope("transactions:read")
  input.accountId == input.jwt.tenant
}

has_scope(s) if {
  s in input.jwt.scope
}

has_scope(s) if {
  # scp claim alternative
  s in input.jwt.scp
}
