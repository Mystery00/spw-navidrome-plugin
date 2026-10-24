package vip.mystery0.spw.navidrome.stream;

/** 单区间请求；多区间明确拒绝，不伪装 multipart 响应。 */
public record ByteRange(long start, long end, long suffix) {
    public static ByteRange parse(String value) {
        if (value == null) return null;
        if (!value.matches("bytes=(\\d+-\\d*|-\\d+)")) throw new IllegalArgumentException("Invalid range");
        String[] parts = value.substring(6).split("-",-1);
        if (parts[0].isEmpty()) {
            long n = Long.parseLong(parts[1]); if (n <= 0) throw new IllegalArgumentException("Invalid suffix");
            return new ByteRange(-1,-1,n);
        }
        long start = Long.parseLong(parts[0]), end = parts[1].isEmpty() ? -1 : Long.parseLong(parts[1]);
        if (end != -1 && end < start) throw new IllegalArgumentException("Invalid range bounds");
        return new ByteRange(start,end,0);
    }
    public long[] resolve(long length) {
        if (length <= 0 || start >= length) throw new IllegalArgumentException("Unsatisfiable range");
        return suffix > 0 ? new long[]{Math.max(0,length-suffix),length-1} : new long[]{start,end<0?length-1:Math.min(end,length-1)};
    }
}
