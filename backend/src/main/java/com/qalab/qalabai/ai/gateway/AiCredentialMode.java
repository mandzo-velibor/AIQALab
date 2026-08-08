package com.qalab.qalabai.ai.gateway;

/**
 * Who owns the credentials used for an AI call.
 *
 * <ul>
 *   <li>MANAGED — AI QA Lab provides the configured provider; the account's
 *       token allowance is consumed.</li>
 *   <li>BYOK — the user supplies their own provider credentials; the managed
 *       allowance is NOT consumed (usage is still recorded).</li>
 *   <li>LOCAL — the provider runs locally (e.g. Ollama); no cloud token cost.</li>
 * </ul>
 */
public enum AiCredentialMode {
    MANAGED,
    BYOK,
    LOCAL
}
