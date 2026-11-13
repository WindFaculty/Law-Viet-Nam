package vn.edu.hsf302.lawrag.observability.service;

/**
 * A kill switch is off or a daily provider/resource cap is reached. Callers
 * must degrade gracefully: never fabricate an answer, never charge quota.
 */
public class BudgetExceededException extends RuntimeException {

    public BudgetExceededException(String message) {
        super(message);
    }
}
