---
name: summarize-changes
description: Summarizes uncommitted changes and flags anything risky. Use when the user asks what changed.
allowed-tools: Read Grep Bash
disallowed-tools: Write
disable-model-invocation: false
context: inline
---

## Current changes

Review the uncommitted changes in the repository.

## Instructions

Summarize the changes in two or three bullet points, then list any risks you notice such as missing error handling, hardcoded values, or tests that need updating.
