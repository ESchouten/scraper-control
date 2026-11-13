// All words on the M5 screen, in Dutch and English. The M5 follows the robot's own
// language (Screen::language(), seen at every connect, kept in flash): a robot set to
// English gives an English M5. Other robot languages: English.
// To add a language: one more Texts below, and pick it in textsFor().
#pragma once
#include <cstring>

struct Texts {
  const char* code;   // "nl" / "en", for the core (Robot::lang)
  // home
  // holdWord: the small caption on every hold key (a key that fills up while its button is held)
  const char *holdWord, *holdPlanning, *holdManual, *holdPin, *route;
  // progress steps
  const char *connecting, *checkingPin, *waitingPanel, *readingStatus, *switchingPlanning,
      *planningOffForNow, *openingRouteMenu, *finishing, *safetyYes, *choosingRoute, *pressingStart,
      *answeringQuestion, *stepOf, *of, *pleaseWait;
  // status rows
  const char *planning, *on, *off, *nextRide, *routeRow, *action, *robotBattery;
  // result
  const char *pressAnyButton, *started, *planningIs, *planningOnWord, *planningOffWord, *manualDone,
      *robotStatus, *checkPlanning, *routeStopped;
  // manual drive
  const char *manual, *left, *direction, *right, *forward, *backward, *driving, *releaseToStop,
      *done, *drive, *motorL, *motorR;
  // PIN
  const char *pinTitle, *cancelWord, *digitMinus, *digitPlus, *next, *pinRejected, *enterPin, *newPin;
  // settings (hold right): PIN / robot / wifi
  const char *settings, *robotWord, *updateWord, *robotRow, *automatic, *wifiRow, *notSet;
  // choose robot
  const char *searching, *noneFound, *nextWord, *choose, *back, *strongest, *robotSaved;
  // update over wifi
  const char *updateTitle, *noWifi, *wifiConnecting, *wifiFailed, *pressToStop;
  // wifi on the M5 itself: choose a network and type the password, or WPS
  const char *networkWord, *wpsWord, *versionRow, *scanningWifi, *noNetworks, *passwordFor, *spaceWord,
      *wifiSaved, *wpsPress, *wpsFailed;
  // the M5's own updates (signed releases)
  const char *checkingUpdate, *upToDate, *newVersion, *install, *downloading, *noUpdateInfo, *updateFailed,
      *notTrusted, *restarting, *autoUpdating, *updatedTo;
  // after an emergency stop the robot asks "route afmaken?": the user answers on the M5
  const char *finishRoute, *finishYesHint, *finishNoHint, *yesWord, *noWord, *laterWord, *routeContinues,
      *routeCancelled, *questionLeftOpen;
  // settings: looking for known wifi at once, for an update (Erik 2026-11-11)
  const char *settingsWifiLooking, *settingsWifiNone, *settingsUpToDate;
  // home screen: the robot as last seen, read every 15 minutes (printf formats: %d)
  const char *notReadYet, *readEvery, *justNow, *minAgo, *hourAgo;
};

