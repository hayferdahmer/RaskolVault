// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.storage;

/**
 * Обёртка SQLException для домена: деньги не должны молча теряться —
 * любая ошибка леджера прерывает операцию громко.
 */
public class LedgerException extends RuntimeException {

    public LedgerException(String message, Throwable cause) {
        super(message, cause);
    }
}
