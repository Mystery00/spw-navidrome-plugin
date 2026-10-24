package vip.mystery0.spw.navidrome.ui;

public final class ViewRequestTest {
    public static void main(String[] args) throws Exception {
        Class<?> type;
        try {
            type = Class.forName("vip.mystery0.spw.navidrome.ui.ViewRequest");
        } catch (ClassNotFoundException e) {
            throw new AssertionError("View request isolation missing");
        }
        Object state = type.getConstructor().newInstance();
        long first = (long) type.getMethod("begin").invoke(state);
        long second = (long) type.getMethod("begin").invoke(state);
        if ((boolean) type.getMethod("accept", long.class).invoke(state, first))
            throw new AssertionError("Late response accepted");
        if (!(boolean) type.getMethod("accept", long.class).invoke(state, second))
            throw new AssertionError("Current response rejected");
        type.getMethod("close").invoke(state);
        if ((boolean) type.getMethod("accept", long.class).invoke(state, second))
            throw new AssertionError("Closed response accepted");
        System.out.println("PASS view request isolation");
    }
}
