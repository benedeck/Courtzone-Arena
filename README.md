# CourtZone Arena

A booking system for a sports facility here in Manila. It has basketball, volleyball, badminton (2 courts each, Court A and B) and billiard (3 tables: A, B, C). Players can reserve a date and time online instead of just walking in

Main part of the system: **Reservations**
# What problem this solves

Right now courts use a logbook. Two groups can show up for the same court at the same time and argue about it. This system blocks double booking, checks the time is inside opening hours (8AM to 12 midnight), and keeps track of payments and refunds in a wallet so nothing gets lost

# Features
**Landing page**
- Public page, no login needed. Shows the business name, the 4 sports, prices, and pictures.
- Has Book Now and Login buttons. Works on phone screens too

**Users**
- Register with name, email, password. Then login and logout
- Passwords are hashed (SHA-256 with salt), not saved as plain text
- Two roles: admin and customers. Pages that need login will send you to the login page if you are not signed in

**Reservations (CRUD)**
- Create: book a sport, court, date, start and end time
- Read: dashboard, My Bookings page, availability table, and the admin list
- Update: reschedule a booking (price difference is charged or refunded)
- Delete: cancel a booking (money goes back to the wallet) or delete it for real. Both ask for confirmation first
- All data is saved in text files under `./data`, so it is still there after restart

**Rules**
- Open 8:00 AM to 12:00 midnight. Minimum 1 hour, no maximum
- Same court + overlapping time = blocked. You can still book the other court or another time
- Only 1 reservation per sport per user per day
- Prices per hour: Basketball P600, Volleyball P500, Badminton P200, Billiard P120. Total = price x hours.
- Booking takes money from your wallet. Cancel = full refund to the wallet. Only the admin can add money to a wallet

## Tech used
Just Java (JDK 17 and up), no frameworks or libraries. It uses the web server that already comes with Java. Data is stored in text files. The pages are plain HTML with one CSS file

Files:
- `src/courtzone/App.java` - routes and booking rules
- `src/courtzone/Db.java` - loading and saving the text files
- `src/courtzone/Auth.java` - password hashing and login sessions
- `src/courtzone/Html.java` - landing page and login/register forms
- `public/` - CSS and images

## How to run

You only need JDK 17 or newer. Check with `javac -version`.

```
run.bat
```

Or step by step:

```
mkdir out
javac -d out src\courtzone\*.java
java -cp out courtzone.App
```

Then open http://localhost:3000 in the browser. On Mac/Linux use `sh run.sh`.

## Test accounts

Made automatically on first run:

- Admin: `admin@courtzone.ph` / `admin123` (can see all bookings, cancel with refund, add balance, change prices)
- Customer: `user@test.com` / `user123` (starts with P2000 balance)

To try it: log in as the customer, book basketball Court A tomorrow 9 to 11 AM. Then try booking 10 AM to 12 PM on the same court, it will be blocked. Then log in as admin and cancel the booking, and the money goes back to the customer wallet

## AI disclosure

I used OpenCode (Muse Spark) to help write the code, the CSS, and this README. I tested everything myself: booking, overlap blocking, refunds, edit, and delete
