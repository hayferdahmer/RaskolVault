// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.storage;

/**
 * Исключение леджера (1.2.4.1): добавлен конструктор (String) для guardNonNegative.
 */
public class LedgerException extends RuntimeException {

    public LedgerException(String message) {
        super(message);
    }

    public LedgerException(String message, Throwable cause) {
        super(message, cause);
    }
}
