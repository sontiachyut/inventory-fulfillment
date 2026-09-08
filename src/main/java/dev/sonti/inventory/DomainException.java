package dev.sonti.inventory;

public class DomainException extends RuntimeException {
    private final int status;
    public DomainException(int status, String message) {
        super(message);
        this.status = status;
    }
    public int status() { return status; }
}
