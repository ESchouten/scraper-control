// Copy to config.h and fill in. config.h is not committed (it holds your PIN).
#pragma once
#define ROBOT_PIN     ""              // eerste pincode; leeg = vragen op het scherm. Later wijzigen: rechts 1,5 s vasthouden
#define ROBOT_ADDRESS ""              // leeg = zoeken; of het adres van de robot
#define ROBOT_NAME    ""              // optioneel: naam van de robot, bv. "stal"
// Routes per knop (links, midden, rechts). Een '-' = knop doet niets.
#define ROUTE_BTN_A   'A'
#define ROUTE_BTN_B   'B'
#define ROUTE_BTN_C   'C'
// Optioneel: wifi, normaal ingesteld op de M5 zelf (instellingen > Wifi).
#define WIFI_SSID     ""
#define WIFI_PASS     ""
#define OTA_PASS      ""              // wachtwoord voor een update vanaf de computer (SCRAPER_OTA_PASS)
