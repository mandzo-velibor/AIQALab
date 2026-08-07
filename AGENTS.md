# Agent Architecture

## 1. Planner Agent

Odgovornost

Razume šta korisnik želi.

Input

- user prompt
- html
- screenshot

Output

Plan testiranja.

Nikad ne izvršava testove.

---

## 2. Explorer Agent

Odgovornost

Istražuje web aplikaciju.

Koristi Browser Tool.

Može da:

- otvori URL
- pročita DOM
- napravi screenshot
- pronađe forme
- pronađe dugmad
- pronađe inpute

Output

Mapa stranice.

---

## 3. Executor Agent

Odgovornost

Pokreće Playwright.

Koristi Playwright Tool.

Može

- run all
- run single

Output

- logs
- screenshot
- trace
- video

---

## 4. Analyst Agent

Odgovornost

Analizira rezultat.

Input

- log
- screenshot
- html

Output

Objašnjenje.

Primer

Timeout.

Element hidden.

HTTP 500.

Locator invalid.

---

## 5. Healing Agent

Odgovornost

Predlaže novi locator.

Nikad ne menja kod automatski.

Primer

from

page.locator("#login")

to

page.getByRole("button",{name:"Login"})

Output

Suggestion.

---

Svi agenti implementiraju

QaAgent

sa metodom

execute(Task task)
