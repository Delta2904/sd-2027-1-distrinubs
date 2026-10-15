# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Current state

This repo has no source code, build system, tests, or git history yet. The only file is `docs/PRD-Distrinubs.md`, the product requirements document for "Distrinubs", a university programming-contest platform. Do not invent build, lint, or test commands; none exist. When code is added, update this file with the real commands.

The PRD is written in Spanish and its domain terms are Spanish (concurso, envío, marcador, tabla de posiciones, evaluación). Keep those terms in code identifiers and UI strings unless told otherwise.

## Planned architecture (from the PRD)

**Stage 1 is a monolith with seven modules:** inicio de sesión (credentials, tokens, sessions), envíos (submission intake), tabla de posiciones (scoreboard), evaluación de código (compile/run test cases), concursos y problemas (contests, problems, verified test cases), administración (accounts, roles, infrastructure), and base de datos. In stage 1 the submission queue lives in PostgreSQL as rows in state "en cola".

**Stage 2 splits the monolith into services** with Kafka between submissions and evaluation. Kafka carries IDs only; state stays in PostgreSQL, because Kafka does not handle leases or per-submission retries.

**Each piece of data has exactly one writing module.** Examples: only Inicio de sesión writes accounts and tokens, so Administración must request account changes from it rather than writing them. Only Evaluación writes per-submission results. Preserve this ownership when adding code.

**Key behavioral rules that shape the design:**
- Submission endpoints return "recibido" with an ID immediately. Evaluation is asynchronous.
- A retried upload with the same ID must return the same ID and create no new submission (idempotency).
- Tokens are validated when a submission is received, not when it runs. Revoked tokens are rejected at intake.
- Each submission produces exactly one result (unique constraint). A re-evaluation after a worker crash must not count twice.
- Workers hold a renewable lease while they run. A crashed worker's lease expires and the submission returns to the queue. Compile errors that look like server faults are retried on a clean worker, up to a fixed cap.
- The public scoreboard is provisional: it uses a subset of test cases, is capped at 5 minutes of lag (organizers are alerted past that), and uses server arrival time, not client clocks.
- The final scoreboard is computed at contest close. Difficulty is based on how many students solved each problem and is applied only at close. Unsolved problems are worth zero.
- Penalty is 1 minute per failed attempt, including attempts later invalidated. Tiebreak order: problems solved, penalty, runtime, memory, then time of the last solved problem. Full ties are shared positions.
- Problems must pass their reference solution within limits before they can be published. Editing a verified problem invalidates its verification.
- Every administrative action is logged with user, time, and reason.

**Sandbox:** each submission runs in a fresh, unprivileged container with no network, a read-only filesystem except one temp directory, and memory, CPU, and process limits. Language toolchains and limits come from a configuration table, not hard-coded per language.

**Consistency over availability:** on a network partition the system stops accepting consistent state changes rather than running on one site. Scoreboard correctness matters more than uptime.

## Decision record

Architecture decisions and their rejected alternatives are tabulated in section 9 of the PRD. Check it before proposing a change to the data store, queue, gateway, failover, or tiebreak rules; many of these were chosen deliberately and the PRD records why.

## Open items

Section 10 of the PRD lists unresolved questions (compile-timeout value, whether organizers can withdraw unsolved problems before close, the 5-minute scoreboard lag still needing a load test). Treat these as undecided, not as settled.
