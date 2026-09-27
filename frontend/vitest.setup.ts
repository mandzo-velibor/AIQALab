import "@testing-library/jest-dom/vitest"
import { cleanup } from "@testing-library/react"
import { afterEach } from "vitest"

// React Testing Library does not unmount between tests when the runner is Vitest rather
// than Jest, so without this a component from one test stays in the document and the next
// test can match text that was never rendered by it. That produces passes that are not
// real and failures that cannot be reproduced.
afterEach(() => {
  cleanup()
})
