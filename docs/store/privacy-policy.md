# Privacy Policy — asom

> **This is the working copy, not the hosted one.** The URL Play Console points at
> is **https://asystemofcells.com/asom/privacy**. Keep the two in sync by hand.

**Last updated: 29 August 2026**

asom is a model-routing app for Android (package `xyz.mdhv.asom`), made by
A System of Cells. Its whole purpose is making network egress visible, so this
policy is unusually specific about it.

## The short version

asom has no accounts, no advertising, no analytics and no tracking. We operate no
servers and receive nothing. The only requests the app ever makes are the ones you
configured, to the providers you chose, with your own keys, and **every single one
of them is written to a ledger you can read**.

## What the app collects

**Nothing reaches us.** There is no telemetry, no crash reporting and no analytics
SDK in the build. This is a build-failing rule of the project, not a setting.

## What is sent off your device, and to whom

Exactly three kinds of request, and no others:

1. **Provider API calls.** When you or a paired app makes a request, asom sends it
   to the provider you configured, using **your** API key, over HTTPS. That
   provider receives it under their own privacy policy. We are not in the middle.
2. **The model catalogue**, fetched so the app knows what models exist.
3. **Model downloads**, when you ask for one.

**Every one of those writes a ledger row.** The row the dashboard shows and the
record the API reports are built from the same object, so they cannot disagree.

There is no automatic, background or silent transmission of anything. The app has
no upload path at all in this version.

## The server is local only

asom runs an API server bound to your device's loopback address, `127.0.0.1`. It is
**never** bound to your network, so nothing on your Wi-Fi, and nothing on the
internet, can reach it. Only apps on your own device can connect.

Apps that connect are identified by Android's own verified caller identity, not by
a token an app could copy from another app. There is no registration endpoint over
HTTP.

## Your API keys

Your keys are wrapped by the Android Keystore and stored encrypted on your device.
They are entered in one place, the Keys tab, and:

- they are never returned in any API response,
- they are never written to any log,
- they are never written to the ledger,
- they are sent only to the provider they belong to.

## What is stored, and where

Your keys, routing rules, model files and the egress ledger all live in the app's
private storage on your device. Uninstalling asom deletes all of it.

The ledger is yours. If you export it, that is a share sheet you invoke, and the
app shows you the exact payload before anything is handed off.

## Permissions, and why each exists

| Permission | Why |
|---|---|
| `INTERNET` | Provider API calls, the model catalogue, and model downloads. |
| `FOREGROUND_SERVICE` | Keep the local API server alive while apps are using it. |
| `POST_NOTIFICATIONS` | For the notification showing the server is running. |
| `RECEIVE_BOOT_COMPLETED` | To restart the server after a reboot, if you asked it to. |

## Children

asom is not directed at children and collects no personal information from anyone,
including children.

## Changes

If this policy changes, the "Last updated" date above changes with it, and the
revised policy is published at this same URL.

## Contact

asom is made by **A System of Cells**. Questions about this policy or the app:
asom@asystemofcells.com
