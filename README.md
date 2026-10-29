# FaithScroll ✝

A Bible verse widget and a TikTok-style feed of inspirational verses and real Christian testimonies, built to encourage reading God's Word every day.

## Features
- **Home-screen widget** — a random verse, with a background you can set from fully transparent to solid, light or dark text, a new verse every hour, and a ↻ button for a new one anytime. Tap it to open that verse in the app.
- **Swipe feed** — full-screen verse cards with living, slowly shifting gradients. Swipe up for the next one.
- **Testimony videos** — every few cards, a real story of faith from YouTube (I Am Second, 700 Club Testimonies, BibleProject). Tap to pause; swipe to move on.
- **Double-tap to "Amen"**, bookmark to save, and share verses as a beautiful image.
- **Read the chapter** — one tap opens the full chapter (KJV) with your verse highlighted.
- **Reading streak** to help build a daily habit.

## Install
1. Go to **Releases** (right side of the repo page) and download the latest `FaithScroll-*.apk` on your Android phone.
2. Open it and allow "install from unknown sources" if asked.
3. In the app, open the **Widget** tab → **Add widget to home screen**.

Every push to `main` builds a new APK automatically (see the **Actions** tab).

## Add your own content (no rebuild needed)
- `content/verses.json` — extra verses: `{"ref": "Psalm 19:1", "text": "...", "theme": "joy"}`. Themes: hope, love, strength, comfort, guidance, peace, courage, faith, grace, joy, prayer.
- `content/channels.json` — YouTube channels (by channel ID) whose latest videos appear in the feed, plus individual pinned `videos`.

The app picks up changes the next time it opens.

## Notes
Scripture is the King James Version (public domain). The signing key is in `keystore/` so updates install over each other. If you ever publish to the Play Store, create a new private key and store it as a GitHub secret instead.
