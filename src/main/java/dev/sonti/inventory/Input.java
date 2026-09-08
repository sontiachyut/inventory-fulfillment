package dev.sonti.inventory;

final class Input {
    private Input() {}
    static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Identifiers must be 1-64 letters, digits, dots, underscores or hyphens.");
        }
        return value;
    }
    static void quantity(int quantity) {
        if (quantity < 1 || quantity > 1_000_000) {
            throw new IllegalArgumentException("Quantity must be between 1 and 1000000.");
        }
    }
}
