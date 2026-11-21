Supproxy - SOCKS5 / HTTP proxy client for Android (Android 8.0+)

Build the APK with GitHub Actions: Actions -> "Build APK" -> Run workflow,
then download the "Supproxy-apk" artifact and install Supproxy.apk.

Production builds
Use a protected GitHub Actions environment named production restricted to the
trusted release branch. Add environment
secrets SUPPROXY_KEYSTORE_BASE64, SUPPROXY_STORE_PASSWORD, SUPPROXY_KEY_ALIAS,
SUPPROXY_KEY_PASSWORD, and SUPPROXY_SIGNING_CERT_SHA256. Use the existing trusted
release key and its certificate SHA-256 fingerprint. Run Build APK manually
with production enabled. The job verifies the certificate before publishing.