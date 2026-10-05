package courtzone;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** CourtZone Arena — single-jar web app, no dependencies. Run: java -cp out courtzone.App */
public class App {
    static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "3000"));
    static final Map<String, List<String>> COURTS = Map.of(
        "basketball", List.of("A", "B"),
        "volleyball", List.of("A", "B"),
        "badminton", List.of("A", "B"),
        "billiard", List.of("A", "B", "C"));

    public static void main(String[] args) throws Exception {
        Db.init();
        HttpServer s = HttpServer.create(new InetSocketAddress(PORT), 0);
        s.createContext("/static/", App::serveStatic);
        s.createContext("/", App::route);
        s.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        s.start();
        System.out.println("CourtZone Arena running at http://localhost:" + PORT);
    }

    // ---------- infrastructure ----------
    static void serveStatic(HttpExchange ex) throws IOException {
        try {
            String p = ex.getRequestURI().getPath().replace("/static/", "");
            if (p.isEmpty() || p.contains("..")) { send(ex, 404, "Not found", "text/plain"); return; }
            Path f = Paths.get("public", p);
            if (!Files.exists(f) || Files.isDirectory(f)) { send(ex, 404, "Not found", "text/plain"); return; }
            String ct = "application/octet-stream";
            if (p.endsWith(".css")) ct = "text/css";
            else if (p.endsWith(".svg")) ct = "image/svg+xml";
            else if (p.endsWith(".js")) ct = "text/javascript";
            else if (p.endsWith(".html")) ct = "text/html; charset=UTF-8";
            else if (p.endsWith(".png")) ct = "image/png";
            sendBytes(ex, 200, Files.readAllBytes(f), ct);
        } catch (Exception e) { send(ex, 500, "Static error: " + e, "text/plain"); }
    }

    static void route(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            URI uri = ex.getRequestURI();
            String path = uri.getPath();
            Map<String, String> q = parseQuery(uri.getRawQuery());
            Map<String, String> form = method.equals("POST") ? parseForm(ex) : Map.of();
            Db.User user = currentUser(ex);

            if (path.equals("/") && method.equals("GET")) returnHtml(ex, Html.layout("Home", Html.landing(Db.loadRates()), user));
            else if (path.equals("/login") && method.equals("GET")) returnHtml(ex, Html.layout("Login", Html.loginForm(null), user));
            else if (path.equals("/login") && method.equals("POST")) doLogin(ex, form);
            else if (path.equals("/register") && method.equals("GET")) returnHtml(ex, Html.layout("Register", Html.registerForm(null), user));
            else if (path.equals("/register") && method.equals("POST")) doRegister(ex, form);
            else if (path.equals("/logout")) doLogout(ex);
            else if (path.equals("/dashboard") && method.equals("GET")) { if (!needLogin(ex, user)) return; returnHtml(ex, Html.layout("Dashboard", dashboard(user, q), user)); }
            else if (path.equals("/book") && method.equals("GET")) { if (!needLogin(ex, user)) return; returnHtml(ex, Html.layout("Book", bookForm(user, q, null), user)); }
            else if (path.equals("/book") && method.equals("POST")) { if (!needLogin(ex, user)) return; doBook(ex, user, form); }
            else if (path.equals("/my-bookings")) { if (!needLogin(ex, user)) return; returnHtml(ex, Html.layout("My Bookings", myBookings(user, q.getOrDefault("msg", null)), user)); }
            else if (path.equals("/edit") && method.equals("GET")) { if (!needLogin(ex, user)) return; editGet(ex, user, q); }
            else if (path.equals("/edit") && method.equals("POST")) { if (!needLogin(ex, user)) return; editPost(ex, user, form); }
            else if (path.equals("/cancel") && method.equals("POST")) { if (!needLogin(ex, user)) return; doCancel(ex, user, form, false); }
            else if (path.equals("/delete") && method.equals("POST")) { if (!needLogin(ex, user)) return; doDelete(ex, user, form); }
            else if (path.equals("/wallet")) { if (!needLogin(ex, user)) return; returnHtml(ex, Html.layout("Wallet", wallet(user), user)); }
            else if (path.equals("/admin") && method.equals("GET")) { if (!needAdmin(ex, user)) return; returnHtml(ex, Html.layout("Admin", adminPage(q), user)); }
            else if (path.equals("/admin/confirm") && method.equals("POST")) { if (!needAdmin(ex, user)) return; adminSetStatus(ex, form, "confirmed", false); }
            else if (path.equals("/admin/cancel") && method.equals("POST")) { if (!needAdmin(ex, user)) return; doCancel(ex, user, form, true); }
            else if (path.equals("/admin/topup") && method.equals("POST")) { if (!needAdmin(ex, user)) return; adminTopup(ex, form); }
            else if (path.equals("/admin/rate") && method.equals("POST")) { if (!needAdmin(ex, user)) return; adminRate(ex, form); }
            else if (path.equals("/admin/delete") && method.equals("POST")) { if (!needAdmin(ex, user)) return; adminHardDelete(ex, form); }
            else send(ex, 404, "Not found. <a href='/'>Home</a>", "text/html");
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, "Server error: " + Html.esc(String.valueOf(e.getMessage())), "text/html");
        }
    }

    // ---------- auth ----------
    static void doLogin(HttpExchange ex, Map<String, String> f) throws IOException {
        Db.User u = Db.findByEmail(f.getOrDefault("email", "").trim());
        if (u == null || !Auth.verify(f.getOrDefault("password", ""), u.salt, u.hash)) {
            returnHtml(ex, Html.layout("Login", Html.loginForm("Invalid email or password."), null)); return;
        }
        String sid = Auth.createSession(u.id);
        ex.getResponseHeaders().add("Set-Cookie", "SESSIONID=" + sid + "; Path=/; HttpOnly");
        redirect(ex, u.role.equals("admin") ? "/admin" : "/dashboard");
    }

    static void doRegister(HttpExchange ex, Map<String, String> f) throws IOException {
        String name = f.getOrDefault("name", "").trim(), email = f.getOrDefault("email", "").trim().toLowerCase(), pw = f.getOrDefault("password", "");
        String err = null;
        if (name.length() < 2) err = "Name is required.";
        else if (!email.contains("@")) err = "Enter a valid email.";
        else if (pw.length() < 6) err = "Password must be at least 6 characters.";
        else if (email.contains("|")) err = "Invalid email.";
        else if (Db.findByEmail(email) != null) err = "Email already registered. Please login.";
        if (err != null) { returnHtml(ex, Html.layout("Register", Html.registerForm(err), null)); return; }
        String salt = Auth.genSalt();
        List<Db.User> users = Db.loadUsers();
        Db.User nu = new Db.User(Db.nextId("U"), name, email, Auth.hash(pw, salt), salt, "customer", 0);
        users.add(nu); Db.saveUsers(users);
        Db.addTxn(nu.id, "info", 0, "Account created");
        String sid = Auth.createSession(nu.id);
        ex.getResponseHeaders().add("Set-Cookie", "SESSIONID=" + sid + "; Path=/; HttpOnly");
        redirect(ex, "/dashboard");
    }

    static void doLogout(HttpExchange ex) throws IOException {
        String sid = cookie(ex, "SESSIONID");
        Auth.destroy(sid);
        ex.getResponseHeaders().add("Set-Cookie", "SESSIONID=; Path=/; Max-Age=0");
        redirect(ex, "/");
    }

    static Db.User currentUser(HttpExchange ex) throws IOException {
        String sid = cookie(ex, "SESSIONID");
        String uid = Auth.getUserId(sid);
        return uid == null ? null : Db.findById(uid);
    }

    static boolean needLogin(HttpExchange ex, Db.User u) throws IOException {
        if (u == null) { redirect(ex, "/login"); return false; } return true;
    }
    static boolean needAdmin(HttpExchange ex, Db.User u) throws IOException {
        if (u == null) { redirect(ex, "/login"); return false; }
        if (!u.role.equals("admin")) { send(ex, 403, "Admins only.", "text/html"); return false; }
        return true;
    }

    // ---------- booking rules ----------
    static int toMin(String t) { String[] p = t.split(":"); return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]); }
    static boolean overlap(String s1, String e1, String s2, String e2) { return toMin(s1) < toMin(e2) && toMin(e2) > toMin(s2); }

    /** Returns error message or null if valid. excludeId skips a reservation (for edit). */
    static String validateBooking(Db.User user, String sport, String court, String date, String start, String end, String excludeId) throws IOException {
        sport = sport.toLowerCase();
        if (!COURTS.containsKey(sport)) return "Invalid sport.";
        if (!COURTS.get(sport).contains(court)) return "Invalid court for " + sport + ". Valid: " + COURTS.get(sport);
        LocalDate d;
        try { d = LocalDate.parse(date); } catch (Exception e) { return "Invalid date (use YYYY-MM-DD)."; }
        if (d.isBefore(LocalDate.now())) return "Date cannot be in the past.";
        int s, e;
        try { s = toMin(start); e = toMin(end); } catch (Exception ex) { return "Invalid time (use HH:MM, 24h)."; }
        if (s >= e) return "Start must be before end.";
        if (s < 8 * 60) return "Facility opens at 8:00 AM.";
        if (e > 24 * 60) return "Facility closes at 12:00 AM (midnight).";
        double hrs = (e - s) / 60.0;
        if (hrs < 1) return "Minimum booking is 1 hour.";
        if (hrs > 8) return "Maximum booking is 8 hours.";
        List<Db.Reservation> all = Db.loadReservations();
        for (Db.Reservation r : all) {
            if (r.id.equals(excludeId)) continue;
            if (!r.status.equals("confirmed")) continue;
            if (r.sport.equals(sport) && r.court.equals(court) && r.date.equals(date) && overlap(start, end, r.start, r.end))
                return "Slot taken: " + sport + " Court " + court + " already booked " + r.start + "–" + r.end + " on " + date + ". Pick another time/court.";
        }
        if (!user.role.equals("admin")) {
            for (Db.Reservation r : all) {
                if (r.id.equals(excludeId)) continue;
                if (!r.status.equals("confirmed")) continue;
                if (r.userId.equals(user.id) && r.sport.equals(sport) && r.date.equals(date))
                    return "Rule: only 1 reservation per sport per day. You already have " + sport + " on " + date + ".";
            }
        }
        return null;
    }

    static void doBook(HttpExchange ex, Db.User user, Map<String, String> f) throws IOException {
        String sport = f.getOrDefault("sport", "").toLowerCase(), court = f.getOrDefault("court", "A").toUpperCase();
        String date = f.getOrDefault("date", ""), start = f.getOrDefault("start", ""), end = f.getOrDefault("end", "");
        String err = validateBooking(user, sport, court, date, start, end, null);
        Map<String, Integer> rates = Db.loadRates();
        double hrs = 0; int price = 0;
        try { hrs = (toMin(end) - toMin(start)) / 60.0; price = (int) Math.round(rates.get(sport) * hrs); } catch (Exception ignored) {}
        if (err == null && user.role.equals("customer")) {
            Db.User fresh = Db.findById(user.id);
            if (fresh.balance < price) err = "Insufficient balance: need ₱" + price + " but you have ₱" + fresh.balance + ". Ask admin to top-up your wallet.";
        }
        if (err != null) { returnHtml(ex, Html.layout("Book", bookForm(user, toMap(f), err), user)); return; }
        // deduct + save
        List<Db.User> users = Db.loadUsers();
        for (Db.User u : users) if (u.id.equals(user.id) && u.role.equals("customer")) u.balance -= price;
        Db.saveUsers(users);
        List<Db.Reservation> all = Db.loadReservations();
        Db.Reservation r = new Db.Reservation(Db.nextId("R"), user.id, sport, court, date, start, end, hrs, price, "confirmed", LocalDateTime.now().toString());
        all.add(r); Db.saveReservations(all);
        if (user.role.equals("customer")) Db.addTxn(user.id, "pay", -price, "Booking " + r.id + " " + sport + " " + court + " " + date + " " + start + "-" + end);
        redirect(ex, "/my-bookings");
    }

    static void doCancel(HttpExchange ex, Db.User actor, Map<String, String> f, boolean isAdmin) throws IOException {
        String id = f.getOrDefault("id", "");
        List<Db.Reservation> all = Db.loadReservations();
        Db.Reservation target = null;
        for (Db.Reservation r : all) if (r.id.equals(id)) target = r;
        if (target == null) { send(ex, 404, "Booking not found", "text/html"); return; }
        if (!isAdmin && !target.userId.equals(actor.id)) { send(ex, 403, "Not yours.", "text/html"); return; }
        if (target.status.equals("cancelled")) { redirect(ex, isAdmin ? "/admin" : "/my-bookings"); return; }
        target.status = "cancelled";
        Db.saveReservations(all);
        // refund to owner's balance
        List<Db.User> users = Db.loadUsers();
        for (Db.User u : users) if (u.id.equals(target.userId)) u.balance += target.price;
        Db.saveUsers(users);
        Db.addTxn(target.userId, "refund", target.price, "Refund booking " + target.id + (isAdmin ? " (admin cancelled)" : " (user cancelled)"));
        redirect(ex, isAdmin ? "/admin" : "/my-bookings");
    }

    static void doDelete(HttpExchange ex, Db.User actor, Map<String, String> f) throws IOException {
        String id = f.getOrDefault("id", "");
        List<Db.Reservation> all = Db.loadReservations();
        Db.Reservation target = null;
        for (Db.Reservation r : all) if (r.id.equals(id)) target = r;
        if (target == null) { send(ex, 404, "Not found", "text/html"); return; }
        boolean admin = actor.role.equals("admin");
        if (!admin && !target.userId.equals(actor.id)) { send(ex, 403, "Not yours.", "text/html"); return; }
        if (!admin && !target.status.equals("cancelled")) { send(ex, 400, "Cancel first before deleting. <a href='/my-bookings'>Back</a>", "text/html"); return; }
        final String fid = id;
        all.removeIf(r -> r.id.equals(fid));
        Db.saveReservations(all);
        redirect(ex, admin ? "/admin" : "/my-bookings");
    }

    // ---------- pages ----------
    static String dashboard(Db.User u, Map<String, String> q) throws IOException {
        Map<String, Integer> rates = Db.loadRates();
        List<Db.Reservation> all = Db.loadReservations();
        List<Db.Reservation> mine = new ArrayList<>();
        for (Db.Reservation r : all) if (r.userId.equals(u.id) && r.status.equals("confirmed")) mine.add(r);
        mine.sort(Comparator.comparing((Db.Reservation r) -> r.date).thenComparing(r -> r.start));
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>Dashboard</h2><p>Balance: <b>₱").append(u.balance).append("</b> • Hours: 8AM–12AM • <a href='/wallet'>Wallet history</a></p>");
        sb.append("<div class='grid'>");
        for (String s : List.of("basketball", "volleyball", "badminton", "billiard"))
            sb.append("<div class='card sm'><h3 style='text-transform:capitalize'>").append(s).append("</h3><p class='price'>₱").append(rates.get(s)).append("/hr</p><a class='btn' href='/book?sport=").append(s).append("'>Book</a></div>");
        sb.append("</div>");
        sb.append("<h3>Check availability</h3><form class='form inline' method='GET' action='/dashboard'>"
            + sportSelect(q.getOrDefault("sport", "basketball")) + courtSelect(q.getOrDefault("court", "A"))
            + "<label>Date<input type='date' name='date' value='" + Html.esc(q.getOrDefault("date", LocalDate.now().toString())) + "'></label>"
            + "<button class='btn' type='submit'>Check</button></form>");
        if (q.containsKey("date")) sb.append(availabilityTable(q.getOrDefault("sport", "basketball"), q.getOrDefault("court", "A"), q.getOrDefault("date", LocalDate.now().toString())));
        sb.append("<h3>Your upcoming (").append(mine.size()).append(")</h3>");
        if (mine.isEmpty()) sb.append("<p class='muted'>No bookings yet. <a href='/book'>Book now</a></p>");
        else { sb.append("<div class='tablewrap'><table><tr><th>Sport</th><th>Court</th><th>Date</th><th>Time</th><th>Price</th></tr>");
            for (Db.Reservation r : mine.subList(0, Math.min(5, mine.size())))
                sb.append("<tr><td>").append(r.sport).append("</td><td>").append(r.court).append("</td><td>").append(r.date).append("</td><td>").append(r.start).append("–").append(r.end).append("</td><td>₱").append(r.price).append("</td></tr>");
            sb.append("</table></div><p><a href='/my-bookings'>View all / edit →</a></p>");
        }
        return sb.toString();
    }

    static String availabilityTable(String sport, String court, String date) throws IOException {
        sport = sport.toLowerCase();
        StringBuilder sb = new StringBuilder("<div class='tablewrap'><table><caption>Booked: " + Html.esc(sport) + " Court " + Html.esc(court) + " on " + Html.esc(date) + "</caption><tr><th>Time</th><th>Status</th></tr>");
        boolean any = false;
        for (Db.Reservation r : Db.loadReservations())
            if (r.sport.equals(sport) && r.court.equals(court) && r.date.equals(date) && r.status.equals("confirmed")) {
                sb.append("<tr><td>").append(r.start).append("–").append(r.end).append("</td><td>⛔ Taken</td></tr>"); any = true;
            }
        if (!any) sb.append("<tr><td colspan='2'>✅ Whole day free 8AM–12AM</td></tr>");
        return sb.append("</table></div>").toString();
    }

    static String sportSelect(String sel) {
        StringBuilder sb = new StringBuilder("<label>Sport<select name='sport'>");
        for (String s : List.of("basketball", "volleyball", "badminton", "billiard"))
            sb.append("<option value='").append(s).append("'").append(s.equals(sel) ? " selected" : "").append(">").append(s).append("</option>");
        return sb.append("</select></label>").toString();
    }
    static String courtSelect(String sel) {
        StringBuilder sb = new StringBuilder("<label>Court<select name='court'>");
        for (String c : List.of("A", "B", "C"))
            sb.append("<option").append(c.equals(sel) ? " selected" : "").append(">").append(c).append("</option>");
        return sb.append("</select></label>").toString();
    }

    static String bookForm(Db.User u, Map<String, String> v, String err) throws IOException {
        Map<String, Integer> rates = Db.loadRates();
        String sport = v.getOrDefault("sport", "basketball").toLowerCase();
        String court = v.getOrDefault("court", "A"), date = v.getOrDefault("date", LocalDate.now().toString());
        String start = v.getOrDefault("start", "09:00"), end = v.getOrDefault("end", "10:00");
        StringBuilder sb = new StringBuilder("<h2>New Reservation</h2>");
        if (err != null) sb.append("<p class='err'>").append(Html.esc(err)).append("</p>");
        sb.append("<p>Balance: <b>₱").append(u.balance).append("</b> • Open 8:00–24:00 • Min 1hr</p>");
        sb.append("<form class='form' method='POST' action='/book' onsubmit='return confirmDel(\"Confirm booking? ₱\"+document.getElementById(\"est\").textContent+\" will be deducted.\")'>");
        sb.append(sportSelect(sport));
        sb.append("<label>Court/Table<select name='court' id='court'></select></label>");
        sb.append("<label>Date<input type='date' name='date' value='").append(Html.esc(date)).append("' min='").append(LocalDate.now()).append("' required></label>");
        sb.append("<label>Start<input type='time' name='start' value='").append(Html.esc(start)).append("' min='08:00' max='23:00' required></label>");
        sb.append("<label>End<input type='time' name='end' value='").append(Html.esc(end)).append("' min='09:00' max='24:00' required></label>");
        sb.append("<p>Estimated: ₱<b id='est'>0</b></p><button class='btn big' type='submit'>Confirm Booking</button></form>");
        sb.append("<script>const rates={");
        rates.forEach((k, val) -> sb.append("'").append(k).append("':").append(val).append(","));
        sb.append("};const courts={basketball:['A','B'],volleyball:['A','B'],badminton:['A','B'],billiard:['A','B','C']};");
        sb.append("const ss=document.querySelector(\"select[name='sport']\"),cs=document.getElementById('court');"
            + "function fillC(){cs.innerHTML='';courts[ss.value].forEach(c=>{const o=document.createElement('option');o.textContent=c;if(c==='" + court.replace("'", "") + "')o.selected=true;cs.appendChild(o);});calc();}"
            + "function calc(){const s=document.querySelector(\"input[name='start']\").value,e=document.querySelector(\"input[name='end']\").value;"
            + "if(s&&e){const [sh,sm]=s.split(':').map(Number),[eh,em]=e.split(':').map(Number);const h=(eh*60+em-sh*60-sm)/60;"
            + "document.getElementById('est').textContent=h>0?Math.round(h*rates[ss.value]):0;}}"
            + "ss.onchange=fillC;document.querySelectorAll(\"input[type='time']\").forEach(i=>i.onchange=calc);fillC();</script>");
        sb.append(availabilityTable(sport, court, date));
        return sb.toString();
    }

    static String myBookings(Db.User u, String msg) throws IOException {
        List<Db.Reservation> all = Db.loadReservations();
        List<Db.Reservation> mine = new ArrayList<>();
        for (Db.Reservation r : all) if (r.userId.equals(u.id)) mine.add(r);
        mine.sort(Comparator.comparing((Db.Reservation r) -> r.date).reversed());
        StringBuilder sb = new StringBuilder("<h2>My Reservations (CRUD)</h2><p><a class='btn' href='/book'>+ New booking</a></p>");
        if (mine.isEmpty()) return sb.append("<p class='muted'>No reservations yet.</p>").toString();
        sb.append("<div class='tablewrap'><table><tr><th>ID</th><th>Sport</th><th>Court</th><th>Date</th><th>Time</th><th>Hrs</th><th>Price</th><th>Status</th><th>Actions</th></tr>");
        for (Db.Reservation r : mine) {
            sb.append("<tr><td>").append(r.id).append("</td><td>").append(r.sport).append("</td><td>").append(r.court).append("</td><td>").append(r.date).append("</td><td>").append(r.start).append("–").append(r.end).append("</td><td>").append(r.hours).append("</td><td>₱").append(r.price).append("</td><td>").append(r.status).append("</td><td class='acts'>");
            if (r.status.equals("confirmed")) {
                sb.append("<a class='btn xs' href='/edit?id=").append(r.id).append("'>Edit</a> ");
                sb.append("<form style='display:inline' method='POST' action='/cancel' onsubmit='return confirmDel(\"Cancel ").append(r.id).append("? ₱").append(r.price).append(" will be refunded.\")'><input type='hidden' name='id' value='").append(r.id).append("'><button class='btn xs warn' type='submit'>Cancel</button></form> ");
            }
            if (r.status.equals("cancelled"))
                sb.append("<form style='display:inline' method='POST' action='/delete' onsubmit='return confirmDel(\"Permanently delete ").append(r.id).append("?\")'><input type='hidden' name='id' value='").append(r.id).append("'><button class='btn xs danger' type='submit'>Delete</button></form>");
            sb.append("</td></tr>");
        }
        return sb.append("</table></div>").toString();
    }

    static void editGet(HttpExchange ex, Db.User u, Map<String, String> q) throws IOException {
        Db.Reservation r = null;
        for (Db.Reservation x : Db.loadReservations()) if (x.id.equals(q.getOrDefault("id", ""))) r = x;
        if (r == null) { send(ex, 404, "Not found", "text/html"); return; }
        if (!u.role.equals("admin") && !r.userId.equals(u.id)) { send(ex, 403, "Not yours", "text/html"); return; }
        String body = "<h2>Edit " + r.id + "</h2><form class='form' method='POST' action='/edit'>"
            + "<input type='hidden' name='id' value='" + r.id + "'>"
            + sportSelect(r.sport)
            + "<label>Court<input name='court' value='" + r.court + "' maxlength='1' required></label>"
            + "<label>Date<input type='date' name='date' value='" + r.date + "' required></label>"
            + "<label>Start<input type='time' name='start' value='" + r.start + "' required></label>"
            + "<label>End<input type='time' name='end' value='" + r.end + "' required></label>"
            + "<button class='btn big' type='submit'>Save changes</button></form><p><a href='/my-bookings'>Back</a></p>";
        returnHtml(ex, Html.layout("Edit", body, u));
    }

    static void editPost(HttpExchange ex, Db.User u, Map<String, String> f) throws IOException {
        String id = f.getOrDefault("id", "");
        List<Db.Reservation> all = Db.loadReservations();
        Db.Reservation r = null;
        for (Db.Reservation x : all) if (x.id.equals(id)) r = x;
        if (r == null) { send(ex, 404, "Not found", "text/html"); return; }
        if (!u.role.equals("admin") && !r.userId.equals(u.id)) { send(ex, 403, "Not yours", "text/html"); return; }
        String sport = f.getOrDefault("sport", r.sport).toLowerCase(), court = f.getOrDefault("court", r.court).toUpperCase();
        String date = f.getOrDefault("date", r.date), start = f.getOrDefault("start", r.start), end = f.getOrDefault("end", r.end);
        // allow owner lookup for rule check (use owner as user for 1/day rule)
        Db.User owner = Db.findById(r.userId);
        String err = validateBooking(owner == null ? u : owner, sport, court, date, start, end, r.id);
        Map<String, Integer> rates = Db.loadRates();
        double hrs = 0; int price = r.price;
        try { hrs = (toMin(end) - toMin(start)) / 60.0; price = (int) Math.round(rates.get(sport) * hrs); } catch (Exception ignored) {}
        if (err == null && price > r.price && owner != null && !u.role.equals("admin")) {
            Db.User fresh = Db.findById(owner.id);
            if (fresh.balance < (price - r.price)) err = "Insufficient balance for price increase: need extra ₱" + (price - r.price) + ".";
        }
        if (err != null) { returnHtml(ex, Html.layout("Edit", "<p class='err'>" + Html.esc(err) + "</p><p><a href='/edit?id=" + r.id + "'>Back</a></p>", u)); return; }
        int diff = price - r.price;
        if (diff != 0 && owner != null) {
            List<Db.User> users = Db.loadUsers();
            for (Db.User x : users) if (x.id.equals(owner.id)) x.balance -= diff;
            Db.saveUsers(users);
            Db.addTxn(owner.id, diff > 0 ? "pay" : "refund", -diff, "Reschedule " + r.id + " price adj.");
        }
        r.sport = sport; r.court = court; r.date = date; r.start = start; r.end = end; r.hours = hrs; r.price = price;
        Db.saveReservations(all);
        redirect(ex, u.role.equals("admin") ? "/admin" : "/my-bookings");
    }

    static String wallet(Db.User u) throws IOException {
        Db.User fresh = Db.findById(u.id);
        StringBuilder sb = new StringBuilder("<h2>Wallet</h2><p>Balance: <b>₱" + fresh.balance + "</b></p>"
            + "<p class='muted'>Top-ups are done by admin. Cancellations auto-refund here.</p><div class='tablewrap'><table><tr><th>Date</th><th>Type</th><th>Amount</th><th>Note</th></tr>");
        List<Db.Txn> tx = Db.loadTxns();
        tx.sort(Comparator.comparing((Db.Txn t) -> t.date).reversed());
        for (Db.Txn t : tx) if (t.userId.equals(u.id))
            sb.append("<tr><td>").append(t.date.substring(0, 16).replace("T", " ")).append("</td><td>").append(t.type).append("</td><td>").append(t.amount >= 0 ? "+" : "").append(t.amount).append("</td><td>").append(Html.esc(t.note)).append("</td></tr>");
        return sb.append("</table></div>").toString();
    }

    static String adminPage(Map<String, String> q) throws IOException {
        String tab = q.getOrDefault("tab", "reservations");
        Map<String, Integer> rates = Db.loadRates();
        StringBuilder sb = new StringBuilder("<h2>Admin Panel</h2><p class='tabs'>"
            + "<a class='" + (tab.equals("reservations") ? "on" : "") + "' href='/admin?tab=reservations'>Reservations</a>"
            + "<a class='" + (tab.equals("users") ? "on" : "") + "' href='/admin?tab=users'>Users & Balance</a>"
            + "<a class='" + (tab.equals("rates") ? "on" : "") + "' href='/admin?tab=rates'>Rates</a></p>");
        if (tab.equals("users")) {
            sb.append("<div class='tablewrap'><table><tr><th>Name</th><th>Email</th><th>Role</th><th>Balance</th><th>Top-up</th></tr>");
            for (Db.User x : Db.loadUsers()) {
                sb.append("<tr><td>").append(Html.esc(x.name)).append("</td><td>").append(Html.esc(x.email)).append("</td><td>").append(x.role).append("</td><td>₱").append(x.balance).append("</td><td>")
                  .append("<form class='inline' method='POST' action='/admin/topup'><input type='hidden' name='userId' value='").append(x.id).append("'><input name='amount' type='number' placeholder='+500' required style='width:90px'><button class='btn xs' type='submit'>Apply</button></form></td></tr>");
            }
            sb.append("</table></div><p class='muted'>Positive = add funds, negative = deduct.</p>");
        } else if (tab.equals("rates")) {
            sb.append("<form class='form' method='POST' action='/admin/rate'>");
            for (String s : List.of("basketball", "volleyball", "badminton", "billiard"))
                sb.append("<label>").append(s).append(" (₱/hr)<input name='").append(s).append("' type='number' min='0' value='").append(rates.get(s)).append("' required></label>");
            sb.append("<button class='btn' type='submit'>Save rates</button></form>");
        } else {
            sb.append("<div class='tablewrap'><table><tr><th>ID</th><th>User</th><th>Sport</th><th>Court</th><th>Date</th><th>Time</th><th>Price</th><th>Status</th><th>Actions</th></tr>");
            List<Db.Reservation> all = Db.loadReservations();
            all.sort(Comparator.comparing((Db.Reservation r) -> r.date).thenComparing(r -> r.start));
            for (Db.Reservation r : all) {
                Db.User owner = Db.findById(r.userId);
                sb.append("<tr><td>").append(r.id).append("</td><td>").append(owner == null ? "?" : Html.esc(owner.email)).append("</td><td>").append(r.sport).append("</td><td>").append(r.court).append("</td><td>").append(r.date).append("</td><td>").append(r.start).append("–").append(r.end).append("</td><td>₱").append(r.price).append("</td><td>").append(r.status).append("</td><td class='acts'>");
                if (r.status.equals("confirmed"))
                    sb.append("<form style='display:inline' method='POST' action='/admin/cancel' onsubmit='return confirmDel(\"Cancel ").append(r.id).append("? ₱").append(r.price).append(" refunded to user.\")'><input type='hidden' name='id' value='").append(r.id).append("'><button class='btn xs warn' type='submit'>Cancel+Refund</button></form> ");
                sb.append("<a class='btn xs' href='/edit?id=").append(r.id).append("'>Edit</a> ");
                sb.append("<form style='display:inline' method='POST' action='/admin/delete' onsubmit='return confirmDel(\"Permanently delete ").append(r.id).append("?\")'><input type='hidden' name='id' value='").append(r.id).append("'><button class='btn xs danger' type='submit'>Delete</button></form>");
                sb.append("</td></tr>");
            }
            sb.append("</table></div>");
        }
        return sb.toString();
    }

    static void adminSetStatus(HttpExchange ex, Map<String, String> f, String status, boolean refund) throws IOException {
        List<Db.Reservation> all = Db.loadReservations();
        for (Db.Reservation r : all) if (r.id.equals(f.getOrDefault("id", ""))) r.status = status;
        Db.saveReservations(all);
        redirect(ex, "/admin");
    }
    static void adminHardDelete(HttpExchange ex, Map<String, String> f) throws IOException {
        List<Db.Reservation> all = Db.loadReservations();
        final String id = f.getOrDefault("id", "");
        all.removeIf(r -> r.id.equals(id));
        Db.saveReservations(all);
        redirect(ex, "/admin");
    }
    static void adminTopup(HttpExchange ex, Map<String, String> f) throws IOException {
        String uid = f.getOrDefault("userId", ""); int amt;
        try { amt = Integer.parseInt(f.getOrDefault("amount", "0").trim()); } catch (Exception e) { redirect(ex, "/admin?tab=users"); return; }
        List<Db.User> users = Db.loadUsers();
        for (Db.User x : users) if (x.id.equals(uid)) x.balance += amt;
        Db.saveUsers(users);
        Db.addTxn(uid, amt >= 0 ? "topup" : "adjust", amt, "Admin balance update");
        redirect(ex, "/admin?tab=users");
    }
    static void adminRate(HttpExchange ex, Map<String, String> f) throws IOException {
        Map<String, Integer> m = Db.loadRates();
        for (String s : List.of("basketball", "volleyball", "badminton", "billiard"))
            try { m.put(s, Math.max(0, Integer.parseInt(f.getOrDefault(s, String.valueOf(m.get(s))).trim()))); } catch (Exception ignored) {}
        Db.saveRates(m);
        redirect(ex, "/admin?tab=rates");
    }

    // ---------- http helpers ----------
    static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String kv : raw.split("&")) {
            String[] p = kv.split("=", 2);
            try { m.put(URLDecoder.decode(p[0], "UTF-8"), p.length > 1 ? URLDecoder.decode(p[1], "UTF-8") : ""); } catch (Exception ignored) {}
        }
        return m;
    }
    static Map<String, String> parseForm(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), "UTF-8");
        return parseQuery(body);
    }
    static Map<String, String> toMap(Map<String, String> f) { return new HashMap<>(f); }
    static String cookie(HttpExchange ex, String name) {
        List<String> cl = ex.getRequestHeaders().get("Cookie");
        if (cl == null) return null;
        for (String c : cl) for (String kv : c.split(";")) {
            String[] p = kv.trim().split("=", 2);
            if (p.length == 2 && p[0].trim().equals(name)) return p[1].trim();
        }
        return null;
    }
    static void send(HttpExchange ex, int code, String body, String ct) throws IOException {
        byte[] b = body.getBytes("UTF-8");
        ex.getResponseHeaders().set("Content-Type", ct.contains("charset") ? ct : ct + "; charset=UTF-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }
    static void sendBytes(HttpExchange ex, int code, byte[] b, String ct) throws IOException {
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }
    static void returnHtml(HttpExchange ex, String html) throws IOException { send(ex, 200, html, "text/html"); }
    static void redirect(HttpExchange ex, String loc) throws IOException {
        ex.getResponseHeaders().add("Location", loc);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }
}
