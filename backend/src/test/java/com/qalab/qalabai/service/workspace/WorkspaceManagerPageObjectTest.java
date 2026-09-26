package com.qalab.qalabai.service.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adversarial tests for the page-object merger and the import rewriter.
 *
 * <p>Both are hand-rolled text transforms — a brace-matching splitter and a regex —
 * shipped without coverage. They exist because N generated variants of the same
 * page class used to overwrite one shared {@code pages/LoginPage.ts}, leaving every
 * sibling test referencing members that no longer existed.</p>
 */
class WorkspaceManagerPageObjectTest {

    private WorkspaceManager manager;

    @BeforeEach
    void setUp() {
        manager = new WorkspaceManager(null, null, null);
    }

    private String merge(String className, String... variants) {
        return ReflectionTestUtils.invokeMethod(manager, "mergePageObjects", className, List.of(variants));
    }

    private String rewrite(String testCode, String className, String pageFile) {
        return ReflectionTestUtils.invokeMethod(manager, "rewritePageObjectImport", testCode, className, pageFile);
    }

    private static String page(String className, String body) {
        return "import { Page, Locator } from '@playwright/test';\n\n"
                + "export class " + className + " {\n" + body + "\n}\n";
    }

    // ---------------------------------------------------------------- merging

    @Test
    void mergesTwoVariantsIntoTheUnionOfMembers() {
        String a = page("LoginPage", """
                  readonly usernameInput: Locator;
                  readonly loginButton: Locator;

                  constructor(page: Page) {
                    this.usernameInput = page.getByLabel('Username');
                    this.loginButton = page.getByRole('button', { name: 'Login' });
                  }

                  async login(u: string, p: string) {
                    await this.usernameInput.fill(u);
                  }""");
        String b = page("LoginPage", """
                  readonly heading: Locator;
                  readonly usernameInput: Locator;
                  readonly errorMessage: Locator;

                  constructor(page: Page) {
                    this.heading = page.getByRole('heading');
                    this.usernameInput = page.getByLabel('Username');
                    this.errorMessage = page.locator('#flash');
                  }

                  async getError(): Promise<string> {
                    return this.errorMessage.textContent() ?? '';
                  }""");

        String merged = merge("LoginPage", a, b);

        // every distinct field present exactly once
        assertEquals(1, countOf(merged, "readonly usernameInput: Locator;"),
                "shared field must be deduplicated:\n" + merged);
        assertTrue(merged.contains("readonly loginButton: Locator;"));
        assertTrue(merged.contains("readonly heading: Locator;"));
        assertTrue(merged.contains("readonly errorMessage: Locator;"));

        // methods from both, deduplicated by name
        assertTrue(merged.contains("async login("));
        assertTrue(merged.contains("async getError("));
        assertEquals(1, countOf(merged, "async login("), "method must appear once:\n" + merged);

        // constructor emitted exactly once, with the union of assignments
        assertEquals(1, countOf(merged, "constructor("), "constructor must appear once:\n" + merged);
        assertTrue(merged.contains("this.usernameInput = page.getByLabel('Username');"));
        assertTrue(merged.contains("this.errorMessage = page.locator('#flash');"));
        assertEquals(1, countOf(merged, "this.usernameInput ="), "assignment must be deduplicated:\n" + merged);

        // the import must survive even though the class body is extracted
        assertTrue(merged.startsWith("import { Page, Locator } from '@playwright/test';"));
    }

    @Test
    void conflictingLocatorForTheSameFieldKeepsTheFirstVariant() {
        String a = page("LoginPage", "  readonly btn: Locator;\n\n  constructor(page: Page) {\n    this.btn = page.getByRole('button', { name: 'Login' });\n  }");
        String b = page("LoginPage", "  readonly btn: Locator;\n\n  constructor(page: Page) {\n    this.btn = page.getByRole('button', { name: 'Sign in' });\n  }");

        String merged = merge("LoginPage", a, b);

        assertTrue(merged.contains("name: 'Login'"), "first variant must win:\n" + merged);
        assertFalse(merged.contains("name: 'Sign in'"), "second variant must be dropped:\n" + merged);
    }

