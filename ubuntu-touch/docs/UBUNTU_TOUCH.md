# asom on Ubuntu Touch: what runs when, what leaves the phone, what the limits are

Status: UT-0 scaffold. Nothing here is released, and nothing described as "the app does X" has been seen on a phone yet: device
behaviour is listed in `DEVICE_CHECKLIST_UT.md` as NEEDS-DEVICE-VALIDATION.

## What runs when

- **Only while asom is open on screen.** Lomiri freezes an app that is not in front, together with everything the app started, and
  the system suspends about four seconds after the display goes off. asom neither works around this nor asks you to change any
  system setting. It does not run at boot, has no background service, and never shows a notification.
- **Two processes, one pipe.** The screen you see (QML) starts a small Java program that is bundled in the app. They talk over an
  anonymous pipe that no other program can open. There is **no network port, no local server, no address** to connect to: other
  apps on the phone cannot use asom, on purpose.
- **Before it starts the Java program, the app checks two checksum lists** (the bundled Java runtime and the program) against the
  files in its own install directory. If a file was changed, it does not start and the Status screen says which part.
- **When you leave the app or lock the phone,** the program stops what it is doing, closes anything it opened and writes down
  what was cut off. It never repeats a request by itself: your text has already left, and repeating it would send it a second time.

## What leaves the phone

Nothing, by itself. asom sends nothing when it starts, sits open, or is idle: it does not check for updates, count starts, report
crashes, or use the push service. Text leaves only when **you** press Send, and then only to a device **you** paired, never to a
cloud service (there are no cloud keys on Ubuntu Touch). Every network event is written to the ledger on this phone, which you can read on
the Ledger screen. UT-0 has no pairing yet, so today nothing can leave at all.

What is **not** asom's: the OpenStore checks for updates on its own, with your device model and OS version; UBports system updates;
any VPN you installed yourself. asom never installs, configures or depends on a VPN. Without one, this phone reaches only devices
on the same Wi-Fi.

## The node key, and its limits

The node's identity will be a key file in the app's private folder (UT-1). Be clear about what that protects:

- It is readable by anything that runs as you outside the app's confinement: the Terminal app, `adb shell`, SSH, an unconfined app,
  a Libertine container, and root.
- It does not prove the key exists on this phone only. Copying the app's data folder (MTP, adb, a backup tool) to another device
  copies the node's identity. The right move is to pair again, not to restore.
- It is encrypted at rest only if you turned on filesystem encryption where your device supports it (experimental on 24.04).
  Without it, a stolen powered-off phone gives its key to anyone who can read the flash.
- There is no lock-screen binding for the key. What asom does instead is behaviour: it does not start new requests unless the app
  is in front and has said so within the last ten seconds. A stolen phone that is unlocked and running keeps its pairings until
  you revoke them on your other devices.
- Peers see this device as "key storage: file (self-reported)". A pin proves "the key you paired", never "this phone's hardware".

## Names

Device names and models shown by asom are **what a peer says about itself** and are labelled "(self-reported)". A peer's name can
be anything, including characters that look like something else, so control and formatting characters in it are replaced before
they are shown.

## Colour

Colour is never the only signal. The pair violet (this device) and cyan (a peer) always comes with a shape (diamond, triangle) and
a word. Red and green are not used to mean anything.

## Verification status

This document describes intended behaviour. The pieces that were checked in a lab (framing, the lifecycle rules, error words, the
provenance line, the Java runtime, the checksum loader) are listed in `../README.md`; nothing was checked on a phone.
