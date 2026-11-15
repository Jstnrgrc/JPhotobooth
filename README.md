# Daily Sweet Doze Photobooth

This is the working foundation for one offline Android booth. Install the debug app from Android Studio. The booth owner should complete first-time admin setup before making the device available to customers.

1. Log in and generate a batch. Export the A4 ticket PDF and print it on regular paper.
2. Collect cash and record issuance from the ticket details. Tickets are redeemable before issuance; keep the unused inventory private.
3. Log out. Enter a ticket and tap Start. On the live camera, tap Start countdown. After 3–2–1, each photo gets its own 10-second countdown. Choose a border in the editor and tap Print receipt.
4. Configure the printer in Admin → Printer setup. See THERMAL-PRINTING.md for setup and recovery. The same ticket resumes an unfinished session; completed tickets cannot create another.
5. Verify restart recovery, multi-page A4 export, and camera output on the booth device before a pilot.

MainActivity.kt owns the plain Android screens and PDF rendering. BoothStore.kt owns SQLite persistence, ticket lookup, credentials, sessions, and queue state. The SQLite implementation uses built-in APIs, so this foundation compiles with the existing project dependencies. CameraX runs the live camera and capture inside the app.

Printer hardware validation, managed kiosk mode, and final styling are later work. There is no portable backup in this version: clearing app data or reinstalling removes booth records and valid codes. Exported PDFs are outside app retention; handle and delete them as appropriate. Completed session photos are cleaned up on a subsequent app launch after 24 hours.


## Photos and Gallery

The app keeps captures in its private files. In the editor, tap a photo to retake that slot. Choose Save to Gallery to copy the final selected photos and designed strip to Pictures / Daily Sweet Doze on the booth device. The Gallery copies are shared on that device and are not removed by the app's private-photo cleanup. Admins can find saved sessions under Photos & receipts.