    @Test
    void handlesBracesInsideStringLiteralsAndNestedBlocks() {
        // A locator whose argument contains braces must not be mistaken for a block
        // boundary, and a nested object literal must not unbalance the splitter.
        String a = page("LoginPage", """
                  readonly flash: Locator;
                  readonly data: Locator;

                  constructor(page: Page) {
                    this.flash = page.locator('#flash');
                    this.data = page.getByTestId('a}b{c}');
                  }

                  async assertMessage(expected: string): Promise<void> {
                    const actual = await this.flash.textContent();
                    if (actual !== null && expected !== '') {
                      expect(actual).toBe(expected);
                    }
                  }

                  async nested(): Promise<void> {
                    await this.flash.evaluate((el) => {
                      const o = { a: 1, b: { c: 2 } };
                      void o;
                    });
                  }""");

        String merged = merge("LoginPage", a);

        assertTrue(merged.contains("page.getByTestId('a}b{c}')"), "brace-in-string lost:\n" + merged);
        assertTrue(merged.contains("async assertMessage("), "first method lost:\n" + merged);
        assertTrue(merged.contains("async nested("), "second method lost:\n" + merged);
        assertTrue(merged.contains("{ a: 1, b: { c: 2 } }"), "nested literal body mangled:\n" + merged);
        assertEquals(1, countOf(merged, "constructor("));
        assertEquals(1, countOf(merged, "async nested("), "trailing method duplicated:\n" + merged);
    }

    @Test
    void toleratesAnUnterminatedStringLiteralWithoutHangingOrCrashing() {
        // Truncated LLM output can leave a quote open. The scanner must still terminate
        // and emit a usable class rather than looping or throwing.
        String broken = page("LoginPage", """
                  readonly data: Locator;

                  constructor(page: Page) {
                    this.data = page.getByTestId('a}b{c');
                  }""");

        String merged = merge("LoginPage", broken);

        assertTrue(merged.contains("export class LoginPage"), "class header lost:\n" + merged);
        assertTrue(merged.contains("readonly data: Locator;"));
        assertTrue(merged.trim().endsWith("}"), "output must still be a closed class:\n" + merged);
    }

    @Test
    void toleratesUnbalancedBracesWithoutLosingTheClass() {
        // Truncated LLM output: the class never closes. Must not throw.
        String truncated = "import { Page } from '@playwright/test';\n\nexport class LoginPage {\n"
                + "  readonly a: Locator;\n\n  constructor(page: Page) {\n    this.a = page.locator('#a');\n";

        String merged = merge("LoginPage", truncated);

        assertTrue(merged.contains("export class LoginPage"), "class header lost:\n" + merged);
        assertTrue(merged.contains("readonly a: Locator;"));
    }

    @Test
    void toleratesAVariantWithNoClassDeclaration() {
        String garbage = "I could not produce a page object for this page.";

        String merged = merge("LoginPage", garbage);

        assertTrue(merged.contains("export class LoginPage"), "must still emit a class:\n" + merged);
    }

    @Test
    void handlesAVariantWithoutAConstructor() {
        String withCtor = page("LoginPage", "  readonly a: Locator;\n\n  constructor(page: Page) {\n    this.a = page.locator('#a');\n  }");
        String withoutCtor = page("LoginPage", "  readonly b: Locator;");

        String merged = merge("LoginPage", withCtor, withoutCtor);

        assertTrue(merged.contains("readonly b: Locator;"), "field from ctor-less variant lost:\n" + merged);
        assertTrue(merged.contains("this.a = page.locator('#a');"));
    }

