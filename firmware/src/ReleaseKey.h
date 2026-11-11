// Public half of the release key (ECDSA P-256). The M5 installs only firmware signed with the
// private half (CI secret FIRMWARE_SIGNING_KEY, made 2026-11-11; a copy stays with Erik).
#pragma once
static const char kReleaseKey[] =
    "-----BEGIN PUBLIC KEY-----\n"
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE5+BVstQnXROhuybMANmQsU1sRAZK\n"
    "TB7yUjvfV7I+WLhYFhBkSv4gVirRK6oehdckBqIeV8QmclcXYdsj4SsvLw==\n"
    "-----END PUBLIC KEY-----\n";
