# CourtZone Arena — Multi-Sport Facility Reservation System

**Business:** CourtZone Arena, a sports facility in Manila, PH with Basketball, Volleyball, Badminton (Courts A & B each) and Billiard (Tables A, B & C).
**What it does:** Customers check real-time availability (8AM–12AM) and reserve a court/table by date + time. Payment is deducted from an in-app wallet. Admin can top-up balances, cancel bookings (auto-refund), and edit rates.
**Core CRUD entity:** Reservations (plus Users & Wallet as supporting entities).

## Problem being solved
Walk-in logbooks cause double-booking, time conflicts, and no refund trail. This system enforces: no overlapping bookings on the same court, 1 reservation per sport per user per day, opening-hours validation, and wallet deduct/refund so cancellations are traceable.

## Feature list
- **Landing page (`/`):** business name, description, 4 sports + rates + images, hours, `Book Now / Login` CTA, mobile-responsive.
- **Users:** register (name, email, password), login, logout. Passwords hashed with SHA-256 + per-user salt (never plain text). Roles: `admin` vs `customer`. Protected pages redirect to `/login`.
- **CRUD on Reservations:** Create (`/book`), Read (`/dashboard`, `/my-bookings`, `/admin`, availability table), Update (`/edit` reschedule with price-adjust), Delete (`/delete` hard-delete, `/cancel` soft-cancel). Validation + `confirm()` on cancel/delete. Data persists in `./data/*.txt` (survives restart).
- **Business rules:**
  - Hours 8:00–24:00, min 1hr, max 8hrs, no past dates.
  - Overlap blocked per sport+court+date. Other courts/times still bookable.
  - 1 reservation / sport / user / day (cancelled excluded; admins exempt).
  - Rates (PH standard, editable by admin): Basketball ₱600/hr, Volleyball ₱500/hr, Badminton ₱200/hr, Billiard ₱120/hr. Total = rate × hours.
  - Wallet: booking deducts; user/admin cancel refunds full price; admin top-up via Users tab.
  - Courts: basketball/volleyball/badminton = A,B. Billiard = A,B,C.

## Tech stack
- Java 17+ only, no dependencies. `com.sun.net.httpserver.HttpServer` (JDK built-in).
- File storage in `./data/` (pipe-separated text). HTML rendered server-side + `public/style.css` + SVG images in `public/images/`.
- Source: `src/courtzone/Db.java` (storage), `Auth.java` (hash + sessions), `Html.java` (landing/auth templates), `App.java` (routing + rules).

## Setup and run (tested on clean Windows, JDK 21)
Requires JDK 17+ only (`javac -version` must work).
```bat
cd "CourtZone Arena folder"
run.bat
```
Or manually:
```bat
mkdir out
javac -d out src\courtzone\*.java
java -cp out courtzone.App
```
Open http://localhost:3000 (or `%PORT%` if set). On Mac/Linux: `sh run.sh`.

## Test accounts (auto-seeded on first run)
- Admin: `admin@courtzone.ph` / `admin123` → `/admin` (reservations, users+top-up, rates)
- Customer: `user@test.com` / `user123` → balance ₱2000 → `/dashboard`, `/book`, `/my-bookings`, `/wallet`

Try: login as user → Book basketball Court A tomorrow 9–11AM → try overlapping 10–12AM (blocked) → try 2nd basketball same day (blocked) → book billiard same day (allowed) → login as admin → Cancel+Refund → user Wallet increases.

## AI tools used
- OpenCode with Muse Spark 1.3 Free: scaffolding, all Java source, CSS, SVG images, validation logic, and this README.
- Human review: ran full booking/overlap/refund/edit/delete test suite via PowerShell; fixed session-cookie test issue and one Java syntax error.

## Project structure
```
src/courtzone/{App,Db,Auth,Html}.java
public/{style.css,images/*.svg}
data/ (created at runtime, gitignored)
run.bat / run.sh
```
