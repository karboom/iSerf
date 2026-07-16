---
name: deploy
description: Deploy the application to production
allowed-tools:
  - Bash
  - Read
  - Write
disable-model-invocation: true
context: fork
---

## Deploy Steps

1. Run the test suite
2. Build the application
3. Push to the deployment target
