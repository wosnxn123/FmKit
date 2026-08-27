package dev.fm.shop.tx;

/**
 * Outcome of one buy/sell/pay attempt.
 *
 * <p>{@code qty} is what actually moved, which may be less than requested: a
 * daily quota or inventory space can clamp an order, and reporting the clamped
 * amount is friendlier than refusing the whole action.
 *
 * @param key   message key to show the player
 * @param gross value before fees, in cents
 * @param fee   fee taken (sell) or added (buy), in cents
 * @param net   what the balance actually moved by, in cents
 * @param need  shortfall when the balance was insufficient
 * @param limit configured daily quota, when the quota blocked the action
 * @param left  quota remaining today
 */
public record TxResult(boolean ok,
                       String key,
                       int qty,
                       long gross,
                       long fee,
                       long net,
                       long need,
                       int limit,
                       int left) {

    public static TxResult fail(String key) {
        return new TxResult(false, key, 0, 0, 0, 0, 0, 0, 0);
    }

    public static TxResult quota(String key, int limit, int left) {
        return new TxResult(false, key, 0, 0, 0, 0, 0, limit, left);
    }

    public static TxResult poor(long need) {
        return new TxResult(false, "no-money", 0, 0, 0, 0, need, 0, 0);
    }

    public static TxResult done(String key, int qty, long gross, long fee, long net) {
        return new TxResult(true, key, qty, gross, fee, net, 0, 0, 0);
    }
}
