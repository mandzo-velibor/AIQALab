# AI QA Lab

AI QA Lab je eksperimentalna platforma za automatizovano testiranje uz pomoć AI agenata.

Cilj projekta nije da zameni Playwright, već da pokaže kako AI može da:

- istraži aplikaciju
- planira testove
- izvrši Playwright testove
- analizira failure
- predloži self-healing
- pamti prethodne probleme

## Stack

Backend

- Java 21
- Spring Boot
- Spring AI
- PostgreSQL

Frontend

- Next.js
- Tailwind
- shadcn/ui

Automation

- Playwright

AI

- OpenAI (za početak)
- kasnije Claude, Gemini i Ollama

---

## MVP

User unese URL.

Explorer Agent obiđe aplikaciju.

Planner Agent predloži testove.

Executor Agent pokrene Playwright.

Analyst pročita rezultat.

Healing Agent predloži novu verziju locatora.

Sve se vidi u dashboardu.

---

## Projekat nije chatbot.

AI je podeljen na više specijalizovanih agenata.

Svaki agent ima jednu odgovornost.

Svi koriste zajedničke Tool-ove.
