# Battle.net account service

Optional server component for the **account login**. Public character search, gear,
portraits and professions in the Android app work directly with Blizzard Armory
and do not require this service.

Requires Node.js 22+, an HTTPS reverse proxy, and a registered Battle.net OAuth
client. Register the exact redirect `https://YOUR-SERVICE/auth/callback`.
Configure these server-only environment variables:

- `PUBLIC_ORIGIN`: public HTTPS origin, with no subpath.
- `BATTLENET_CLIENT_ID` and `BATTLENET_CLIENT_SECRET`: owner OAuth application.
- `PORT`: local port, default 8080.

Run `node server/server.mjs`. Build Android with
`-PpulseApiBase=https://YOUR-SERVICE`. An empty setting excludes the account login
button. The secret must never be passed to Gradle or included in the APK.

The app opens Battle.net in the system browser. Passwords do not pass through
Azeroth Pulse. OAuth state is single-use, expires in ten minutes; the return code
expires in 90 seconds and can be redeemed only with the phone's verifier. The
callback contains a one-time code, never an access token. Blizzard access tokens
remain server-side. The app encrypts its opaque session with Android Keystore.
Sessions expire after one hour; restart of this minimal service signs everyone
out. Refresh tokens are not requested. DELETE /session erases the server session.

Run `node --test server/server.test.mjs` for the mocked OAuth integration checks.
No production deployment or real account login has been performed for 0.18.0.
Before public account login: register the owner's client, deploy the service,
verify EU/US responses and the Android callback on a real phone. Current Forever
account API behavior is not a stable documented contract. Keep the public-profile
flow available if the account API returns incomplete data.

The service never accepts an arbitrary API URL and does not expose Blizzard
credentials to clients. Deploy one process; a multi-instance deployment needs
shared expiring storage for states and sessions. Configure limits at the HTTPS
proxy before opening to a wider audience. No personal data is written to logs.
