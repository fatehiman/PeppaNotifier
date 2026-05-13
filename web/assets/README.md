# Web assets

## `notify.mp3` — required

Drop a short notification sound here as `notify.mp3` (a 0.5–1.5 s WAV/MP3 works
well). The web UI will play it on every new message after the user clicks
"Enable notifications" once.

The same file must also be copied to `android/app/src/main/res/raw/notify.mp3`
so both clients play identical audio.

If the file is missing, the page still works — sound just won't play.