static const Texts kNl = {
    "nl",
    "HOUD VAST", "Tijdpad", "Handbed.", "Instellen", "Route",
    "Verbinden", "Pincode controleren", "Synchroniseren met robot", "Status lezen", "Tijdpad omzetten",
    "Tijdpad tijdelijk uitzetten", "Routemenu openen", "Afronden", "Veiligheidsvraag: Ja", "Route kiezen",
    "Start indrukken", "Vraag over het tijdpad beantwoorden", "Stap", "van", "Even geduld...",
    "Tijdpad", "Aan", "Uit", "Volgende rit", "Route", "Actie", "Accu robot",
    "Druk op een knop om verder te gaan", "gestart", "Tijdpad staat", "aan", "uit", "Handbediening klaar",
    "Status van de robot", "Kijk even bij de robot en controleer het tijdpad.", "Route gestopt",
    "Handbediening", "Links", "Richting", "Rechts", "Vooruit", "Achteruit", "Rijdt",
    "Loslaten om te stoppen", "Klaar", "Rijden", "Motor L", "Motor R",
    "Pincode", "Annuleren", "Cijfer -", "Cijfer +", "Volgende",
    "Pincode geweigerd. Probeer opnieuw:", "Vul de pincode van de robot in:", "Nieuwe pincode:",
    "Instellingen", "Robot", "Update", "Robot", "automatisch (sterkste)", "Wifi", "niet ingesteld",
    "Robots zoeken...", "Geen robot gevonden. Is een andere app nog verbonden?", "Volgende", "Kiezen", "Terug",
    "Automatisch: de sterkste", "Robot opgeslagen",
    "Update", "Nog geen wifi. Kies eerst Netwerk of WPS.",
    "Verbinden met wifi...", "Wifi lukt niet. Klopt het wachtwoord?", "Druk op een knop om te stoppen",
    "Netwerk", "WPS", "Versie", "Wifi-netwerken zoeken...", "Geen wifi-netwerk gevonden.", "Wachtwoord voor", "spatie",
    "Verbonden en opgeslagen:", "Druk binnen 2 minuten op de WPS-knop van je router.",
    "WPS lukte niet. Kies anders Netwerk en typ het wachtwoord.",
    "Kijken of er een update is...", "Je hebt de nieuwste versie.", "Nieuwe versie", "Installeren", "Downloaden...",
    "Kan de updates nu niet bereiken. Probeer het later nog eens.", "Update mislukt. De huidige versie blijft staan.",
    "Update niet vertrouwd: de handtekening klopt niet. Er is niets veranderd.", "Klaar. De knop start opnieuw.",
    "Nieuwe versie wordt geinstalleerd. Een knop indrukken breekt af.", "Bijgewerkt naar versie",
    "Route afmaken?", "Ja: de robot rijdt de route verder.", "Nee: de route stopt, het tijdpad blijft uit.",
    "Ja", "Nee", "Later", "Route gaat verder", "Route afgebroken",
    "De vraag staat nog op de robot. Beantwoord hem daar, of druk weer op een routeknop.",
    "Zoekt wifi voor update...", "Geen bekende wifi in de buurt.", "Nieuwste versie, geen update.",
    "Nog niet gelezen", "Leest de robot elke 15 min", "zojuist",
    "%d min geleden", "%d uur geleden",
};

static const Texts kEn = {
    "en",
    "HOLD", "Schedule", "Manual", "Settings", "Route",
    "Connecting", "Checking PIN", "Syncing with robot", "Reading status", "Switching schedule",
    "Pausing schedule", "Opening route menu", "Finishing", "Safety question: Yes", "Choosing route",
    "Pressing Start", "Answering the schedule question", "Step", "of", "Please wait...",
    "Schedule", "On", "Off", "Next run", "Route", "Action", "Robot battery",
    "Press a button to continue", "started", "Schedule is", "on", "off", "Manual control done",
    "Robot status", "Please check the robot and its schedule.", "Route stopped",
    "Manual control", "Left", "Direction", "Right", "Forward", "Backward", "Driving",
    "Release to stop", "Done", "Drive", "Motor L", "Motor R",
    "PIN", "Cancel", "Digit -", "Digit +", "Next",
    "PIN rejected. Try again:", "Enter the robot's PIN:", "New PIN:",
    "Settings", "Robot", "Update", "Robot", "automatic (strongest)", "Wi-Fi", "not set",
    "Searching for robots...", "No robot found. Is another app still connected?", "Next", "Choose", "Back",
    "Automatic: the strongest", "Robot saved",
    "Update", "No Wi-Fi yet. Choose Network or WPS first.",
    "Connecting to Wi-Fi...", "Wi-Fi failed. Is the password right?", "Press a button to stop",
    "Network", "WPS", "Version", "Searching for Wi-Fi networks...", "No Wi-Fi network found.", "Password for", "space",
    "Connected and saved:", "Press the WPS button on your router within 2 minutes.",
    "WPS did not work. Choose Network instead and type the password.",
    "Checking for an update...", "You have the latest version.", "New version", "Install", "Downloading...",
    "Cannot reach the updates right now. Please try again later.", "Update failed. The current version stays.",
    "Update not trusted: the signature does not match. Nothing changed.", "Done. The button restarts.",
    "Installing a new version. Press any button to stop.", "Updated to version",
    "Finish the route?", "Yes: the robot drives the rest of the route.", "No: the route ends, the schedule stays off.",
    "Yes", "No", "Later", "Route continues", "Route cancelled",
    "The question is still on the robot. Answer it there, or press a route button again.",
    "Finding wifi for update...", "No known wifi nearby.", "Latest version, no update.",
    "Not read yet", "Reads the robot every 15 min", "just now",
    "%d min ago", "%d h ago",
};

// "nl" -> Dutch, anything else -> English.
inline const Texts& textsFor(const char* code) {
  return code && std::strcmp(code, "nl") == 0 ? kNl : kEn;
}