    @Test
    void handlesGenericTypeParametersInAMethodSignature() {
        String a = page("LoginPage", """
                  readonly a: Locator;

                  constructor(page: Page) {
                    this.a = page.locator('#a');
                  }

                  async first<T>(items: Array<T>): Promise<T | null> {
                    return items.length > 0 ? items[0] : null;
                  }""");

        String merged = merge("LoginPage", a);

        assertTrue(merged.contains("async first<T>("), "generic signature mangled:\n" + merged);
    }

    @Test
    void handlesStaticAndArrowFunctionProperties() {
        String a = page("LoginPage", """
                  static readonly BASE = '/login';
                  readonly resolve: () => string;

                  constructor(page: Page) {
                    this.resolve = () => page.url();
                  }""");

        String merged = merge("LoginPage", a);

        assertTrue(merged.contains("static readonly BASE = '/login';"), "static lost:\n" + merged);
        assertTrue(merged.contains("readonly resolve: () => string;"), "arrow property lost:\n" + merged);
    }

    // ---------------------------------------------------------------- rewriting

    @Test
    void rewritesTheStandardImport() {
        String code = """
                import { test, expect } from '@playwright/test';
                import { LoginPage } from '../pages/LoginPage';

                test('x', async ({ page }) => { const lp = new LoginPage(page); });""";

        String out = rewrite(code, "LoginPage", "LoginPage_login.ui.ts");

        assertTrue(out.contains("from '../pages/LoginPage_login.ui.ts';"), out);
        assertFalse(out.contains("from '../pages/LoginPage';"), "old import must be gone:\n" + out);
        // only the page import changes; the playwright import is untouched
        assertTrue(out.contains("from '@playwright/test';"), out);
    }

    @Test
    void rewritesOneImportAmongSeveral() {
        String code = """
                import { test } from '@playwright/test';
                import { LoginPage } from '../pages/LoginPage';
                import { SecureAreaPage } from '../pages/SecureAreaPage';""";

        String out = rewrite(code, "LoginPage", "LoginPage_a.ts");

        assertTrue(out.contains("import { LoginPage } from '../pages/LoginPage_a.ts';"), out);
        assertTrue(out.contains("import { SecureAreaPage } from '../pages/SecureAreaPage';"),
                "the other page import must be untouched:\n" + out);
    }

    @Test
    void rewritesWhenTheClassIsOneOfSeveralInASingleImport() {
        String code = "import { LoginPage, LoginPageBase } from '../pages/LoginPage';";

        String out = rewrite(code, "LoginPage", "LoginPage_a.ts");

        assertTrue(out.contains("from '../pages/LoginPage_a.ts';"), out);
    }

    @Test
    void doesNotMatchAClassNameThatIsOnlyASuffixOfAnother() {
        // "Page" must not be found inside "SecureAreaPage" — no word boundary there.
        String code = "import { SecureAreaPage } from '../pages/SecureAreaPage';";

        String out = rewrite(code, "Page", "Page_x.ts");

        assertEquals(code, out, "must not rewrite a different class's import:\n" + out);
    }

    @Test
    void leavesCodeUnchangedWhenNoImportMatches() {
        String code = "import { test } from '@playwright/test';\ntest('x', async () => {});";

        assertEquals(code, rewrite(code, "LoginPage", "LoginPage_a.ts"));
    }

    @Test
    void leavesCodeUnchangedWhenTheClassIsNotImportedAtAll() {
        String code = "const p = new LoginPage(page); // used without an import";

        assertEquals(code, rewrite(code, "LoginPage", "LoginPage_a.ts"));
    }

    @Test
    void handlesDoubleQuotedAndExtensionlessPaths() {
        assertTrue(rewrite("import { LoginPage } from \"../pages/LoginPage\";",
                "LoginPage", "L.ts").contains("\"../pages/L.ts\""));
        assertTrue(rewrite("import { LoginPage } from '../pages/LoginPage.ts';",
                "LoginPage", "L.ts").contains("'../pages/L.ts'"));
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
