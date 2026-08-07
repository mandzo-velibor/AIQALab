# Architecture

                Next.js

                    │

REST + WebSocket

                    │

Spring Boot

                    │

Agent Orchestrator

                    │

------------------------------------

Planner

Explorer

Executor

Analyst

Healing

------------------------------------

                    │

Tools

Browser

Filesystem

Playwright

Terminal

                    │

PostgreSQL

---

## Pravila

Agent ne zna kako se nešto radi.

Tool izvršava posao.

Agent samo odlučuje koji Tool da pozove.

---

## Workflow

User

↓

Explorer

↓

Planner

↓

Executor

↓

Analyst

↓

Healing

↓

Done

---

## Tool Interface

interface Tool {

execute()

}

---

## Agent Interface

interface QaAgent {

execute(Task)

}

---

## MVP Toolovi

Browser Tool

Open URL

Take Screenshot

Read DOM

---

Playwright Tool

Run All

Run Single

---

Filesystem Tool

Read File

Write File

Search

---

Terminal Tool

Execute Command

---

## Database

Čuvati

Sessions

Tasks

Executions

Failures

Healing Suggestions

---

## Buduće verzije

Vector Memory

Plugin sistem

Claude

Gemini

Ollama

Accessibility Agent

Performance Agent

API Agent

Visual Testing Agent
