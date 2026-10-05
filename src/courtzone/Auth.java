package courtzone;

import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** SHA-256 + salt hashing (never store plain text) + in-memory cookie sessions. */
public class Auth {
    private static final Map<String, String> sessions = new ConcurrentHashMap<>(); // sessionId -> userId

    public static String genSalt() { return UUID.randomUUID().toString().replace("-", "").substring(0, 16); }

    public static String hash(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest((salt + "::" + password).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static boolean verify(String password, String salt, String expected) {
        return hash(password, salt).equals(expected);
    }

    public static String createSession(String userId) {
        String sid = UUID.randomUUID().toString();
        sessions.put(sid, userId);
        return sid;
    }

    public static String getUserId(String sid) { return sid == null ? null : sessions.get(sid); }
    public static void destroy(String sid) { if (sid != null) sessions.remove(sid); }
}
