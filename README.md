# Safe Import — Handy Safe replacement (Android, 64-bit)

This is a **new, independent Android app** (Kotlin + Jetpack Compose) that reads a JSON
export of your old Handy Safe data and stores it going forward in its own
Android-Keystore-encrypted local storage. It contains **no code from the original Handy
Safe app** — the old `.so`/`.apk` was only used, locally and temporarily, to verify the
file format and produce a one-time plaintext export of *your own* data.

It targets a recent Android SDK and is restricted to 64-bit ABIs only
(`arm64-v8a`, `x86_64`) — no 32-bit (`armeabi-v7a`/`x86`) output, so it matches modern
Android 16 devices.

## Getting a real .apk automatically (GitHub Actions)

This project includes `.github/workflows/build-apk.yml`, which builds a debug APK for you
on GitHub's servers — no local Android Studio install needed:

1. Create a new (private, if you like) repository on GitHub.
2. Upload the contents of this folder to it (drag-and-drop on github.com works, or `git
   push` if you're comfortable with git).
3. Go to the repo's **Actions** tab — a "Build APK" run should start automatically (or
   click "Run workflow" to trigger it manually).
4. When it finishes (a few minutes), open the run and download the
   **SafeImportApp-debug-apk** artifact at the bottom of the page — that's your `.apk`.
5. Copy it to your phone and install it (you'll need to allow "install from unknown
   sources" for a debug build like this, since it isn't Play Store-signed).

## How to build locally instead (Android Studio)

1. Open this folder in Android Studio (a recent version — it will prompt to install any
   missing SDK/AGP components).
2. Let Gradle sync (this needs normal internet access to Google's/Maven's repositories,
   which this build could **not** reach in the sandbox where the code was written — that's
   why it hasn't been compiled into an `.apk` for you already).
3. Bump `compileSdk` / `targetSdk` in `app/build.gradle.kts` to whatever "Android 16" maps
   to in your installed SDK (already set to 36, adjust if Studio suggests otherwise).
4. Run on a device/emulator, or Build → Generate Signed App Bundle/APK.

## How to use it

1. Copy `items_full.json` (provided alongside this project) onto the phone.
2. Launch the app → **Import JSON** → pick that file.
3. Browse your folders/cards. Sensitive-looking fields (PIN, password, card number) are
   masked by default — tap the eye icon to reveal.
4. **Delete `items_full.json` from the phone** once you've confirmed the import looks
   right — from then on your data lives only inside the app's encrypted storage.

## Important notes on the data

- `items_full.json` is a **plaintext** export of everything in your Handy Safe database
  (titles, names, PINs, card numbers where they were stored as text). Treat that file like
  you would a plaintext password list — delete it after import, don't upload it anywhere,
  don't leave it in cloud-synced folders.
- The field extraction is a **best-effort heuristic**: each entry's text was recovered by
  scanning the decrypted record for readable UTF-16 strings and pairing them up as
  label → value. This worked cleanly for the great majority of entries (titles, bank
  names, PINs, first/last names, folder structure). A few purely numeric fields (some
  card numbers, expiry dates) did not come through as text in every entry — if a "Card #"
  or "Expires" row looks empty for a specific card, that value wasn't recovered
  automatically and you'll want to re-enter it by hand after checking the physical card
  or a statement.
- If you'd like the raw decrypted bytes for anything that looks off, they're preserved
  per-record (ask, and they can be provided) so a field can be re-decoded by hand.

## How the format was recovered

Handy Safe's database (`.ind` index + `.dat` data files) is encrypted with Blowfish in a
custom block-chaining mode, with a key derived via MD5 from your password plus a
hard-coded salt embedded in the app. Rather than guess at this, the original app's native
library was executed (via a temporary ARM emulation shim, not shipped anywhere) with your
real password to confirm the header layout, password-check logic, and per-record
decryption byte-for-byte, then the decrypted content was exported once to JSON. That
verified export is what this app imports — this avoids shipping any reverse-engineered
proprietary crypto code inside the new app itself.
